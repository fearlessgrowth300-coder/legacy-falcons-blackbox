#include "FileSystemHook.h"
#include "IO.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"

#include <cstdarg>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <ctime>

// open/openat are variadic and crashed Instagram's native pool on Android 16.
// Those two stay off on API 36+. The non-variadic calls below still rewrite paths.

static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_open_2)(const char *pathname, int flags) = nullptr;
static int (*orig_openat_2)(int dirfd, const char *pathname, int flags) = nullptr;
static FILE *(*orig_fopen)(const char *pathname, const char *mode) = nullptr;
static int (*orig_access)(const char *pathname, int mode) = nullptr;
static int (*orig_stat)(const char *pathname, struct stat *buf) = nullptr;
static int (*orig_lstat)(const char *pathname, struct stat *buf) = nullptr;
static int (*orig_faccessat)(int dirfd, const char *pathname, int mode, int flags) = nullptr;
static int (*orig_fstatat)(int dirfd, const char *pathname, struct stat *buf, int flags) = nullptr;
static ssize_t (*orig_readlinkat)(int dirfd, const char *pathname, char *buf, size_t size) = nullptr;
static DIR *(*orig_opendir)(const char *name) = nullptr;
static int (*orig_clock_gettime)(clockid_t clock, struct timespec *ts) = nullptr;

static long g_uptime_offset_ms = 0;
static bool g_installed = false;
static thread_local int g_in_hook = 0;

static const char *rewrite(const char *path) {
    if (g_in_hook || path == nullptr) return path;
    g_in_hook++;
    const char *out = IO::redirectPath(path);
    g_in_hook--;
    return out == nullptr ? path : out;
}

static int my_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_open ? orig_open(path, flags, mode) : -1;
}

static int my_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (pathname != nullptr && pathname[0] != '/' && path == pathname) {
        return orig_openat ? orig_openat(dirfd, pathname, flags, mode) : -1;
    }
    return orig_openat ? orig_openat(dirfd, path, flags, mode) : -1;
}

static int my_open_2(const char *pathname, int flags) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_open_2 ? orig_open_2(path, flags) : -1;
}

static int my_openat_2(int dirfd, const char *pathname, int flags) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (pathname != nullptr && pathname[0] != '/' && path == pathname) {
        return orig_openat_2 ? orig_openat_2(dirfd, pathname, flags) : -1;
    }
    return orig_openat_2 ? orig_openat_2(dirfd, path, flags) : -1;
}

static FILE *my_fopen(const char *pathname, const char *mode) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_fopen ? orig_fopen(path, mode) : nullptr;
}

static int my_access(const char *pathname, int mode) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_access ? orig_access(path, mode) : -1;
}

static int my_stat(const char *pathname, struct stat *buf) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_stat ? orig_stat(path, buf) : -1;
}

static int my_lstat(const char *pathname, struct stat *buf) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_lstat ? orig_lstat(path, buf) : -1;
}

static int my_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (pathname != nullptr && pathname[0] != '/' && path == pathname) {
        return orig_faccessat ? orig_faccessat(dirfd, pathname, mode, flags) : -1;
    }
    return orig_faccessat ? orig_faccessat(dirfd, path, mode, flags) : -1;
}

static int my_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (pathname != nullptr && pathname[0] != '/' && path == pathname) {
        return orig_fstatat ? orig_fstatat(dirfd, pathname, buf, flags) : -1;
    }
    return orig_fstatat ? orig_fstatat(dirfd, path, buf, flags) : -1;
}

static ssize_t my_readlinkat(int dirfd, const char *pathname, char *buf, size_t size) {
    const char *path = rewrite(pathname);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_readlinkat ? orig_readlinkat(dirfd, path, buf, size) : -1;
}

static DIR *my_opendir(const char *name) {
    const char *path = rewrite(name);
    if (path == nullptr) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_opendir ? orig_opendir(path) : nullptr;
}

static void add_offset(struct timespec *ts) {
    if (ts == nullptr || g_uptime_offset_ms == 0) return;
    long sec = g_uptime_offset_ms / 1000;
    long nsec = (g_uptime_offset_ms % 1000) * 1000000L;
    ts->tv_sec += sec;
    ts->tv_nsec += nsec;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec += 1;
        ts->tv_nsec -= 1000000000L;
    }
}

static int my_clock_gettime(clockid_t clock, struct timespec *ts) {
    int result = orig_clock_gettime ? orig_clock_gettime(clock, ts) : -1;
    if (result == 0 && (clock == CLOCK_MONOTONIC || clock == CLOCK_MONOTONIC_RAW
            || clock == CLOCK_MONOTONIC_COARSE || clock == CLOCK_BOOTTIME)) {
        add_offset(ts);
    }
    return result;
}

static void hook_one(void *handle, const char *name, void *replacement, void **original) {
    void *target = xdl_sym(handle, name, nullptr);
    if (target == nullptr) {
        ALOGD("FileSystemHook: %s missing", name);
        return;
    }
    if (DobbyHook(target, replacement, original) != 0) {
        ALOGD("FileSystemHook: %s hook failed; continuing", name);
        *original = nullptr;
    }
}

void FileSystemHook::setUptimeOffsetMs(long offsetMs) {
    if (offsetMs < 0) offsetMs = 0;
    g_uptime_offset_ms = offsetMs;
}

void FileSystemHook::init(int apiLevel) {
    if (g_installed) return;
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: libc unavailable; path hooks degraded");
        return;
    }
    hook_one(handle, "__open_2", (void *) my_open_2, (void **) &orig_open_2);
    hook_one(handle, "__openat_2", (void *) my_openat_2, (void **) &orig_openat_2);
    hook_one(handle, "fopen", (void *) my_fopen, (void **) &orig_fopen);
    hook_one(handle, "access", (void *) my_access, (void **) &orig_access);
    hook_one(handle, "stat", (void *) my_stat, (void **) &orig_stat);
    hook_one(handle, "lstat", (void *) my_lstat, (void **) &orig_lstat);
    hook_one(handle, "faccessat", (void *) my_faccessat, (void **) &orig_faccessat);
    hook_one(handle, "fstatat", (void *) my_fstatat, (void **) &orig_fstatat);
    hook_one(handle, "readlinkat", (void *) my_readlinkat, (void **) &orig_readlinkat);
    hook_one(handle, "opendir", (void *) my_opendir, (void **) &orig_opendir);
    hook_one(handle, "clock_gettime", (void *) my_clock_gettime, (void **) &orig_clock_gettime);
    if (apiLevel < 36) {
        hook_one(handle, "open", (void *) my_open, (void **) &orig_open);
        hook_one(handle, "openat", (void *) my_openat, (void **) &orig_openat);
    } else {
        ALOGD("FileSystemHook: skipping variadic open/openat on API %d", apiLevel);
    }
    g_installed = true;
    xdl_close(handle);
}
