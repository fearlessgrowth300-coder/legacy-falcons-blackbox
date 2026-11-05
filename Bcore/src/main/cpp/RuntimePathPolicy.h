#pragma once

#include <cstring>

// libcutils ashmem_create_region reads /proc/sys/kernel/random/boot_id and
// opens /dev/ashmem plus that id. A redirected boot id makes the open ENOENT,
// so CursorWindow and Bitmap ashmem fail inside the clone. App reads of
// boot_id may still be redirected. These runtime libraries must see the real file.

inline bool path_is_ashmem_device(const char *path) {
    return path != nullptr && strncmp(path, "/dev/ashmem", 11) == 0;
}

inline bool path_is_kernel_boot_id(const char *path) {
    return path != nullptr && strcmp(path, "/proc/sys/kernel/random/boot_id") == 0;
}

inline bool library_needs_real_boot_id(const char *mapped_object) {
    if (mapped_object == nullptr) return false;
    return strstr(mapped_object, "libcutils.so") != nullptr
            || strstr(mapped_object, "libbase.so") != nullptr
            || strstr(mapped_object, "libsqlite.so") != nullptr
            || strstr(mapped_object, "libhwui.so") != nullptr
            || strstr(mapped_object, "libmeminfo.so") != nullptr;
}

// Choreographer compares System.nanoTime (CLOCK_MONOTONIC in the guest) with
// vsync timestamps from SurfaceFlinger (the real monotonic clock). Shifting
// either clock makes every frame look hundreds of seconds late.
inline bool clock_may_be_shifted(int clock_id) {
    (void) clock_id;
    return false;
}
