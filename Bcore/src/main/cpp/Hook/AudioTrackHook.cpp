#include "AudioTrackHook.h"

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include "JniHook/JniHook.h"

#define ATAG "AudioTrackProxy"

// AudioTrack.native_setup calls AudioFlinger directly. The previous hook guessed
// the JNI signature, so on this Android 16 build GetMethodID missed the real
// method and the original kept sending the clone package (output 0, status -1).
// The guest process now reflects the real method and installs this trampoline
// on that exact descriptor.

static jclass gClass = nullptr;
static jmethodID gInGuest = nullptr;
static jmethodID gOpPackage = nullptr;
static jmethodID gAttribution = nullptr;
static jmethodID gLog = nullptr;
static jmethodID gRecycle = nullptr;
static int gParcelSlot = -1;
static int gStringSlot = -1;
static int gSessionSlot = -1;
static void *gOrig = nullptr;

struct CallState {
    JNIEnv *env;
    jobject thiz;
    jobject session;
    jobject createdParcel;
    jstring createdPackage;
    uint64_t lr;
    bool guest;
    bool active;
};

static thread_local CallState tCall;

static void note(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, ATAG, "%s", message);
}

static bool resolveJava(JNIEnv *env) {
    if (gClass != nullptr) return gInGuest != nullptr;
    jclass local = env->FindClass("top/niunaijun/blackbox/core/AudioTrackHost");
    if (local == nullptr) {
        env->ExceptionClear();
        note("hook install failed: AudioTrackHost class");
        return false;
    }
    gClass = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    gInGuest = env->GetStaticMethodID(gClass, "inGuest", "()Z");
    gOpPackage = env->GetStaticMethodID(gClass, "opPackage", "(Ljava/lang/String;)Ljava/lang/String;");
    gAttribution = env->GetStaticMethodID(gClass, "hostAttribution", "(Landroid/os/Parcel;I)Landroid/os/Parcel;");
    gLog = env->GetStaticMethodID(gClass, "logCreateTrack", "(Ljava/lang/String;IIIIZ)V");
    if (env->ExceptionCheck() || gInGuest == nullptr || gOpPackage == nullptr
            || gAttribution == nullptr || gLog == nullptr) {
        env->ExceptionClear();
        note("hook install failed: AudioTrackHost methods");
        return false;
    }
    return true;
}

static jobject readSlot(uint64_t *regs, void *stack, int slot) {
    if (slot < 0) return nullptr;
    if (slot < 8) return reinterpret_cast<jobject>(regs[slot]);
    auto bytes = reinterpret_cast<uint8_t *>(stack);
    return *reinterpret_cast<jobject *>(bytes + (slot - 8) * 8);
}

static void writeSlot(uint64_t *regs, void *stack, int slot, jobject value) {
    if (slot < 0) return;
    if (slot < 8) {
        regs[slot] = reinterpret_cast<uint64_t>(value);
        return;
    }
    auto bytes = reinterpret_cast<uint8_t *>(stack);
    *reinterpret_cast<jobject *>(bytes + (slot - 8) * 8) = value;
}

