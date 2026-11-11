package top.niunaijun.blackbox.core;

/**
 * Packages that stay on the phone. A clone that did not install its own copy
 * may read the real package so Play services can answer instead of timing out.
 * This does not invent a certificate or an attestation result.
 */
public final class HostVisiblePackages {
    private HostVisiblePackages() {}

    public static boolean isHostService(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.android.vending".equals(packageName);
    }

    public static boolean intentTargetsHostService(String packageName, String componentPackage, String action) {
        if (isHostService(packageName) || isHostService(componentPackage)) return true;
        if (action == null) return false;
        return action.startsWith("com.google.android.gms")
                || action.startsWith("com.google.android.c2dm");
    }

    /**
     * Platform SMS/telephony broadcasts. A clone does not ship a messaging app, so
     * the phone's own receivers are the normal answer. An empty list makes the
     * caller wait for a default SMS package that never appears.
     */
    public static boolean useHostReceivers(String action) {
        if (action == null) return false;
        return action.startsWith("android.provider.Telephony.")
                || "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED".equals(action)
                || "android.provider.action.EXTERNAL_PROVIDER_CHANGE".equals(action);
    }
}
