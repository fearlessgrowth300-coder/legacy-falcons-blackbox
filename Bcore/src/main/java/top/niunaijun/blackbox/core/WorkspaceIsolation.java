package top.niunaijun.blackbox.core;

/**
 * Facts the leak audit can check without a device. Data, clipboard, and package
 * queries are scoped by virtual user. A stored Android ID is never replaced.
 */
public final class WorkspaceIsolation {
    private WorkspaceIsolation() {}

    public static String dataDir(String virtualRoot, int userId, String pkg) {
        return virtualRoot + "/data/user/" + userId + "/" + pkg;
    }

    public static boolean separateDataDirs(int userA, String pkgA, int userB, String pkgB) {
        return userA != userB || (pkgA != null && !pkgA.equals(pkgB));
    }

    /** Additional authenticated data bound into the clone clipboard cipher. */
    public static String clipboardScope(int userId, String pkg) {
        return "BlackBoxClipboard|1|" + userId + "|" + pkg;
    }

    public static boolean clipboardIsolated(int userA, String pkgA, int userB, String pkgB) {
        return !clipboardScope(userA, pkgA).equals(clipboardScope(userB, pkgB));
    }

    /** A guest package query lists packages installed for that same virtual user. */
    public static boolean packageListedFor(int installedUser, int callerUser) {
        return installedUser == callerUser;
    }

    /** Keep the id already written for this user. A missing value may be generated once. */
    public static String persistedAndroidId(String stored, String generated) {
        if (stored != null && !stored.trim().isEmpty()) return stored;
        return generated;
    }
}
