import top.niunaijun.blackbox.core.CallerIdentityParcels;
import top.niunaijun.blackbox.core.SystemCallerIdentity;

public final class CallerIdentityParcelsTest {
    public static final class FakeEditor {
        public String packageName;
        public String label;
    }

    public static final class FakeEvent {
        CharSequence mPackageName;
    }

    public static void main(String[] args) {
        check("top.niunaijun.blackbox".equals(SystemCallerIdentity.packageForSystem(
                "com.facebook.katana", "com.facebook.katana", "top.niunaijun.blackbox")),
                "the clone package is presented as the host");
        check("com.android.settings".equals(SystemCallerIdentity.packageForSystem(
                "com.android.settings", "com.facebook.katana", "top.niunaijun.blackbox")),
                "another package is left alone");
        check("com.facebook.katana".equals(SystemCallerIdentity.packageForSystem(
                "com.facebook.katana", null, "top.niunaijun.blackbox")),
                "outside a clone the reported package stays");

        FakeEditor editor = new FakeEditor();
        editor.packageName = "com.facebook.katana";
        editor.label = "name";
        check(CallerIdentityParcels.rewrite(new Object[] {editor},
                "com.facebook.katana", "top.niunaijun.blackbox") == 1,
                "EditorInfo.packageName is rewritten");
        check("top.niunaijun.blackbox".equals(editor.packageName), "the input method sees the host");
        check("name".equals(editor.label), "the editor label stays");

        FakeEvent event = new FakeEvent();
        event.mPackageName = "com.facebook.katana";
        check(CallerIdentityParcels.rewriteObject(event, "com.facebook.katana", "top.niunaijun.blackbox") == 1,
                "an accessibility event package is rewritten");
        check("top.niunaijun.blackbox".equals(event.mPackageName.toString()),
                "the event reports the host");

        check(CallerIdentityParcels.skipClassName("android.app.job.JobInfo"),
                "job descriptions are not rewritten here");
        check(CallerIdentityParcels.skipClassName("android.content.Intent"),
                "activity intents keep their target package");
        check(!CallerIdentityParcels.skipClassName("android.view.inputmethod.EditorInfo"),
                "the input editor is rewritten");
        System.out.println("CallerIdentityParcelsTest ok (10 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
