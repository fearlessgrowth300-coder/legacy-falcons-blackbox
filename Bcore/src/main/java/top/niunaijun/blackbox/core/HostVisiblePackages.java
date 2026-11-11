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
}
