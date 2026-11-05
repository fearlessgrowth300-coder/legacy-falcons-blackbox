#include "FileSystemHook.h"
#include "IO.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"
#include "HookReentry.h"
#include "RuntimePathPolicy.h"

#include <cstdarg>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <ctime>

// Variadic open/openat only canonicalize /dev/ashmem* on API 36. A full path
// rewrite on those two crashed Instagram's native pool. __open_2, fopen, and
// the *at family still apply data-dir isolation. boot_id is never rewritten.

static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat64)(int dirfd, const char *pathname, int flags, ...) = nullptr;
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

static bool g_installed = false;
static int g_api_level = 0;
// Tag 2 is the file hooks. Separate from the syscall guard so a redirect
// that opens a socket does not look like a nested connect.
using FileGuard = HookReentry<2>;

static const char *rewrite(const char *path, bool allowGeneralRewrite) {
    if (path == nullptr) return path;
    const char *ashmem = canonical_ashmem_path(path);
    if (ashmem != path) return ashmem;
    if (path_is_kernel_boot_id(path) || path_is_runtime_device(path)) return path;
    if (!allowGeneralRewrite || FileGuard::depth() > 0) return path;
    FileGuard::Guard guard;
    const char *out = IO::redirectPath(path);
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
    const char *path = rewrite(pathname, g_api_level < 36);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_open ? orig_open(path, flags, mode) : -1;
}

static int my_open64(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char *path = rewrite(pathname, g_api_level < 36);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_open64 ? orig_open64(path, flags, mode) : -1;
}

static int my_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char *path = rewrite(pathname, g_api_level < 36);
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
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_open_2 ? orig_open_2(path, flags) : -1;
}

static int my_openat64(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char *path = rewrite(pathname, g_api_level < 36);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (pathname != nullptr && pathname[0] != '/' && path == pathname) {
        return orig_openat64 ? orig_openat64(dirfd, pathname, flags, mode) : -1;
    }
    return orig_openat64 ? orig_openat64(dirfd, path, flags, mode) : -1;
}

static int my_openat_2(int dirfd, const char *pathname, int flags) {
    const char *path = rewrite(pathname, true);
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
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_fopen ? orig_fopen(path, mode) : nullptr;
}

static int my_access(const char *pathname, int mode) {
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_access ? orig_access(path, mode) : -1;
}

static int my_stat(const char *pathname, struct stat *buf) {
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_stat ? orig_stat(path, buf) : -1;
}

static int my_lstat(const char *pathname, struct stat *buf) {
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_lstat ? orig_lstat(path, buf) : -1;
}

static int my_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    const char *path = rewrite(pathname, true);
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
    const char *path = rewrite(pathname, true);
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
    const char *path = rewrite(pathname, true);
    if (path == nullptr) {
        errno = ENOENT;
        return -1;
    }
    return orig_readlinkat ? orig_readlinkat(dirfd, path, buf, size) : -1;
}

static DIR *my_opendir(const char *name) {
    const char *path = rewrite(name, true);
    if (path == nullptr) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_opendir ? orig_opendir(path) : nullptr;
}

static int my_clock_gettime(clockid_t clock, struct timespec *ts) {
    int result = orig_clock_gettime ? orig_clock_gettime(clock, ts) : -1;
    // clock_may_be_shifted is false for every clock. A constant offset on
    // CLOCK_MONOTONIC disagrees with SurfaceFlinger's vsync timestamps.
    if (result == 0 && clock_may_be_shifted(static_cast<int>(clock))) {
        return result;
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
    // Kept so older callers still link. The value is not applied: shifting
    // CLOCK_MONOTONIC or CLOCK_BOOTTIME breaks vsync and elapsedRealtime.
    (void) offsetMs;
}

void FileSystemHook::init(int apiLevel) {
    if (g_installed) return;
    g_api_level = apiLevel;
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
    // Variadic open stays limited to ashmem canonicalization on API 36.
    // __open_2 / fopen / *at still apply the other redirects, never boot_id.
    void *open_sym = xdl_sym(handle, "open", nullptr);
    void *openat_sym = xdl_sym(handle, "openat", nullptr);
    hook_one(handle, "open", (void *) my_open, (void **) &orig_open);
    hook_one(handle, "openat", (void *) my_openat, (void **) &orig_openat);
    // On arm64 these are aliases of open/openat. Hooking the same address
    // again would replace the trampoline and recurse. 32-bit keeps them apart.
    void *open64_sym = xdl_sym(handle, "open64", nullptr);
    if (open64_sym != nullptr && open64_sym != open_sym) {
        hook_one(handle, "open64", (void *) my_open64, (void **) &orig_open64);
    }
    void *openat64_sym = xdl_sym(handle, "openat64", nullptr);
    if (openat64_sym != nullptr && openat64_sym != openat_sym) {
        hook_one(handle, "openat64", (void *) my_openat64, (void **) &orig_openat64);
    }
    g_installed = true;
    xdl_close(handle);
}
