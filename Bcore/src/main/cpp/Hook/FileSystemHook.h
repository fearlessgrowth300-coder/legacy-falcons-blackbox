#ifndef VIRTUALM_FILESYSTEMHOOK_H
#define VIRTUALM_FILESYSTEMHOOK_H

class FileSystemHook {
public:
    static void init(int apiLevel);
    static void setUptimeOffsetMs(long offsetMs);
};

#endif
