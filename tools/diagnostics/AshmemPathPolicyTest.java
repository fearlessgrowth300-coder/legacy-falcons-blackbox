import top.niunaijun.blackbox.core.AshmemPathPolicy;

public final class AshmemPathPolicyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check("/dev/ashmem".equals(AshmemPathPolicy.canonicalAshmem("/dev/ashmem")),
                "the real ashmem node stays");
        check("/dev/ashmem".equals(AshmemPathPolicy.canonicalAshmem(
                        "/dev/ashmem4bfe6159-feb0-428f-944b-a08a93425fa4")),
                "a boot-id ashmem name is rewritten to /dev/ashmem");
        check("/dev/ashmem".equals(AshmemPathPolicy.canonicalAshmem("/dev/ashmem-spoofed")),
                "a spoofed ashmem name is rewritten to /dev/ashmem");
        check("/dev/binder".equals(AshmemPathPolicy.canonicalAshmem("/dev/binder")),
                "binder is not treated as ashmem");
        check(!AshmemPathPolicy.nativeMayRedirectBootId(),
                "native hooks do not spoof boot_id");
        check(AshmemPathPolicy.isKernelBootId("/proc/sys/kernel/random/boot_id"),
                "boot id path is recognized");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/binder"), "binder is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/hwbinder"), "hwbinder is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/__properties__"),
                "properties device is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/kgsl-3d0"), "kgsl is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/mali0"), "mali is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/dev/dri/card0"), "dri is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/proc/self/maps"), "maps is not redirected");
        check(AshmemPathPolicy.blocksNativeRedirect("/proc/self/fd/3"), "fds are not redirected");
        check(!AshmemPathPolicy.blocksNativeRedirect("/proc/self/cmdline"),
                "cmdline remains available for the virtual process name");
        System.out.println("PASS: " + checks + " ashmem path checks");
    }
}
