#include "../../Bcore/src/main/cpp/RuntimePathPolicy.h"

#include <cstdio>
#include <cstdlib>

static int checks;

static void check(bool ok, const char *label) {
    checks++;
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", label);
        std::exit(1);
    }
}

int main() {
    check(strcmp(canonical_ashmem_path("/dev/ashmem"), "/dev/ashmem") == 0,
            "the real ashmem node stays");
    const char *spoofed = "/dev/ashmem4bfe6159-feb0-428f-944b-a08a93425fa4";
    check(strcmp(canonical_ashmem_path(spoofed), "/dev/ashmem") == 0,
            "a boot-id ashmem name opens /dev/ashmem");
    check(strcmp(canonical_ashmem_path("/dev/binder"), "/dev/binder") == 0,
            "binder is not rewritten to ashmem");
    check(path_is_kernel_boot_id("/proc/sys/kernel/random/boot_id"), "boot id path is recognized");
    check(native_may_redirect_boot_id(), "guest boot_id stays on the per-user file");
    set_native_boot_id_spoof(false);
    check(!native_may_redirect_boot_id(), "a failed ashmem probe can disable boot_id spoof");
    set_native_boot_id_spoof(true);
    check(path_is_runtime_device("/dev/binder"), "binder is protected");
    check(path_is_runtime_device("/dev/hwbinder"), "hwbinder is protected");
    check(path_is_runtime_device("/dev/__properties__"), "properties device is protected");
    check(path_is_runtime_device("/dev/kgsl-3d0"), "kgsl is protected");
    check(path_is_runtime_device("/dev/mali0"), "mali is protected");
    check(path_is_runtime_device("/dev/dri/card0"), "dri is protected");
    check(path_is_runtime_device("/proc/self/maps"), "maps is protected");
    check(path_is_runtime_device("/proc/self/fd/4"), "fds are protected");
    check(!path_is_runtime_device("/proc/self/cmdline"), "cmdline can still be virtualized");
    check(syscall_is_openat_family(56, 0), "arm64 openat is rewritten for ashmem");
    check(syscall_is_openat_family(48, 0), "arm64 faccessat is rewritten for ashmem");
    check(syscall_is_openat_family(79, 0), "arm64 newfstatat is rewritten for ashmem");
    check(!syscall_is_openat_family(98, 0), "arm64 futex is not an open path");
    check(!syscall_is_openat_family(279, 0), "arm64 memfd_create is not rewritten");
    check(!clock_may_be_shifted(1), "monotonic clock stays real");
    std::printf("PASS: %d runtime path checks\n", checks);
    return 0;
}
