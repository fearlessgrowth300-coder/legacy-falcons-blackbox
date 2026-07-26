#include "FileSystemHook.h"
#include "Log.h"
#include "xdl.h"

#include <fcntl.h>

// Kept as discovered entry points for future, separately validated interception work.
// Do not patch these process-global variadic functions in production guest processes:
// Instagram's native SWPool reproducibly crashed on Android 16 when they were hooked.
static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat64)(int dirfd, const char *pathname, int flags, ...) = nullptr;

void FileSystemHook::init() {
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: Failed to open libc.so");
        return;
    }

    orig_open = (int (*)(const char *, int, ...)) xdl_sym(handle, "open", nullptr);
    orig_open64 = (int (*)(const char *, int, ...)) xdl_sym(handle, "open64", nullptr);
    orig_openat = (int (*)(int, const char *, int, ...)) xdl_sym(handle, "openat", nullptr);
    orig_openat64 = (int (*)(int, const char *, int, ...)) xdl_sym(handle, "openat64", nullptr);
    ALOGD("FileSystemHook: invasive libc redirects disabled");
    xdl_close(handle);
}
