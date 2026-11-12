package top.niunaijun.blackbox.core.system.notification;

/** Matches the host tag written by {@code BNotificationManagerService}. */
public final class NotificationVisibility {
    private NotificationVisibility() {}

    public static boolean ownsPostedTag(String tag, int userId, String packageName) {
        if (tag == null || packageName == null || packageName.isEmpty() || userId < 0) return false;
        return tag.startsWith("blackbox|" + userId + "|" + packageName + "|");
    }
}
