import top.niunaijun.blackbox.core.system.notification.NotificationNamespace;

public final class NotificationNamespaceTest {
    private static int checks;
    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("Notification scope check " + checks);
    }
    public static void main(String[] args) {
        NotificationNamespace a = new NotificationNamespace("com.example.chat", 0);
        NotificationNamespace b = new NotificationNamespace("com.example.chat", 17);
        NotificationNamespace c = new NotificationNamespace("com.example.mail", 0);
        String[] ids = {"messages", "", "a@black-b", "messages@black-17-com.example.chat", "one/two"};
        for (String id : ids) {
            String stored = a.hostChannel(id);
            check(a.ownsChannel(stored));
            check(!b.ownsChannel(stored));
            check(!c.ownsChannel(stored));
            check(id.equals(a.guestChannel(stored)));
            check(id.equals(a.guestGroup(a.hostGroup(id))));
            // A newly constructed scope reads an Android-persisted ID after service restart.
            check(id.equals(new NotificationNamespace("com.example.chat", 0).guestChannel(stored)));
        }
        check(a.hostGroup(null) == null);
        check(a.guestGroup(null) == null);
        check(!a.ownsChannel(null));
        check(!a.ownsGroup(b.hostGroup("messages")));
        check(a.hostChannel("messages").equals("messages@black-0-com.example.chat"));
        System.out.println("Notification namespace checks passed: " + checks);
    }
}
