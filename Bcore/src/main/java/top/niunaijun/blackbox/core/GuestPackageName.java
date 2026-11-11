package top.niunaijun.blackbox.core;

/**
 * Package name guest code should see on its own context.
 * A system context ("android") keeps the host package so framework calls still
 * match this process. The guest application and its activities see the clone.
 */
public final class GuestPackageName {
    private GuestPackageName() {}

    public static String visible(String contextPackage, String guestPackage, String hostPackage) {
        if (guestPackage == null || guestPackage.isEmpty() || guestPackage.equals(hostPackage)) {
            return hostPackage;
        }
        if (contextPackage == null || contextPackage.isEmpty() || "android".equals(contextPackage)) {
            return hostPackage;
        }
        if (contextPackage.equals(guestPackage) || contextPackage.equals(hostPackage)) {
            return guestPackage;
        }
        return hostPackage;
    }
}
