package top.niunaijun.blackbox.core;

/**
 * System apps the work profile should show in the launcher. Play Store is first
 * because that is how the person installs ShieldProxy and their other apps there.
 * Knox platform packages are not included.
 */
public final class ProfileWorkApps {
    private static final String[] PACKAGES = {
            "com.android.vending",
            "com.google.android.gms",
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.sec.android.app.myfiles",
            "com.android.settings"
    };

    public static final String SHIELD_PROXY = "com.privacyshield.proxy";

    private ProfileWorkApps() {}

    public static String[] systemPackages() {
        return PACKAGES.clone();
    }

    public static boolean isPlayStore(String packageName) {
        return "com.android.vending".equals(packageName);
    }

    /** Shown when the personal side cannot start the work profile. */
    public static String removeByHandMessage() {
        return "Blackbox could not open the work profile from here. On this Samsung phone, "
                + "remove it in Settings, Security and privacy, More security settings, Work profile, "
                + "then set it up again from Manage users.";
    }
}
