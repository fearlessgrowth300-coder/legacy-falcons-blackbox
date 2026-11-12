import top.niunaijun.blackbox.core.ProfileStatusCopy;

import java.util.Arrays;

public final class ProfileStatusCopyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(ProfileStatusCopy.managedUserId(false, 0, new int[]{10}, -1) == 10,
                "a visible managed profile is user 10");
        check(ProfileStatusCopy.managedUserId(false, 0, new int[0], 10) == 10,
                "a remembered user id is used when live queries are empty");
        check(ProfileStatusCopy.managedUserId(true, 10, new int[0], -1) == 10,
                "the profile-owner side reports its own user id");
        check(ProfileStatusCopy.notice(false, 0, new int[]{10}, -1, false, true, true)
                        == ProfileStatusCopy.Notice.ACTIVE,
                "our running profile is active even when the slot is taken and Secure Folder exists");
        String active = ProfileStatusCopy.activeMessage(10, false);
        check(active.contains("Android user 10") && active.contains("Work tab") && active.contains("ShieldProxy"),
                "the main side tells the user to open the work profile and install ShieldProxy");
        check(ProfileStatusCopy.activeMessage(10, true).contains("This is the separate Android profile"),
                "the profile side says the user is already inside it");
        check(ProfileStatusCopy.notice(false, 0, new int[0], -1, false, true, true)
                        == ProfileStatusCopy.Notice.SLOT_SAMSUNG,
                "Secure Folder is named only when the slot is not ours");
        check(ProfileStatusCopy.notice(false, 0, new int[0], -1, false, false, true)
                        == ProfileStatusCopy.Notice.SLOT_OTHER,
                "a foreign profile is not called Secure Folder");
        check(!ProfileStatusCopy.secureFolderPackage("com.samsung.android.knox.containercore"),
                "Knox platform packages are not Secure Folder");
        check(ProfileStatusCopy.secureFolderPackage("com.samsung.knox.securefolder"),
                "the Secure Folder package is recognized");

        int[] workspaces = ProfileStatusCopy.realWorkspaceIds(new int[]{-1, 0, 4, 5, 10, 20});
        check(Arrays.equals(workspaces, new int[]{0, 4, 5, 10, 20}),
                "negative ids are not workspaces");
        check(ProfileStatusCopy.resolveBoundWorkspace(-1, new int[]{4}) == 4,
                "one explicit separate workspace is the bound one");
        check(ProfileStatusCopy.resolveBoundWorkspace(-1, new int[]{4, 5, 10}) == -1,
                "several explicit choices are not all treated as bound");
        check(ProfileStatusCopy.resolveBoundWorkspace(7, new int[]{4}) == 7,
                "a recorded binding wins");
        check(ProfileStatusCopy.launchesSeparate(4, 4), "the bound workspace launches separate");
        check(!ProfileStatusCopy.launchesSeparate(5, 4), "other workspaces stay hooked");
        check(!ProfileStatusCopy.launchesSeparate(10, -1), "nothing is separate until one workspace is bound");
        String line = ProfileStatusCopy.workspaceLine(5, 4);
        check(line.contains("hooked") && !line.contains("separate Android profile"),
                "an unbound workspace is not labelled separate");
        check(ProfileStatusCopy.workspaceLine(4, 4).contains("separate Android profile"),
                "the bound workspace is labelled separate");

        int darkOnDark = ProfileStatusCopy.chooseTextColor(0xFF212121, 0xFF091121, 0xFFF6F7FF);
        check(darkOnDark == 0xFFF6F7FF, "dark theme text on the dark window uses the light fallback");
        int lightOnDark = ProfileStatusCopy.chooseTextColor(0xFFF6F7FF, 0xFF091121, 0xFF000000);
        check(lightOnDark == 0xFFF6F7FF, "text that already contrasts is kept");

        System.out.println("PASS: " + checks + " profile status checks");
    }
}
