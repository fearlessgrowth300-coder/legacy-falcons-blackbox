#ifndef BLACKBOX_AUDIOTRACKHOOK_H
#define BLACKBOX_AUDIOTRACKHOOK_H

#include <jni.h>

class AudioTrackHook {
public:
    static void init(JNIEnv *env);
    static const char *hook(JNIEnv *env, jobject method, const char *descriptor,
                            int parcelSlot, int stringSlot, int sessionSlot);
};

#endif
