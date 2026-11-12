package top.niunaijun.blackbox.core.system.notification;

/** Stable host namespaces; exact suffix matching prevents cross-workspace lookups. */
public final class NotificationNamespace {
    private final String channelSuffix;
    private final String groupSuffix;

    public NotificationNamespace(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty() || userId < 0) {
            throw new IllegalArgumentException("Invalid notification owner");
        }
        channelSuffix = "@black-" + userId + "-" + packageName;
        groupSuffix = "@black-group-" + userId + "-" + packageName;
    }

    public String hostChannel(String id) { return append(id, channelSuffix); }
    public String hostGroup(String id) { return append(id, groupSuffix); }
    public boolean ownsChannel(String id) { return id != null && id.endsWith(channelSuffix); }
    public boolean ownsGroup(String id) { return id != null && id.endsWith(groupSuffix); }
    public String guestChannel(String id) { return strip(id, channelSuffix); }
    public String guestGroup(String id) { return strip(id, groupSuffix); }

    private static String append(String id, String suffix) { return id == null ? null : id + suffix; }
    private static String strip(String id, String suffix) {
        return id != null && id.endsWith(suffix) ? id.substring(0, id.length() - suffix.length()) : id;
    }
}
