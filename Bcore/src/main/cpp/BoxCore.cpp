



#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include "RuntimePathPolicy.h"
#include "Hook/BinderHook.h"
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
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

static int g_host_uid = 0;
static int g_virtual_uid = 0;
static __thread int g_self_provider_call = 0;
static char g_uid_dir[512];
static void publish_self_uid();

void BoxCore::setCallerIdentity(int hostUid, int virtualUid) {
    g_host_uid = hostUid;
    g_virtual_uid = virtualUid;
    publish_self_uid();
}

void BoxCore::setCallerUidDirectory(const char *path) {
    if (path == nullptr) {
        g_uid_dir[0] = '\0';
        return;
    }
    std::snprintf(g_uid_dir, sizeof(g_uid_dir), "%s", path);
    if (mkdir(g_uid_dir, 0755) != 0 && errno != EEXIST) {
        ALOGE("CallerUid mkdir failed dir=%s errno=%d", g_uid_dir, errno);
    }
    ALOGE("CallerUid directory=%s", g_uid_dir);
    publish_self_uid();
}

static int (*g_ipc_calling_pid)(void *) = nullptr;
static void *(*g_ipc_self)() = nullptr;
static bool g_ipc_resolved = false;

static void resolve_ipc_calling_pid() {
    if (g_ipc_resolved) return;
    g_ipc_resolved = true;
    void *lib = dlopen("libbinder.so", RTLD_NOW);
    if (lib == nullptr) {
        ALOGE("CallerUid libbinder unavailable");
        return;
    }
    g_ipc_self = reinterpret_cast<void *(*)()>(
            dlsym(lib, "_ZN7android14IPCThreadState4selfEv"));
    g_ipc_calling_pid = reinterpret_cast<int (*)(void *)>(
            dlsym(lib, "_ZNK7android14IPCThreadState13getCallingPidEv"));
    if (g_ipc_self == nullptr || g_ipc_calling_pid == nullptr) {
        ALOGE("CallerUid IPCThreadState symbols missing");
    }
}

static int ipc_calling_pid() {
    resolve_ipc_calling_pid();
    if (g_ipc_self == nullptr || g_ipc_calling_pid == nullptr) return 0;
    void *self = g_ipc_self();
    if (self == nullptr) return 0;
    return g_ipc_calling_pid(self);
}

// libc open() is redirected into the guest data dir. These files live in the
// host blackbox directory, so the read must be the raw openat syscall.
static int raw_openat(const char *path, int flags, int mode) {
    return (int) syscall(SYS_openat, AT_FDCWD, path, flags, mode);
}

static int lookup_published_uid(int pid, int *err_out) {
    if (err_out != nullptr) *err_out = 0;
    if (g_uid_dir[0] == '\0' || pid <= 0) return -1;
    char path[640];
    int wrote = std::snprintf(path, sizeof(path), "%s/%d", g_uid_dir, pid);
    if (wrote <= 0 || wrote >= (int) sizeof(path)) return -1;
    int fd = raw_openat(path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) {
        if (err_out != nullptr) *err_out = errno;
        return -1;
    }
    char buf[16];
    ssize_t got = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (got <= 0) return -1;
    buf[got] = '\0';
    int uid = std::atoi(buf);
    return uid >= 10000 ? uid : -1;
}

static void publish_self_uid() {
    if (g_uid_dir[0] == '\0' || g_virtual_uid < 10000) return;
    char path[640];
    int wrote = std::snprintf(path, sizeof(path), "%s/%d", g_uid_dir, getpid());
    if (wrote <= 0 || wrote >= (int) sizeof(path)) return;
    int fd = raw_openat(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        ALOGE("CallerUid publish failed pid=%d uid=%d path=%s errno=%d",
              getpid(), g_virtual_uid, path, errno);
        return;
    }
    char buf[16];
    int n = std::snprintf(buf, sizeof(buf), "%d", g_virtual_uid);
    if (n > 0) write(fd, buf, (size_t) n);
    close(fd);
    ALOGE("CallerUid publish pid=%d uid=%d path=%s", getpid(), g_virtual_uid, path);
}

