package top.niunaijun.blackbox.core;

/**
 * The uid a running guest sees as its own. {@code Process.myUid()} is
 * {@code Os.getuid()}, which returns this value. A guest's own provider
 * compares {@code Binder.getCallingUid()} with that same number.
 */
public final class GuestUid {
    public static final int PER_USER_RANGE = 100000;

    private GuestUid() {}

    public static int appId(int uid) {
        return uid % PER_USER_RANGE;
    }

    /** App id of the virtual uid once the guest is running, otherwise the host uid. */
    public static int myUid(boolean guestReady, int virtualUid, int hostUid) {
        if (!guestReady || virtualUid <= 0) return hostUid;
        int id = appId(virtualUid);
        return id > 0 ? id : hostUid;
    }
}
