package top.niunaijun.blackbox.core;

/**
 * Identity AudioFlinger should see for a track created inside a clone.
 *
 * <p>The process Linux uid belongs to the host app. A track whose package is the
 * clone is rejected, and AudioFlinger then reports output 0. The guest package
 * stays on the in-process context; only the track's own call is rewritten.
 */
public final class AudioClientIdentity {
    /** {@code AudioTrack.ERROR_NATIVESETUP_NATIVEINITFAILED}. AudioFlinger output was 0. */
    public static final int NATIVE_INIT_FAILED = -20;

    private AudioClientIdentity() {}

    public static boolean inGuest(String guest, String host) {
        return guest != null && !guest.isEmpty()
                && host != null && !host.isEmpty()
                && !guest.equals(host);
    }

    /** Package passed to AudioFlinger. A clone always uses the host package. */
    public static String packageForAudio(String reported, String guest, String host) {
        if (!inGuest(guest, host)) {
            return reported;
        }
        return host;
    }

    /**
     * Uid passed to AudioFlinger. {@code realUid} is the Linux uid
     * ({@code getuid()}), which stays the host uid after {@code Os.getuid()}
     * is hooked to the guest app id.
     */
    public static int uidForAudio(boolean inGuest, int realUid, int hostUid) {
        if (!inGuest) {
            return hostUid > 0 ? hostUid : realUid;
        }
        if (realUid > 0) return realUid;
        return hostUid;
    }

    /**
     * {@code native_setup} does not return the AudioFlinger io handle.
     * Status {@link #NATIVE_INIT_FAILED} is the Java mapping of output 0.
     * Status 0 means a thread was assigned; the handle stays in native code,
     * so the log uses -1 for that case.
     */
    public static int outputForStatus(int nativeStatus) {
        if (nativeStatus == NATIVE_INIT_FAILED) return 0;
        if (nativeStatus == 0) return -1;
        return 0;
    }

    public static String createTrackLog(String pkg, int uid, int session, int output, int status,
                                       boolean parcelRewritten) {
        return "createTrack package=" + pkg
                + " uid=" + uid
                + " session=" + session
                + " output=" + output
                + " status=" + status
                + " parcel=" + (parcelRewritten ? "host" : "kept");
    }
}
