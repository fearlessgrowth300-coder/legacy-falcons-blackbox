package top.niunaijun.blackbox.utils;

/**
 * Stops SimpleCrashFix from keeping a process alive across the same fatal activity launch.
 */
public final class CrashLoopPolicy {
    /** Identical swallowed failures allowed before the process is allowed to die. */
    public static final int MAX_SWALLOWS = 2;

    private CrashLoopPolicy() {}

    public static boolean fatalActivityLaunch(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message == null) continue;
            if (message.contains("Unable to start activity")
                    || message.contains("Unable to resume activity")
                    || message.contains("Unable to pause activity")
                    || message.contains("Unable to stop activity")
                    || message.contains("Unable to destroy activity")
                    || message.contains("AppContext.set has not been invoked")) {
                return true;
            }
        }
        return false;
    }

    public static String key(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) root = root.getCause();
        if (root == null) return "unknown";
        String message = root.getMessage();
        String type = root.getClass().getName();
        return message == null ? type : type + ":" + message;
    }

    /** A fatal activity launch is never swallowed. Other failures stop after the cap. */
    public static boolean allowSwallow(Throwable error, boolean swallowable, int seen) {
        if (fatalActivityLaunch(error)) return false;
        if (!swallowable) return false;
        return seen <= MAX_SWALLOWS;
    }
}
