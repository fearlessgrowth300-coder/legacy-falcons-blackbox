package top.niunaijun.blackbox.core;

/**
 * Play Store's quick_launch process ClassCasts a process-global object and then
 * SIGSEGVs under the virtual loader (SM-S918U1, Android 16). It is not required
 * for Play sign-in. Do not start it.
 */
public final class PlayProcessPolicy {
    private PlayProcessPolicy() {}

    public static boolean refuseStart(String packageName, String processName) {
        if (!"com.android.vending".equals(packageName) || processName == null) return false;
        return processName.endsWith(":quick_launch") || processName.contains("quick_launch");
    }
}
