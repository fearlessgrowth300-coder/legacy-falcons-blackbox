#ifndef BLACKBOX_AUDIOTRACKHOOK_H
#define BLACKBOX_AUDIOTRACKHOOK_H

#include <jni.h>

class AudioTrackHook {
public:
    static void init(JNIEnv *env);
};

#endif
