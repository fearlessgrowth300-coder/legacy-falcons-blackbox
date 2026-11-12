#include "AudioTrackHook.h"

#include <android/log.h>
#include <unistd.h>

#include "JniHook/JniHook.h"

#define ATAG "AudioTrackProxy"

// createTrack does not go through IAudioService. AudioTrack.native_setup parcels
// the context AttributionSource and calls AudioFlinger directly. That source
// carries the clone package, so AudioFlinger finds no output thread (output 0,
// status -1, Java status -20). Pine cannot replace Builder.build on Android 16.
// IAttributionSourceProxy is not installed. This hook rewrites only this call.

static const char *PARCEL5 =
        "(Ljava/lang/Object;Ljava/lang/Object;[IIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)I";
static const char *PARCEL6 =
        "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)I";
static const char *LEGACY5 =
        "(Ljava/lang/Object;Ljava/lang/Object;[IIIII[IJZILjava/lang/Object;Ljava/lang/String;)I";
static const char *LEGACY6 =
        "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[IJZILjava/lang/Object;Ljava/lang/String;)I";

static jclass gClass = nullptr;
static jmethodID gInGuest = nullptr;
static jmethodID gOpPackage = nullptr;
static jmethodID gAttribution = nullptr;
static jmethodID gOutput = nullptr;
static jmethodID gLog = nullptr;
static jmethodID gRecycle = nullptr;

typedef jint (*SetupParcel5)(JNIEnv *, jobject, jobject, jobject, jintArray,
                             jint, jint, jint, jint, jint, jintArray, jobject,
                             jlong, jboolean, jint, jobject, jstring);
typedef jint (*SetupParcel6)(JNIEnv *, jobject, jobject, jobject, jintArray,
                             jint, jint, jint, jint, jint, jint, jintArray, jobject,
                             jlong, jboolean, jint, jobject, jstring);
typedef jint (*SetupLegacy5)(JNIEnv *, jobject, jobject, jobject, jintArray,
                             jint, jint, jint, jint, jint, jintArray,
                             jlong, jboolean, jint, jobject, jstring);
typedef jint (*SetupLegacy6)(JNIEnv *, jobject, jobject, jobject, jintArray,
                             jint, jint, jint, jint, jint, jint, jintArray,
                             jlong, jboolean, jint, jobject, jstring);

static SetupParcel5 orig_parcel5 = nullptr;
static SetupParcel6 orig_parcel6 = nullptr;
static SetupLegacy5 orig_legacy5 = nullptr;
static SetupLegacy6 orig_legacy6 = nullptr;

static void note(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, ATAG, "%s", message);
}

static bool resolveJava(JNIEnv *env) {
    if (gClass != nullptr) return gInGuest != nullptr;
    jclass local = env->FindClass("top/niunaijun/blackbox/core/AudioTrackHost");
    if (local == nullptr) {
        env->ExceptionClear();
        note("native_setup hook missed: AudioTrackHost");
        return false;
    }
    gClass = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    gInGuest = env->GetStaticMethodID(gClass, "inGuest", "()Z");
    gOpPackage = env->GetStaticMethodID(gClass, "opPackage", "(Ljava/lang/String;)Ljava/lang/String;");
    gAttribution = env->GetStaticMethodID(gClass, "hostAttribution", "(Landroid/os/Parcel;I)Landroid/os/Parcel;");
    gOutput = env->GetStaticMethodID(gClass, "outputForStatus", "(I)I");
    gLog = env->GetStaticMethodID(gClass, "logCreateTrack", "(Ljava/lang/String;IIIIZ)V");
    if (env->ExceptionCheck() || gInGuest == nullptr || gOpPackage == nullptr
            || gAttribution == nullptr || gOutput == nullptr || gLog == nullptr) {
        env->ExceptionClear();
        note("native_setup hook missed: AudioTrackHost methods");
        return false;
    }
    return true;
}

static bool hasSetup(JNIEnv *env, const char *signature) {
    jclass clazz = env->FindClass("android/media/AudioTrack");
    if (clazz == nullptr) {
        env->ExceptionClear();
        return false;
    }
    jmethodID method = env->GetMethodID(clazz, "native_setup", signature);
    if (method == nullptr) env->ExceptionClear();
    env->DeleteLocalRef(clazz);
    return method != nullptr;
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

static jint sessionOf(JNIEnv *env, jintArray session) {
    if (session == nullptr) return 0;
    jint value = 0;
    env->GetIntArrayRegion(session, 0, 1, &value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return value;
}

template <typename Call>
static jint rewriteAndCall(JNIEnv *env, jobject parcel, jstring opPackage, jintArray session, Call call) {
    if (!resolveJava(env)) return call(parcel, opPackage);
    jboolean guest = env->CallStaticBooleanMethod(gClass, gInGuest);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        guest = JNI_FALSE;
    }
    if (!guest) return call(parcel, opPackage);

    jint uid = (jint) getuid();
    jstring pkg = (jstring) env->CallStaticObjectMethod(gClass, gOpPackage, opPackage);
    if (env->ExceptionCheck() || pkg == nullptr) {
        env->ExceptionClear();
        pkg = opPackage;
    }
    jobject created = nullptr;
    jobject useParcel = parcel;
    if (parcel != nullptr) {
        created = env->CallStaticObjectMethod(gClass, gAttribution, parcel, uid);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            created = nullptr;
        }
        if (created != nullptr) useParcel = created;
    }
    jint status = call(useParcel, pkg);
    jthrowable thrown = env->ExceptionOccurred();
    if (thrown != nullptr) env->ExceptionClear();
    jint sess = sessionOf(env, session);
    jint output = env->CallStaticIntMethod(gClass, gOutput, status);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        output = status == 0 ? -1 : 0;
    }
    env->CallStaticVoidMethod(gClass, gLog, pkg, uid, sess, output, status,
                              created != nullptr ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (created != nullptr) {
        recycleParcel(env, created);
        env->DeleteLocalRef(created);
    }
    if (pkg != nullptr && pkg != opPackage) env->DeleteLocalRef(pkg);
    if (thrown != nullptr) {
        env->Throw(thrown);
        env->DeleteLocalRef(thrown);
    }
    return status;
}

