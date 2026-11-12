import top.niunaijun.blackbox.core.HostLaunchGuard;

public final class HostLaunchGuardTest {
    public static void main(String[] args) {
        check("com.facebook.katana".equals(HostLaunchGuard.targetPackage(null, "com.facebook.katana")),
                "component package wins");
        check("com.facebook.katana".equals(HostLaunchGuard.targetPackage("com.facebook.katana", null)),
                "intent package is used when there is no component");
        check(HostLaunchGuard.targetPackage(null, null) == null, "implicit intent has no target");
        check(HostLaunchGuard.blockHostCopy("com.facebook.katana", "top.niunaijun.blackbox", true),
                "an installed guest must not start the phone's copy");
        check(!HostLaunchGuard.blockHostCopy("com.android.camera", "top.niunaijun.blackbox", false),
                "a package that is not a guest may use the phone");
        check(!HostLaunchGuard.blockHostCopy("top.niunaijun.blackbox", "top.niunaijun.blackbox", true),
                "the host package is the proxy, not a leaked guest");
        System.out.println("HostLaunchGuardTest ok (6 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
