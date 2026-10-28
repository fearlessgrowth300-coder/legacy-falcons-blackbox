package top.niunaijun.blackbox.utils.compat;

/**
 * Maps a guest foreground-service type onto a type the host proxy service declares.
 *
 * <p>Android 14+ rejects a started foreground service whose type is missing or undeclared.
 * Zeroing the type (the previous hook) also drops the process out of the foreground
 * oom class, so the cloned app is killed mid-session. Types the host does not declare
 * are replaced with {@code specialUse}, which the proxy manifest declares.
 */
public final class ForegroundServiceTypeCompat {
    public static final int NONE = 0;
    public static final int MANIFEST = -1;
    public static final int DATA_SYNC = 1;
    public static final int MEDIA_PLAYBACK = 2;
    public static final int PHONE_CALL = 4;
    public static final int LOCATION = 8;
    public static final int CONNECTED_DEVICE = 16;
    public static final int MEDIA_PROJECTION = 32;
    public static final int CAMERA = 64;
    public static final int MICROPHONE = 128;
    public static final int HEALTH = 256;
    public static final int REMOTE_MESSAGING = 512;
    public static final int SHORT_SERVICE = 2048;
    public static final int SPECIAL_USE = 0x40000000;

    /** Bits declared on the host ProxyService / daemon entries. */
    public static final int HOST_DECLARED =
            DATA_SYNC | MEDIA_PLAYBACK | PHONE_CALL | LOCATION | CONNECTED_DEVICE
                    | CAMERA | MICROPHONE | REMOTE_MESSAGING | SPECIAL_USE;

    private ForegroundServiceTypeCompat() {}

    public static int supportedType(int requested) {
        if (requested == NONE || requested == MANIFEST) return SPECIAL_USE;
        int kept = requested & HOST_DECLARED;
        if (kept != 0) return kept;
        return SPECIAL_USE;
    }

    /** The first integer of {@code setServiceForeground} is the notification id. */
    public static boolean isStopRequest(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return ((Integer) arg) == 0;
            }
        }
        return false;
    }

    public static int lastIntegerIndex(Object[] args) {
        if (args == null) return -1;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Integer) return i;
        }
        return -1;
    }
}