static void recycleParcel(JNIEnv *env, jobject parcel) {
    if (parcel == nullptr) return;
    if (gRecycle == nullptr) {
        jclass clazz = env->GetObjectClass(parcel);
        gRecycle = env->GetMethodID(clazz, "recycle", "()V");
        env->DeleteLocalRef(clazz);
        if (gRecycle == nullptr) {
            env->ExceptionClear();
            return;
        }
    }
    env->CallVoidMethod(parcel, gRecycle);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static jint sessionOf(JNIEnv *env, jobject session) {
    if (session == nullptr) return 0;
    jint value = 0;
    env->GetIntArrayRegion(reinterpret_cast<jintArray>(session), 0, 1, &value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return value;
}

static jint readOutput(JNIEnv *env, jobject thiz, jint status) {
    if (status != 0 || thiz == nullptr) return 0;
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID field = env->GetFieldID(clazz, "mNativeTrackInJavaObj", "J");
    env->DeleteLocalRef(clazz);
    if (field == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    jlong ptr = env->GetLongField(thiz, field);
    if (env->ExceptionCheck() || ptr < 0x1000) {
        env->ExceptionClear();
        return -1;
    }
    using GetOutput = int (*)(void *);
    static GetOutput fn = nullptr;
    static bool looked = false;
    if (!looked) {
        looked = true;
        fn = reinterpret_cast<GetOutput>(dlsym(RTLD_DEFAULT, "_ZNK7android10AudioTrack9getOutputEv"));
        if (fn == nullptr) {
            fn = reinterpret_cast<GetOutput>(dlsym(RTLD_DEFAULT, "_ZN7android10AudioTrack9getOutputEv"));
        }
    }
    if (fn == nullptr) return -1;
    return fn(reinterpret_cast<void *>(ptr));
}

extern "C" uint64_t audio_prepare(uint64_t *regs, void *stack, uint64_t lr) {
    tCall = {};
    tCall.lr = lr;
    tCall.env = reinterpret_cast<JNIEnv *>(regs[0]);
    tCall.thiz = reinterpret_cast<jobject>(regs[1]);
    tCall.active = true;
    JNIEnv *env = tCall.env;
    if (gOrig == nullptr || env == nullptr || !resolveJava(env)) return reinterpret_cast<uint64_t>(gOrig);
    if (gSessionSlot >= 0) tCall.session = readSlot(regs, stack, gSessionSlot);
    jboolean guest = env->CallStaticBooleanMethod(gClass, gInGuest);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        guest = JNI_FALSE;
    }
    tCall.guest = guest == JNI_TRUE;
    if (!tCall.guest) return reinterpret_cast<uint64_t>(gOrig);

    jint uid = (jint) getuid();
    if (gStringSlot >= 0) {
        auto reported = reinterpret_cast<jstring>(readSlot(regs, stack, gStringSlot));
        auto packageName = reinterpret_cast<jstring>(env->CallStaticObjectMethod(gClass, gOpPackage, reported));
        if (env->ExceptionCheck() || packageName == nullptr) {
            env->ExceptionClear();
        } else {
            writeSlot(regs, stack, gStringSlot, packageName);
            tCall.createdPackage = packageName;
        }
    }
    if (gParcelSlot >= 0) {
        jobject parcel = readSlot(regs, stack, gParcelSlot);
        if (parcel != nullptr) {
            jobject created = env->CallStaticObjectMethod(gClass, gAttribution, parcel, uid);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                created = nullptr;
            }
            if (created != nullptr) {
                writeSlot(regs, stack, gParcelSlot, created);
                tCall.createdParcel = created;
            }
        }
    }
    return reinterpret_cast<uint64_t>(gOrig);
}

extern "C" uint64_t audio_finish(uint64_t statusReg) {
    uint64_t lr = tCall.lr;
    JNIEnv *env = tCall.env;
    jint status = static_cast<jint>(statusReg);
    if (tCall.active && tCall.guest && env != nullptr && gClass != nullptr && gLog != nullptr) {
        jthrowable thrown = env->ExceptionOccurred();
        if (thrown != nullptr) env->ExceptionClear();
        jint session = sessionOf(env, tCall.session);
        jint output = readOutput(env, tCall.thiz, status);
        jstring packageName = tCall.createdPackage;
        if (packageName == nullptr) packageName = env->NewStringUTF("");
        env->CallStaticVoidMethod(gClass, gLog, packageName, (jint) getuid(), session, output, status,
                                  tCall.createdParcel != nullptr ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (tCall.createdParcel != nullptr) recycleParcel(env, tCall.createdParcel);
        if (thrown != nullptr) {
            env->Throw(thrown);
            env->DeleteLocalRef(thrown);
        }
    }
    tCall = {};
    return lr;
}

#if defined(__aarch64__)
extern "C" void audio_setup_trampoline();
#endif

const char *AudioTrackHook::hook(JNIEnv *env, jobject method, const char *descriptor,
                                 int parcelSlot, int stringSlot, int sessionSlot) {
    if (method == nullptr || descriptor == nullptr) return "method not found";
    if (stringSlot < 0) return "unsupported signature";
#if !defined(__aarch64__)
    (void) env;
    (void) parcelSlot;
    (void) sessionSlot;
    return "trampoline unsupported on this abi";
#else
    if (gOrig != nullptr) return "already installed";
    // Resolve AudioTrackHost now, while BlackBox code is on the stack. Inside the
    // hooked AudioTrack.native_setup, FindClass searches the boot class loader and
    // cannot see it, so every guest AudioTrack kept the clone's package and was refused.
    if (!resolveJava(env)) return "AudioTrackHost unresolved";
    gParcelSlot = parcelSlot;
    gStringSlot = stringSlot;
    gSessionSlot = sessionSlot;
    const char *error = "hook failed";
    void *orig = nullptr;
    bool ok = JniHook::HookReflected(env, method, "native_setup", descriptor,
                                     reinterpret_cast<void *>(audio_setup_trampoline), &orig, &error);
    if (!ok || orig == nullptr) {
        gParcelSlot = gStringSlot = gSessionSlot = -1;
        return error != nullptr ? error : "hook failed";
    }
    gOrig = orig;
    __android_log_print(ANDROID_LOG_ERROR, ATAG,
                        "hook install success signature=%s parcelSlot=%d stringSlot=%d sessionSlot=%d",
                        descriptor, parcelSlot, stringSlot, sessionSlot);
    return "installed";
#endif
}

void AudioTrackHook::init(JNIEnv *env) {
    note("hook install from enableIO");
    jclass clazz = env->FindClass("top/niunaijun/blackbox/core/AudioTrackHost");
    if (clazz == nullptr) {
        env->ExceptionClear();
        note("hook install failed: AudioTrackHost class");
        return;
    }
    jmethodID install = env->GetStaticMethodID(clazz, "installFromNative", "()V");
    if (install == nullptr) {
        env->ExceptionClear();
        note("hook install failed: installFromNative missing");
        env->DeleteLocalRef(clazz);
        return;
    }
    env->CallStaticVoidMethod(clazz, install);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        note("hook install failed: installFromNative threw");
    }
    env->DeleteLocalRef(clazz);
}
