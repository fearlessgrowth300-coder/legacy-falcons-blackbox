import top.niunaijun.blackbox.core.GuestPackageName;
import top.niunaijun.blackbox.core.HostVisiblePackages;

public final class GuestPackageNameTest {
    public static void main(String[] args) {
        check("com.facebook.katana".equals(GuestPackageName.visible(
                "com.facebook.katana", "com.facebook.katana", "top.niunaijun.blackbox")),
                "a guest context keeps the guest package");
        check("com.facebook.katana".equals(GuestPackageName.visible(
                "top.niunaijun.blackbox", "com.facebook.katana", "top.niunaijun.blackbox")),
                "a guest context that was labeled with the host is corrected");
        check("top.niunaijun.blackbox".equals(GuestPackageName.visible(
                "android", "com.facebook.katana", "top.niunaijun.blackbox")),
                "the system context stays the host package");
        check("top.niunaijun.blackbox".equals(GuestPackageName.visible(
                "com.facebook.katana", null, "top.niunaijun.blackbox")),
                "no guest means the host package");
        check(HostVisiblePackages.isHostService("com.google.android.gms"), "gms is the phone's service");
        check(!HostVisiblePackages.isHostService("com.facebook.katana"), "a clone is not a host service");
        check(HostVisiblePackages.intentTargetsHostService(null, null,
                "com.google.android.gms.auth.api.phone.SMS_RETRIEVED"),
                "a play services action is answered by the phone");
        System.out.println("GuestPackageNameTest ok (7 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
