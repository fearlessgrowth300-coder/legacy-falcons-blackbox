



#include "FileSystemHook.h"
#include "IO.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <stdarg.h>
#include <cstring>
#include <cstdlib>
#include <errno.h>


static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat64)(int dirfd, const char *pathname, int flags, ...) = nullptr;

static bool should_block(const char *pathname) {
    return pathname != nullptr && (
            strstr(pathname, "resource-cache") ||
            strstr(pathname, "@idmap") ||
            strstr(pathname, ".frro") ||
            strstr(pathname, "systemui") ||
            strstr(pathname, "data@resource-cache@"));
}

static void release_redirect(const char *original, const char *redirected) {
    if (redirected != nullptr && redirected != original
            && strcmp(redirected, "/dev/null") != 0) {
        free((void *) redirected);
    }
}

static int call_open(int (*function)(const char *, int, ...),
                     const char *pathname, int flags, va_list args) {
    if (function == nullptr || pathname == nullptr) {
        errno = pathname == nullptr ? EFAULT : ENOSYS;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    if (redirected == nullptr) {
        errno = ENOMEM;
        return -1;
    }
    int result;
    if ((flags & O_CREAT) != 0
#ifdef O_TMPFILE
            || (flags & O_TMPFILE) == O_TMPFILE
#endif
    ) {
        mode_t mode = va_arg(args, mode_t);
        result = function(redirected, flags, mode);
    } else {
        result = function(redirected, flags);
    }
    release_redirect(pathname, redirected);
    return result;
}

static int call_openat(int (*function)(int, const char *, int, ...), int dirfd,
                       const char *pathname, int flags, va_list args) {
    if (function == nullptr || pathname == nullptr) {
        errno = pathname == nullptr ? EFAULT : ENOSYS;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    if (redirected == nullptr) {
        errno = ENOMEM;
        return -1;
    }
    int result;
    if ((flags & O_CREAT) != 0
#ifdef O_TMPFILE
            || (flags & O_TMPFILE) == O_TMPFILE
#endif
    ) {
        mode_t mode = va_arg(args, mode_t);
        result = function(dirfd, redirected, flags, mode);
    } else {
        result = function(dirfd, redirected, flags);
    }
    release_redirect(pathname, redirected);
    return result;
}


int new_open(const char *pathname, int flags, ...) {
    if (should_block(pathname)) {
        errno = ENOENT;
        return -1;
    }
    va_list args;
    va_start(args, flags);
    int result = call_open(orig_open, pathname, flags, args);
    va_end(args);
    return result;
}


int new_open64(const char *pathname, int flags, ...) {
    if (should_block(pathname)) {
        errno = ENOENT;
        return -1;
    }
    va_list args;
    va_start(args, flags);
    int result = call_open(orig_open64, pathname, flags, args);
    va_end(args);
    return result;
}

int new_openat(int dirfd, const char *pathname, int flags, ...) {
    if (should_block(pathname)) {
        errno = ENOENT;
        return -1;
    }
    va_list args;
    va_start(args, flags);
    int result = call_openat(orig_openat, dirfd, pathname, flags, args);
    va_end(args);
    return result;
}

int new_openat64(int dirfd, const char *pathname, int flags, ...) {
    if (should_block(pathname)) {
        errno = ENOENT;
        return -1;
    }
    va_list args;
    va_start(args, flags);
    int result = call_openat(orig_openat64, dirfd, pathname, flags, args);
    va_end(args);
    return result;
}

void FileSystemHook::init() {
    ALOGD("FileSystemHook: Initializing file system hooks");
    
    
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: Failed to open libc.so");
        return;
    }
    
    
    void *open_target = xdl_sym(handle, "open", nullptr);
    if (open_target && DobbyHook(open_target, (void *) new_open,
                                 (void **) &orig_open) == 0) {
        ALOGD("FileSystemHook: open redirect active");
    } else {
        ALOGE("FileSystemHook: Failed to hook open");
    }

    void *open64_target = xdl_sym(handle, "open64", nullptr);
    if (open64_target && open64_target != open_target
            && DobbyHook(open64_target, (void *) new_open64,
                         (void **) &orig_open64) == 0) {
        ALOGD("FileSystemHook: open64 redirect active");
    } else if (open64_target == open_target) {
        orig_open64 = orig_open;
    }

    void *openat_target = xdl_sym(handle, "openat", nullptr);
    if (openat_target && DobbyHook(openat_target, (void *) new_openat,
                                   (void **) &orig_openat) == 0) {
        ALOGD("FileSystemHook: openat redirect active");
    } else {
        ALOGE("FileSystemHook: Failed to hook openat");
    }

    void *openat64_target = xdl_sym(handle, "openat64", nullptr);
    if (openat64_target && openat64_target != openat_target
            && DobbyHook(openat64_target, (void *) new_openat64,
                         (void **) &orig_openat64) == 0) {
        ALOGD("FileSystemHook: openat64 redirect active");
    } else if (openat64_target == openat_target) {
        orig_openat64 = orig_openat;
    }

    xdl_close(handle);
}
