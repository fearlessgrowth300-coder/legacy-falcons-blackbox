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

    /**
     * Argument slots for an instance {@code native_setup} descriptor.
     * Slot 0 is {@code JNIEnv*}, slot 1 is {@code this}, and Java arguments follow
     * in integer registers then 8-byte stack slots. Returns
     * {@code {parcel, string, session, end}} or null when the descriptor is not
     * a hookable integer/object method. {@code -1} means that argument is absent.
     * The second {@code int[]} is the session; a lone {@code int[]} is used when
     * there is only one.
     */
    public static int[] nativeSetupSlots(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != '(') {
            return null;
        }
        int end = descriptor.lastIndexOf(')');
        if (end < 1 || !descriptor.endsWith(")I")) return null;
        int slot = 2;
        int intArrays = 0;
        int parcel = -1;
        int string = -1;
        int session = -1;
        int i = 1;
        while (i < end) {
            char c = descriptor.charAt(i);
            if (c == 'F' || c == 'D') return null;
            int at = slot;
            if (c == '[') {
                int dims = 0;
                while (i < end && descriptor.charAt(i) == '[') {
                    dims++;
                    i++;
                }
                if (i >= end) return null;
                char elem = descriptor.charAt(i);
                if (elem == 'F' || elem == 'D') return null;
                if (elem == 'L') {
                    int semi = descriptor.indexOf(';', i);
                    if (semi < 0 || semi >= end) return null;
                    i = semi + 1;
                } else if (elem == 'B' || elem == 'C' || elem == 'I' || elem == 'J'
                        || elem == 'S' || elem == 'Z') {
                    i++;
                } else {
                    return null;
                }
                if (dims == 1 && elem == 'I') {
                    intArrays++;
                    if (session < 0 || intArrays == 2) session = at;
                }
                slot++;
                continue;
            }
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                if (semi < 0 || semi >= end) return null;
                String type = descriptor.substring(i, semi + 1);
                if (parcel < 0 && "Landroid/os/Parcel;".equals(type)) parcel = at;
                if ("Ljava/lang/String;".equals(type)) string = at;
                i = semi + 1;
                slot++;
                continue;
            }
            if (c == 'B' || c == 'C' || c == 'I' || c == 'J' || c == 'S' || c == 'Z') {
                i++;
                slot++;
                continue;
            }
            return null;
        }
        if (string < 0) return null;
        return new int[] {parcel, string, session, slot};
    }
}
