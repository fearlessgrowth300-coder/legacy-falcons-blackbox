package top.niunaijun.blackbox.core;

/**
 * What the profile status screen should say. Android user ids and workspace ids
 * are plain ints so this can be checked without a device.
 */
public final class ProfileStatusCopy {
    public enum Notice {
        ACTIVE,
        EMPTY,
        SLOT_SAMSUNG,
        SLOT_OTHER,
        UNAVAILABLE
    }

    private ProfileStatusCopy() {}

    /** Secure Folder's package. Knox platform packages are on every Samsung phone. */
    public static final String SECURE_FOLDER_PACKAGE = "com.samsung.knox.securefolder";

    public static boolean secureFolderPackage(String packageName) {
        return SECURE_FOLDER_PACKAGE.equals(packageName);
    }

    /**
     * The Android user id of our managed profile, or -1 when this side cannot see one.
     * {@code others} are ids from UserManager, CrossProfileApps, and hidden profile lists.
     */
    public static int managedUserId(boolean profileOwnerHere, int myUserId, int[] others, int remembered) {
        if (profileOwnerHere && myUserId >= 0) return myUserId;
        if (others != null) {
            for (int id : others) {
                if (id >= 0 && id != myUserId) return id;
            }
        }
        if (remembered >= 0 && remembered != myUserId) return remembered;
        return -1;
    }

    public static Notice notice(boolean profileOwnerHere, int myUserId, int[] others, int remembered,
                                boolean provisioningAllowed, boolean secureFolderInstalled,
                                boolean setupActivityResolved) {
        if (managedUserId(profileOwnerHere, myUserId, others, remembered) >= 0) return Notice.ACTIVE;
        if (!setupActivityResolved) return Notice.UNAVAILABLE;
        if (provisioningAllowed) return Notice.EMPTY;
        if (secureFolderInstalled) return Notice.SLOT_SAMSUNG;
        return Notice.SLOT_OTHER;
    }

    public static String activeMessage(int androidUserId, boolean insideProfile) {
        if (insideProfile) {
            return "This is the separate Android profile (Android user " + androidUserId + "). "
                    + "Install the apps for this account here, including ShieldProxy. "
                    + "A VPN belongs to one Android user, so ShieldProxy in the main profile does not cover this one.";
        }
        return "Separate Android profile is active (Android user " + androidUserId + "). "
                + "Blackbox is its profile owner. Open Blackbox in the Work tab to finish setup and install apps there. "
                + "Install ShieldProxy inside that profile too. A VPN belongs to one Android user.";
    }

    public static String emptyMessage() {
        return "No other Android profile is visible yet. A separate profile is a real Android user "
                + "(usually user 10 or higher) with its own uid. Hooked workspaces in this app still share this uid.";
    }

    /**
     * One explicit separate workspace can be treated as the bound one after a provision
     * that did not record the id. Several explicit choices are not guessed.
     */
    public static int resolveBoundWorkspace(int storedBound, int[] explicitSeparateIds) {
        if (storedBound >= 0) return storedBound;
        if (explicitSeparateIds != null && explicitSeparateIds.length == 1) {
            return explicitSeparateIds[0];
        }
        return -1;
    }

    public static boolean launchesSeparate(int workspaceId, int boundWorkspaceId) {
        return boundWorkspaceId >= 0 && workspaceId == boundWorkspaceId;
    }

    public static String workspaceLine(int workspaceId, int boundWorkspaceId) {
        if (launchesSeparate(workspaceId, boundWorkspaceId)) {
            return "Workspace " + workspaceId + ": separate Android profile\n"
                    + "This workspace launches in the Android work profile.";
        }
        return "Workspace " + workspaceId + ": same profile, hooked\n"
                + "Apps run in this process and share its Linux uid.";
    }

    /** Keep ids that exist as virtual users. Negative ids are not workspaces. */
    public static int[] realWorkspaceIds(int[] ids) {
        if (ids == null || ids.length == 0) return new int[0];
        int count = 0;
        for (int id : ids) if (id >= 0) count++;
        int[] out = new int[count];
        int n = 0;
        for (int id : ids) if (id >= 0) out[n++] = id;
        return out;
    }

    /** Theme text that does not contrast with the window is replaced by the fallback. */
    public static int chooseTextColor(int themeText, int background, int fallback) {
        if (Math.abs(luma(themeText) - luma(background)) >= 120) return themeText;
        return fallback;
    }

    static int luma(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
