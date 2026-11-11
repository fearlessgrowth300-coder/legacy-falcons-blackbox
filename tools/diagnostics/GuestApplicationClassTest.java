import top.niunaijun.blackbox.core.ProcessRestartPolicy;
import top.niunaijun.blackbox.utils.CrashLoopPolicy;
import top.niunaijun.blackbox.utils.compat.GuestApplicationClass;

public final class GuestApplicationClassTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check("com.facebook.katana.FacebookApplication".equals(GuestApplicationClass.expected(
                "com.facebook.katana.FacebookApplication", null)),
                "manifest class is used when the process has no override");
        check("com.facebook.katana.FacebookApplication".equals(GuestApplicationClass.expected(
                "android.app.Application", "com.facebook.katana.FacebookApplication")),
                "per-process class wins over the application tag");
        check(GuestApplicationClass.DEFAULT.equals(GuestApplicationClass.expected(null, "")),
                "missing manifest class stays the framework default");
        check(GuestApplicationClass.needsReplacement(
                "android.app.Application", "com.facebook.katana.FacebookApplication"),
                "default instance must be replaced with the manifest class");
        check(!GuestApplicationClass.needsReplacement(
                "com.facebook.katana.FacebookApplication", "com.facebook.katana.FacebookApplication"),
                "the matching class is left in place");
        check(!GuestApplicationClass.needsReplacement("android.app.Application", "android.app.Application"),
                "an app with no custom class is not replaced");

        IllegalStateException appContext = new IllegalStateException("AppContext.set has not been invoked");
        RuntimeException activity = new RuntimeException(
                "Unable to start activity ComponentInfo{com.facebook.katana/com.facebook.katana.activity.FbMainTabActivity}",
                appContext);
        check(CrashLoopPolicy.fatalActivityLaunch(activity), "activity launch failure is fatal");
        check(CrashLoopPolicy.fatalActivityLaunch(appContext), "AppContext failure is fatal on its own");
        check(!CrashLoopPolicy.allowSwallow(activity, true, 1), "a fatal launch is not swallowed");
        check(CrashLoopPolicy.allowSwallow(new NullPointerException("INSTANCE_FIELD"), true, 1),
                "one recoverable init failure may be swallowed");
        check(CrashLoopPolicy.allowSwallow(new NullPointerException("INSTANCE_FIELD"), true, 2),
                "the second identical swallow is the last");
        check(!CrashLoopPolicy.allowSwallow(new NullPointerException("INSTANCE_FIELD"), true, 3),
                "the third identical failure ends the loop");
        check(!CrashLoopPolicy.allowSwallow(new NullPointerException("INSTANCE_FIELD"), false, 1),
                "a non-swallowable failure stays fatal");

        ProcessRestartPolicy.resetForTests();
        long now = 1_000_000L;
        check(ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana", now),
                "first start is allowed");
        check(ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana", now + 100),
                "second start is allowed");
        check(ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana", now + 200),
                "third start is allowed");
        check(!ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana", now + 300),
                "fourth start inside the window is refused");
        check(ProcessRestartPolicy.allow(22, "com.instagram.android", "com.instagram.android", now + 300),
                "another package is counted separately");
        check(ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana:push", now + 300),
                "another process is counted separately");
        check(ProcessRestartPolicy.allow(22, "com.facebook.katana", "com.facebook.katana",
                now + ProcessRestartPolicy.WINDOW_MS + 301),
                "a start after the window is allowed again");
        System.out.println("GuestApplicationClassTest ok (" + checks + " checks)");
    }
}
