package top.niunaijun.blackbox.core;

/**
 * Which phone packages a clone may see for SMS/telephony broadcast queries.
 * A cloned app and any other user-installed app stay hidden. The visibility
 * guard result is passed in so this check cannot grow its own allowlist.
 */
public final class SmsReceiverVisibility {
    private SmsReceiverVisibility() {}

    public static boolean isPlatformTelephony(String packageName) {
        return "com.android.phone".equals(packageName)
                || "com.android.mms".equals(packageName)
                || "com.android.messaging".equals(packageName)
                || "com.android.providers.telephony".equals(packageName)
                || "com.android.server.telecom".equals(packageName);
    }

    /** {@code guardAllows} is the existing package-visibility guard, or platform telephony. */
    public static boolean guardAllows(String packageName, boolean openPackage) {
        return openPackage || isPlatformTelephony(packageName);
    }

    public static boolean visible(String packageName, boolean guardAllows,
                                  boolean defaultSmsHolder, boolean installedAsClone) {
        if (packageName == null || packageName.isEmpty() || installedAsClone) return false;
        return defaultSmsHolder || guardAllows;
    }
}
