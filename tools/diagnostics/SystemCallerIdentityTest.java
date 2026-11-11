import top.niunaijun.blackbox.core.SystemCallerIdentity;

public final class SystemCallerIdentityTest {
    public static void main(String[] args) {
        Object[] call = new Object[] {"com.facebook.katana", "feature", null, 10477};
        check(SystemCallerIdentity.rewrite(call, "com.facebook.katana", "top.niunaijun.blackbox") == 1,
                "the calling package is rewritten");
        check("top.niunaijun.blackbox".equals(call[0]), "the host package is what the phone sees");
        check("feature".equals(call[1]), "other strings stay");

        String[] both = new String[] {"com.facebook.katana", "com.facebook.katana"};
        Object[] array = new Object[] {both};
        check(SystemCallerIdentity.rewrite(array, "com.facebook.katana", "top.niunaijun.blackbox") == 2,
                "every entry in a string array is rewritten");
        check("top.niunaijun.blackbox".equals(both[0]) && "top.niunaijun.blackbox".equals(both[1]),
                "array entries name the host");

        Object[] lookup = new Object[] {"com.facebook.katana"};
        check(SystemCallerIdentity.keepsTargetPackage("getPackageInfo"),
                "a package lookup keeps the clone name");
        check(!SystemCallerIdentity.keepsTargetPackage("addOnSubscriptionsChangedListener"),
                "subscription registration presents the host");
        check(SystemCallerIdentity.rewrite(lookup, "com.facebook.katana", "com.facebook.katana") == 0,
                "there is nothing to rewrite when the caller is not a clone");
        check("com.facebook.katana".equals(lookup[0]), "the clone name is unchanged in that case");
        System.out.println("SystemCallerIdentityTest ok (8 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
