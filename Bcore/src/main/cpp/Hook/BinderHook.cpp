



#include "BinderHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"



HOOK_JNI(jint, getCallingUid, JNIEnv *env, jobject obj) {
    int orig = orig_getCallingUid(env, obj);
    return BoxCore::getCallingUid(env, orig);
}

HOOK_JNI(jint, getCallingPid, JNIEnv *env, jobject obj) {
    int orig = orig_getCallingPid ? orig_getCallingPid(env, obj) : 0;
    return BoxCore::getCallingPid(env, orig);
}

int BinderHook::callingPid(JNIEnv *env) {
    if (orig_getCallingPid == nullptr || env == nullptr) return 0;
    return orig_getCallingPid(env, nullptr);
}

void BinderHook::init(JNIEnv *env) {
    const char *clazz = "android/os/Binder";
    JniHook::HookJniFun(env, clazz, "getCallingPid", "()I", (void *) new_getCallingPid,
                        (void **) (&orig_getCallingPid), true);
    JniHook::HookJniFun(env, clazz, "getCallingUid", "()I", (void *) new_getCallingUid,
                        (void **) (&orig_getCallingUid), true);
}