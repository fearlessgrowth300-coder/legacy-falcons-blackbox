



#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/FileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include <Hook/RuntimeHook.h>
#include "Utils/HexDump.h"
#include "hidden_api.h"

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    FileSystemHook::init();
    VMClassLoaderHook::init(env);

    BinderHook::init(env);
    DexFileHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");

    JniHook::InitJniHook(env, api_level);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    ALOGD("set addIORule");
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    IO::init(env);
    nativeHook(env);
}

bool disableHiddenApi(JNIEnv *env, jclass clazz) {
    ALOGD("set disableHiddenApi");
    if(!disable_hidden_api(env)){
        ALOGD("set disableHiddenApi Fail!!!");
        return false;
    }
    return true;
}

bool disableResourceLoading(JNIEnv *env, jclass clazz) {
    ALOGD("set disableResourceLoading");
    if(!disable_resource_loading()){
        ALOGD("set disableResourceLoading Fail!!!");
        return false;
    }
    return true;
}

// Per-clone device spoof (see Utils/VirtualSpoof.cpp). Populates the native
// __system_property_get map for THIS guest process with a coherent per-userId
// profile (ro.product.model/brand/manufacturer/device/board/fingerprint/serial…).
extern "C" void vs_clear();
extern "C" void vs_set(const char *key, const char *value);
extern "C" void vs_ensure_installed();

// Per-guest transparent proxy (see Utils/ProxyRedirect.cpp).
extern "C" bool pr_set_proxy(int type, const char *host, int port, const char *user, const char *pass);
extern "C" void pr_disable();

// Block guest apps from running `logcat` (Meta crash reporter -> Android-13 "allow device logs"
// dialog). See Utils/BlockLogcat.cpp.
extern "C" void bl_install();

static jboolean setProxy(JNIEnv *env, jclass clazz, jint type, jstring host, jint port, jstring user, jstring pass) {
    if (host == nullptr) { pr_disable(); return JNI_FALSE; }
    const char *h = env->GetStringUTFChars(host, nullptr);
    const char *u = user ? env->GetStringUTFChars(user, nullptr) : nullptr;
    const char *p = pass ? env->GetStringUTFChars(pass, nullptr) : nullptr;
    bool ok = pr_set_proxy(type, h, port, u ? u : "", p ? p : "");
    env->ReleaseStringUTFChars(host, h);
    if (user) env->ReleaseStringUTFChars(user, u);
    if (pass) env->ReleaseStringUTFChars(pass, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

void disableProxy(JNIEnv *env, jclass clazz) {
    pr_disable();
}

void spoofDevice(JNIEnv *env, jclass clazz, jobjectArray keys, jobjectArray values) {
    ALOGD("set spoofDevice");
    bl_install();   // stop the guest's crash reporter from spawning logcat
    vs_clear();
    if (keys == nullptr || values == nullptr) { vs_ensure_installed(); return; }
    jsize n = env->GetArrayLength(keys);
    jsize m = env->GetArrayLength(values);
    if (m < n) n = m;
    for (jsize i = 0; i < n; ++i) {
        auto k = (jstring) env->GetObjectArrayElement(keys, i);
        auto v = (jstring) env->GetObjectArrayElement(values, i);
        if (k && v) {
            const char *ck = env->GetStringUTFChars(k, nullptr);
            const char *cv = env->GetStringUTFChars(v, nullptr);
            vs_set(ck, cv);
            env->ReleaseStringUTFChars(k, ck);
            env->ReleaseStringUTFChars(v, cv);
        }
        if (k) env->DeleteLocalRef(k);
        if (v) env->DeleteLocalRef(v);
    }
    vs_ensure_installed();
}

void updateDeviceProperties(JNIEnv *env, jclass clazz, jobjectArray keys, jobjectArray values) {
    ALOGD("merge device properties");
    if (keys == nullptr || values == nullptr) { vs_ensure_installed(); return; }
    jsize n = env->GetArrayLength(keys);
    jsize m = env->GetArrayLength(values);
    if (m < n) n = m;
    for (jsize i = 0; i < n; ++i) {
        auto k = (jstring) env->GetObjectArrayElement(keys, i);
        auto v = (jstring) env->GetObjectArrayElement(values, i);
        if (k && v) {
            const char *ck = env->GetStringUTFChars(k, nullptr);
            const char *cv = env->GetStringUTFChars(v, nullptr);
            vs_set(ck, cv);
            env->ReleaseStringUTFChars(k, ck);
            env->ReleaseStringUTFChars(v, cv);
        }
        if (k) env->DeleteLocalRef(k);
        if (v) env->DeleteLocalRef(v);
    }
    vs_ensure_installed();
}

static JNINativeMethod gMethods[] = {
        {"spoofDevice", "([Ljava/lang/String;[Ljava/lang/String;)V", (void *) spoofDevice},
        {"updateDeviceProperties", "([Ljava/lang/String;[Ljava/lang/String;)V", (void *) updateDeviceProperties},
        {"setProxy",    "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;)Z", (void *) setProxy},
        {"disableProxy","()V",                                       (void *) disableProxy},
        {"disableHiddenApi", "()Z",                               (void *) disableHiddenApi},
        {"disableResourceLoading", "()Z",                         (void *) disableResourceLoading},
        {"hideXposed", "()V",                                     (void *) hideXposed},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",                                     (void *) enableIO},
        {"init",       "(I)V",                                    (void *) init},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    registerMethod(env);
    return JNI_VERSION_1_6;
}
