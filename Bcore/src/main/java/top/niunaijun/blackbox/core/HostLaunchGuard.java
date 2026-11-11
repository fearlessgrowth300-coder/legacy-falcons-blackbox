package top.niunaijun.blackbox.core;

/**
 * An intent aimed at a package installed in the guest must not be handed to the
 * phone's ActivityManager. That starts the real app (background, no activity)
 * and the guest never receives the result.
 */
public final class HostLaunchGuard {
    private HostLaunchGuard() {}

    public static String targetPackage(String intentPackage, String componentPackage) {
        if (componentPackage != null && !componentPackage.isEmpty()) return componentPackage;
        if (intentPackage != null && !intentPackage.isEmpty()) return intentPackage;
        return null;
    }

    public static boolean blockHostCopy(String targetPackage, String hostPackage, boolean installedInGuest) {
        if (targetPackage == null || targetPackage.isEmpty()) return false;
        if (hostPackage != null && hostPackage.equals(targetPackage)) return false;
        return installedInGuest;
    }
}
