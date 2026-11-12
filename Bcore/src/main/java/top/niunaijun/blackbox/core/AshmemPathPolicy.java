package top.niunaijun.blackbox.core;

/**
 * Native open paths must not invent an ashmem device name. Java may still
 * substitute {@code /proc/sys/kernel/random/boot_id} for guest file APIs.
 */
public final class AshmemPathPolicy {
    public static final String ASHMEM = "/dev/ashmem";
    public static final String BOOT_ID = "/proc/sys/kernel/random/boot_id";

    private AshmemPathPolicy() {}

    public static String canonicalAshmem(String path) {
        if (path == null || !path.startsWith(ASHMEM)) return path;
        if (ASHMEM.equals(path)) return path;
        return ASHMEM;
    }

    public static boolean isKernelBootId(String path) {
        return BOOT_ID.equals(path);
    }

    /** Native hooks never rewrite boot_id. Java file APIs may. */
    public static boolean nativeMayRedirectBootId() {
        return false;
    }

    public static boolean blocksNativeRedirect(String path) {
        if (path == null) return false;
        return path.startsWith("/dev/binder")
                || path.startsWith("/dev/hwbinder")
                || path.startsWith("/dev/vndbinder")
                || path.startsWith("/dev/__properties__")
                || path.startsWith("/dev/kgsl")
                || path.startsWith("/dev/mali")
                || path.startsWith("/dev/dri")
                || path.startsWith("/proc/self/maps")
                || path.startsWith("/proc/self/smaps")
                || path.startsWith("/proc/self/fd")
                || path.startsWith("/proc/self/mem")
                || path.startsWith("/proc/self/status")
                || path.startsWith("/proc/self/mount")
                || path.startsWith("/proc/self/attr")
                || path.startsWith("/proc/self/cgroup")
                || path.startsWith("/proc/self/task")
                || path.startsWith("/proc/self/exe")
                || path.startsWith("/proc/self/cwd")
                || path.startsWith("/proc/self/root")
                || path.startsWith("/proc/self/ns/");
    }
}
