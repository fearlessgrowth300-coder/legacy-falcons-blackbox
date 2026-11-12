package top.niunaijun.blackbox.utils.compat;

/**
 * Android 14+ bind flags that Chromium uses for its sandboxed child process.
 *
 * <p>{@code BIND_EXTERNAL_SERVICE} is the sign bit of a 32-bit int. Widening it to the
 * {@code long} flags argument added in Android 14 sign-extends it, and
 * {@code BIND_EXTERNAL_SERVICE_LONG} lives at bit 62. Either form makes system_server
 * reject a bind to BlackBox's ordinary (non-isolated) proxy service, which Chromium
 * reports as {@code ChildProcessConnection.start failed}.
 */
public final class BindServiceFlagsCompat {
    public static final int BIND_EXTERNAL_SERVICE = 0x80000000;
    public static final long BIND_EXTERNAL_SERVICE_LONG = 1L << 62;
    private static final long UNSIGNED_EXTERNAL = Integer.toUnsignedLong(BIND_EXTERNAL_SERVICE);

    private BindServiceFlagsCompat() {}

    public static Object stripExternalServiceFlag(Object flags) {
        if (flags instanceof Long) {
            return stripExternal((Long) flags);
        }
        if (flags instanceof Integer) {
            return ((Integer) flags) & ~BIND_EXTERNAL_SERVICE;
        }
        return flags;
    }

    public static long stripExternal(long flags) {
        boolean signExtendedExternal = (flags >>> 32) == 0xFFFFFFFFL
                && (flags & UNSIGNED_EXTERNAL) != 0;
        flags &= ~UNSIGNED_EXTERNAL;
        flags &= ~BIND_EXTERNAL_SERVICE_LONG;
        if (signExtendedExternal) {
            flags &= 0xFFFFFFFFL;
        }
        return flags;
    }

    /**
     * {@code bindServiceInstance} and {@code bindIsolatedService} carry the instance name
     * immediately after the flags. Plain {@code bindService} does not.
     */
    public static int instanceNameIndex(String methodName, int argCount) {
        if (methodName == null || argCount <= 6) return -1;
        if (methodName.contains("Instance") || methodName.contains("Isolated")) return 6;
        return -1;
    }
}