static void log_calling_uid(int orig, int jni_pid, int raw_pid, int looked,
                            int look_errno, int returned) {
    // The GMS provider transaction is the one that must be visible. Log each
    // distinct sender once, and every time we would answer with our own uid
    // for somebody else's process.
    static int seen_pids[16];
    static int seen_count = 0;
    static int fallback_logs = 0;
    bool cross = raw_pid > 0 && raw_pid != getpid();
    bool fallback = cross && returned == g_virtual_uid;
    bool known = false;
    int key = raw_pid != 0 ? raw_pid : jni_pid;
    for (int i = 0; i < seen_count; i++) {
        if (seen_pids[i] == key) {
            known = true;
            break;
        }
    }
    if (!known && seen_count < 16) seen_pids[seen_count++] = key;
    if (known && !fallback) return;
    if (fallback && fallback_logs++ > 40) return;
    ALOGE("CallerUid hook proc=%d own=%d orig=%d jniPid=%d rawPid=%d looked=%d errno=%d dir=%s return=%d",
          getpid(), g_virtual_uid, orig, jni_pid, raw_pid, looked, look_errno,
          g_uid_dir[0] == '\0' ? "(empty)" : g_uid_dir, returned);
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    // Android 16 crashes if this hook calls back into Java. Binder.getCallingUid can run
    // while ART is resolving a method, and the upcall through NativeCore.getCallingUid
    // SIGSEGVs the guest (seen on SM-S918U1, SDK 36, process :p2). Resolve locally.
    // Returning this process's own virtual uid for every host-uid caller makes a
    // Play Store call (uid 10004) fail inside GMS (uid 10005). Read the sender pid.
    if (VMEnv.api_level >= 36) {
        if (g_host_uid <= 0 || orig != g_host_uid) return orig;
        // Same-app provider calls cleared the binder identity. Keep the process uid
        // so the guest's own provider sees Binder.getCallingUid() == Process.myUid().
        if (g_self_provider_call > 0) return orig;
        int jni_pid = BinderHook::callingPid(env);
        int raw_pid = ipc_calling_pid();
        int pid = raw_pid > 0 ? raw_pid : jni_pid;
        int looked = -1;
        int look_errno = 0;
        int returned = g_virtual_uid >= 10000 ? g_virtual_uid : orig;
        if (pid > 0 && pid != getpid()) {
            looked = lookup_published_uid(pid, &look_errno);
            if (looked >= 10000) returned = looked;
        }
        log_calling_uid(orig, jni_pid, raw_pid, looked, look_errno, returned);
        return returned;
    }
    env = ensureEnvCreated();
    if (env == nullptr || env->ExceptionCheck()) return orig;
    int result = env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return orig;
    }
    return result;
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
    FileSystemHook::init(VMEnv.api_level);
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

void setBootIdSpoof(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void) env;
    (void) clazz;
    set_native_boot_id_spoof(enabled == JNI_TRUE);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    ALOGD("set addIORule");
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void setPathIsolation(JNIEnv *env, jclass clazz, jstring root, jstring ext, jint user) {
    const char *rootChars = root ? env->GetStringUTFChars(root, nullptr) : nullptr;
    const char *extChars = ext ? env->GetStringUTFChars(ext, nullptr) : nullptr;
    IO::setIsolation(rootChars, extChars, user);
    if (root && rootChars) env->ReleaseStringUTFChars(root, rootChars);
    if (ext && extChars) env->ReleaseStringUTFChars(ext, extChars);
}

void setUptimeOffsetMs(JNIEnv *env, jclass clazz, jlong offset) {
    FileSystemHook::setUptimeOffsetMs(offset < 0 ? 0 : (long) offset);
}

void enterSelfProviderCall(JNIEnv *env, jclass clazz) {
    g_self_provider_call++;
}

void exitSelfProviderCall(JNIEnv *env, jclass clazz) {
    if (g_self_provider_call > 0) g_self_provider_call--;
}

void setCallerIdentity(JNIEnv *env, jclass clazz, jint hostUid, jint virtualUid) {
    (void) env;
    (void) clazz;
    BoxCore::setCallerIdentity(hostUid, virtualUid);
}

void setCallerUidDirectory(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *chars = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    BoxCore::setCallerUidDirectory(chars);
    if (path && chars) env->ReleaseStringUTFChars(path, chars);
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
        {"setBootIdSpoof", "(Z)V", (void *) setBootIdSpoof},
        {"setPathIsolation", "(Ljava/lang/String;Ljava/lang/String;I)V", (void *) setPathIsolation},
        {"setUptimeOffsetMs", "(J)V", (void *) setUptimeOffsetMs},
        {"setCallerIdentity", "(II)V", (void *) setCallerIdentity},
        {"nativeEnterSelfProviderCall", "()V", (void *) enterSelfProviderCall},
        {"nativeExitSelfProviderCall", "()V", (void *) exitSelfProviderCall},
        {"setCallerUidDirectory", "(Ljava/lang/String;)V", (void *) setCallerUidDirectory},
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
