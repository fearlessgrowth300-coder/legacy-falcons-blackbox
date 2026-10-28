package top.niunaijun.blackbox.core;

/**
 * Pine 0.3.0 cannot be used on two runtimes this engine still has to launch guests on.
 *
 * <p>Its ARM64 object bridge native-crashes under Android 16 ART. Separately, the
 * packaged {@code libpine.so} is aligned to 4 KB pages, so loading it on a 16 KB page
 * device (Android 15 and later, and any earlier kernel with that page size) fails
 * inside the loader and kills the process. Callers skip Pine in both cases and use a
 * Binder or provider fallback instead.
 */
public final class PineRuntime {
    public static final long PAGE_16K = 16384L;

    private PineRuntime() {}

    public static boolean hooksCrashThisRuntime(int sdkInt, long pageSize) {
        if (pageSize >= PAGE_16K) return true;
        return sdkInt >= 36;
    }
}
