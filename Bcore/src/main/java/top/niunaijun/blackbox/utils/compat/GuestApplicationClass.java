package top.niunaijun.blackbox.utils.compat;

/**
 * Chooses the guest Application class Android 16 would select for a process.
 *
 * <p>API 36+ looks up a per-process class with {@code Process.myProcessName()} and otherwise
 * uses the manifest {@code android:name}. A stub process name misses the per-process entry
 * and leaves Facebook on {@code android.app.Application}, which never calls AppContext.set.
 */
public final class GuestApplicationClass {
    public static final String DEFAULT = "android.app.Application";

    private GuestApplicationClass() {}

    public static String expected(String manifestClass, String processSpecificClass) {
        if (processSpecificClass != null && !processSpecificClass.isEmpty()) {
            return processSpecificClass;
        }
        if (manifestClass != null && !manifestClass.isEmpty()) {
            return manifestClass;
        }
        return DEFAULT;
    }

    /** The framework instance is not the class the manifest named for this process. */
    public static boolean needsReplacement(String actualClass, String expectedClass) {
        return expectedClass != null && !DEFAULT.equals(expectedClass)
                && !expectedClass.equals(actualClass);
    }
}
