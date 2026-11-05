import top.niunaijun.blackbox.core.AppListLoad;
import top.niunaijun.blackbox.core.NetworkIdentity;
import top.niunaijun.blackbox.core.WorkspaceIsolation;

public final class LeakAuditTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        String root = "/data/user/0/top.niunaijun.blackbox/blackbox";
        String user0 = WorkspaceIsolation.dataDir(root, 0, "com.instagram.android");
        String user4 = WorkspaceIsolation.dataDir(root, 4, "com.instagram.android");
        String play = WorkspaceIsolation.dataDir(root, 0, "com.android.vending");
        check(!user0.equals(user4), "two users do not share an Instagram data dir");
        check(!user0.equals(play), "Instagram and Play Store do not share a data dir");
        check(WorkspaceIsolation.separateDataDirs(0, "com.instagram.android", 4, "com.instagram.android"),
                "user ids separate the same package");
        check(!WorkspaceIsolation.separateDataDirs(0, "com.instagram.android", 0, "com.instagram.android"),
                "one clone keeps its own directory");

        check(WorkspaceIsolation.clipboardIsolated(0, "com.instagram.android", 4, "com.instagram.android"),
                "clipboard scope differs by user");
        check(WorkspaceIsolation.clipboardIsolated(0, "com.instagram.android", 0, "com.android.vending"),
                "clipboard scope differs by package");
        check(!WorkspaceIsolation.clipboardIsolated(0, "com.instagram.android", 0, "com.instagram.android"),
                "one clone reads its own clipboard");

        check(WorkspaceIsolation.packageListedFor(0, 0), "a user sees its own packages");
        check(!WorkspaceIsolation.packageListedFor(0, 4), "a user does not see another user's packages");

        check("android-id-kept".equals(WorkspaceIsolation.persistedAndroidId(
                "android-id-kept", "fresh-id")), "a stored Android ID survives an update");
        check("fresh-id".equals(WorkspaceIsolation.persistedAndroidId("  ", "fresh-id")),
                "only a missing Android ID is generated");

        check(!NetworkIdentity.dotted(0, "seed-a").equals(NetworkIdentity.dotted(4, "seed-b")),
                "local addresses differ per user");
        check(AppListLoad.classify(-1).kind == AppListLoad.Kind.ERROR, "a dead binder is an error");
        check(AppListLoad.classify(0).kind == AppListLoad.Kind.EMPTY, "a real empty user is empty");
        check(AppListLoad.classify(2).kind == AppListLoad.Kind.READY, "a returned list is ready");
        check(AppListLoad.ERROR.contains("Retry"), "the error state tells the user to retry");

        System.out.println("PASS: " + checks + " leak audit checks");
    }
}
