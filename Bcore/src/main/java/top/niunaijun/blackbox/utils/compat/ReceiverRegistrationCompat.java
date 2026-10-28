package top.niunaijun.blackbox.utils.compat;

/**
 * Android 13 added {@code RECEIVER_EXPORTED} / {@code RECEIVER_NOT_EXPORTED}. Android 14
 * throws if a registration sets neither. A non-exported receiver also never sees system
 * broadcasts such as {@code SCREEN_ON}, which is how cloned Google services learn that
 * the phone woke up.
 */
public final class ReceiverRegistrationCompat {
    public static final int RECEIVER_EXPORTED = 0x2;
    public static final int RECEIVER_NOT_EXPORTED = 0x4;

    private ReceiverRegistrationCompat() {}

    public static int ensureExportFlag(int flags, boolean systemBroadcast) {
        if ((flags & (RECEIVER_EXPORTED | RECEIVER_NOT_EXPORTED)) != 0) return flags;
        return flags | (systemBroadcast ? RECEIVER_EXPORTED : RECEIVER_NOT_EXPORTED);
    }

    public static boolean looksLikeSystemBroadcast(Iterable<String> actions) {
        if (actions == null) return false;
        for (String action : actions) {
            if (action == null) continue;
            if (action.startsWith("android.")
                    || action.startsWith("com.android.")
                    || action.startsWith("com.google.android.")) {
                return true;
            }
        }
        return false;
    }
}
