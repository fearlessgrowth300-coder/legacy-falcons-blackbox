package top.niunaijun.blackbox.fake.service.context.providers;

/**
 * PackageManager answers for the uid a guest sees when it calls itself.
 * The process uid is the host uid, and the virtual uid is a different number
 * for the same guest. Both have to name that guest.
 */
public final class UidPackageIdentity {
    private UidPackageIdentity() {}

    public static boolean representsGuest(int uid, int hostUid, int virtualUid, int appId) {
        return uid == hostUid || uid == virtualUid || (appId > 0 && uid == appId);
    }

    public static String nameForUid(int uid, int hostUid, int virtualUid, int appId, String guestPackage) {
        if (guestPackage == null || guestPackage.isEmpty()) return null;
        return representsGuest(uid, hostUid, virtualUid, appId) ? guestPackage : null;
    }

    public static String[] packagesForCaller(int uid, int hostUid, int virtualUid, int appId,
                                              String guestPackage, String[] resolved) {
        if (!representsGuest(uid, hostUid, virtualUid, appId)
                || guestPackage == null || guestPackage.isEmpty()) {
            return resolved == null ? new String[0] : resolved;
        }
        if (resolved != null) {
            for (String name : resolved) {
                if (guestPackage.equals(name)) return resolved;
            }
        }
        int length = resolved == null ? 0 : resolved.length;
        String[] withGuest = new String[length + 1];
        withGuest[0] = guestPackage;
        if (length > 0) System.arraycopy(resolved, 0, withGuest, 1, length);
        return withGuest;
    }
}
