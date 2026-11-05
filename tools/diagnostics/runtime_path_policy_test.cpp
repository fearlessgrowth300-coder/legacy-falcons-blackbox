#include "../../Bcore/src/main/cpp/RuntimePathPolicy.h"

#include <cstdio>
#include <cstdlib>
#include <ctime>

static int checks;

static void check(bool ok, const char *label) {
    checks++;
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", label);
        std::exit(1);
    }
}

int main() {
    check(path_is_ashmem_device("/dev/ashmem"), "classic ashmem node stays real");
    check(path_is_ashmem_device("/dev/ashmem0123456789abcdef"), "boot-id ashmem node stays real");
    check(!path_is_ashmem_device("/dev/binder"), "binder is not classified as ashmem");
    check(path_is_kernel_boot_id("/proc/sys/kernel/random/boot_id"), "boot id path is recognized");
    check(!path_is_kernel_boot_id("/proc/sys/kernel/random/uuid"), "uuid is not boot id");
    check(library_needs_real_boot_id("/apex/com.android.runtime/lib64/libcutils.so"),
            "libcutils reads the real boot id");
    check(library_needs_real_boot_id("/system/lib64/libbase.so"), "libbase reads the real boot id");
    check(library_needs_real_boot_id("libhwui.so"), "hwui reads the real boot id");
    check(library_needs_real_boot_id("/data/app/libsqlite.so"), "sqlite reads the real boot id");
    check(!library_needs_real_boot_id("/data/app/libinstagram.so"),
            "app code can still see a per-profile boot id");
    check(!library_needs_real_boot_id("libjavacore.so"),
            "Java file reads can still see a per-profile boot id");
    check(!clock_may_be_shifted(CLOCK_MONOTONIC), "CLOCK_MONOTONIC stays real");
    check(!clock_may_be_shifted(CLOCK_MONOTONIC_RAW), "CLOCK_MONOTONIC_RAW stays real");
    check(!clock_may_be_shifted(CLOCK_MONOTONIC_COARSE), "CLOCK_MONOTONIC_COARSE stays real");
    check(!clock_may_be_shifted(CLOCK_BOOTTIME), "CLOCK_BOOTTIME stays real");
    check(!clock_may_be_shifted(CLOCK_REALTIME), "CLOCK_REALTIME stays real");
    std::printf("PASS: %d runtime path checks\n", checks);
    return 0;
}
