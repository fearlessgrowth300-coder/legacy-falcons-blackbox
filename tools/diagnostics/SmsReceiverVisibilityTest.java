import top.niunaijun.blackbox.core.SmsReceiverVisibility;

public final class SmsReceiverVisibilityTest {
    public static void main(String[] args) {
        boolean facebookGuard = SmsReceiverVisibility.guardAllows("com.facebook.katana", false);
        check(!SmsReceiverVisibility.visible("com.facebook.katana", facebookGuard, false, true),
                "a cloned Facebook receiver is hidden");
        check(!SmsReceiverVisibility.visible("com.facebook.katana", facebookGuard, true, true),
                "a cloned Facebook package stays hidden even as the SMS holder");
        check(!SmsReceiverVisibility.visible("com.facebook.katana", facebookGuard, false, false),
                "Facebook is not visible just because it has a receiver");
        check(!SmsReceiverVisibility.visible("com.instagram.android", false, false, true),
                "a cloned Instagram package is hidden");
        check(!SmsReceiverVisibility.visible("com.zhiliaoapp.musically", false, false, false),
                "a third-party app is hidden");
        check(SmsReceiverVisibility.visible("com.google.android.apps.messaging",
                SmsReceiverVisibility.guardAllows("com.google.android.apps.messaging", false),
                true, false),
                "the default SMS holder is visible when it is not a clone");
        check(SmsReceiverVisibility.visible("com.android.phone",
                SmsReceiverVisibility.guardAllows("com.android.phone", false), false, false),
                "the platform phone package is visible");
        check(SmsReceiverVisibility.visible("android",
                SmsReceiverVisibility.guardAllows("android", true), false, false),
                "a package the visibility guard already allows stays visible");
        Object[] roleArgs = SmsReceiverVisibility.roleCallArguments(
                new Class<?>[] {String.class, int.class, String.class},
                "top.niunaijun.blackbox", 0);
        check("android.app.role.SMS".equals(roleArgs[0]), "the role name is SMS");
        check("top.niunaijun.blackbox".equals(roleArgs[2]),
                "the role call names the host, not a clone");
        System.out.println("SmsReceiverVisibilityTest ok (10 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