static jint new_parcel5(JNIEnv *env, jobject thiz, jobject weak, jobject attrs, jintArray rates,
                        jint channel, jint index, jint format, jint bytes, jint mode,
                        jintArray session, jobject parcel, jlong nativeTrack, jboolean offload,
                        jint encapsulation, jobject tuner, jstring opPackage) {
    return rewriteAndCall(env, parcel, opPackage, session, [&](jobject useParcel, jstring pkg) {
        return orig_parcel5(env, thiz, weak, attrs, rates, channel, index, format, bytes, mode,
                            session, useParcel, nativeTrack, offload, encapsulation, tuner, pkg);
    });
}

static jint new_parcel6(JNIEnv *env, jobject thiz, jobject weak, jobject attrs, jintArray rates,
                        jint channel, jint index, jint format, jint bytes, jint mode, jint extra,
                        jintArray session, jobject parcel, jlong nativeTrack, jboolean offload,
                        jint encapsulation, jobject tuner, jstring opPackage) {
    return rewriteAndCall(env, parcel, opPackage, session, [&](jobject useParcel, jstring pkg) {
        return orig_parcel6(env, thiz, weak, attrs, rates, channel, index, format, bytes, mode,
                            extra, session, useParcel, nativeTrack, offload, encapsulation, tuner, pkg);
    });
}

static jint new_legacy5(JNIEnv *env, jobject thiz, jobject weak, jobject attrs, jintArray rates,
                        jint channel, jint index, jint format, jint bytes, jint mode,
                        jintArray session, jlong nativeTrack, jboolean offload,
                        jint encapsulation, jobject tuner, jstring opPackage) {
    return rewriteAndCall(env, nullptr, opPackage, session, [&](jobject, jstring pkg) {
        return orig_legacy5(env, thiz, weak, attrs, rates, channel, index, format, bytes, mode,
                            session, nativeTrack, offload, encapsulation, tuner, pkg);
    });
}

static jint new_legacy6(JNIEnv *env, jobject thiz, jobject weak, jobject attrs, jintArray rates,
                        jint channel, jint index, jint format, jint bytes, jint mode, jint extra,
                        jintArray session, jlong nativeTrack, jboolean offload,
                        jint encapsulation, jobject tuner, jstring opPackage) {
    return rewriteAndCall(env, nullptr, opPackage, session, [&](jobject, jstring pkg) {
        return orig_legacy6(env, thiz, weak, attrs, rates, channel, index, format, bytes, mode,
                            extra, session, nativeTrack, offload, encapsulation, tuner, pkg);
    });
}

void AudioTrackHook::init(JNIEnv *env) {
    if (!resolveJava(env)) return;
    if (hasSetup(env, PARCEL5)) {
        JniHook::HookJniFun(env, "android/media/AudioTrack", "native_setup", PARCEL5,
                            (void *) new_parcel5, (void **) &orig_parcel5, false);
        note(orig_parcel5 != nullptr ? "native_setup hook parcel5" : "native_setup hook parcel5 failed");
        return;
    }
    if (hasSetup(env, PARCEL6)) {
        JniHook::HookJniFun(env, "android/media/AudioTrack", "native_setup", PARCEL6,
                            (void *) new_parcel6, (void **) &orig_parcel6, false);
        note(orig_parcel6 != nullptr ? "native_setup hook parcel6" : "native_setup hook parcel6 failed");
        return;
    }
    if (hasSetup(env, LEGACY5)) {
        JniHook::HookJniFun(env, "android/media/AudioTrack", "native_setup", LEGACY5,
                            (void *) new_legacy5, (void **) &orig_legacy5, false);
        note(orig_legacy5 != nullptr ? "native_setup hook legacy5" : "native_setup hook legacy5 failed");
        return;
    }
    if (hasSetup(env, LEGACY6)) {
        JniHook::HookJniFun(env, "android/media/AudioTrack", "native_setup", LEGACY6,
                            (void *) new_legacy6, (void **) &orig_legacy6, false);
        note(orig_legacy6 != nullptr ? "native_setup hook legacy6" : "native_setup hook legacy6 failed");
        return;
    }
    note("native_setup hook missed: no signature");
}
