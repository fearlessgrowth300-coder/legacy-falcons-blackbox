#pragma once

#include <cstring>

// libcutils builds "/dev/ashmem" + the boot id it just read. Both names are
// the same char device (10, 125) on this kernel. Any other suffix is a
// spoofed id and does not exist, so open it as the plain node instead.

inline bool path_is_ashmem_device(const char *path) {
    return path != nullptr && strncmp(path, "/dev/ashmem", 11) == 0;
}

inline const char *canonical_ashmem_path(const char *path) {
    if (!path_is_ashmem_device(path)) return path;
    if (path[11] == '\0') return path;
    return "/dev/ashmem";
}

inline bool path_is_kernel_boot_id(const char *path) {
    return path != nullptr && strcmp(path, "/proc/sys/kernel/random/boot_id") == 0;
}

// System libraries open these themselves. A redirect here breaks the runtime.
inline bool path_is_runtime_device(const char *path) {
    if (path == nullptr) return false;
    return strncmp(path, "/dev/binder", 11) == 0
            || strncmp(path, "/dev/hwbinder", 13) == 0
            || strncmp(path, "/dev/vndbinder", 14) == 0
            || strncmp(path, "/dev/__properties__", 18) == 0
            || strncmp(path, "/dev/kgsl", 9) == 0
            || strncmp(path, "/dev/mali", 9) == 0
            || strncmp(path, "/dev/dri", 8) == 0
            || strncmp(path, "/proc/self/maps", 15) == 0
            || strncmp(path, "/proc/self/smaps", 16) == 0
            || strncmp(path, "/proc/self/fd", 13) == 0
            || strncmp(path, "/proc/self/mem", 14) == 0
            || strncmp(path, "/proc/self/status", 17) == 0
            || strncmp(path, "/proc/self/mount", 16) == 0
            || strncmp(path, "/proc/self/attr", 15) == 0
            || strncmp(path, "/proc/self/cgroup", 17) == 0
            || strncmp(path, "/proc/self/task", 15) == 0
            || strncmp(path, "/proc/self/exe", 14) == 0
            || strncmp(path, "/proc/self/cwd", 14) == 0
            || strncmp(path, "/proc/self/root", 15) == 0
            || strncmp(path, "/proc/self/ns/", 14) == 0;
}

// Native hooks never substitute boot_id. The immediate caller of open() is
// libc, so a library check cannot see libcutils.
inline bool native_may_redirect_boot_id() {
    return false;
}

inline bool clock_may_be_shifted(int clock_id) {
    (void) clock_id;
    return false;
}

// openat / faccessat / newfstatat carry a path in the second argument.
inline bool syscall_is_openat_family(long number, int arch) {
    if (arch == 0) return number == 56 || number == 48 || number == 79;
    if (arch == 1) return number == 322 || number == 334 || number == 327;
    if (arch == 2) return number == 257 || number == 269 || number == 262;
    return false;
}
