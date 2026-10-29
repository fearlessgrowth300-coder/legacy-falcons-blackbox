import top.niunaijun.blackbox.core.ProfileWorkApps;

import java.util.Arrays;
import java.util.HashSet;

public final class ProfileWorkAppsTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        String[] packages = ProfileWorkApps.systemPackages();
        check(packages.length >= 8, "the work profile enables several launcher apps");
        check(ProfileWorkApps.isPlayStore(packages[0]), "Play Store is enabled first");
        check(Arrays.asList(packages).contains("com.android.chrome"), "Chrome is on the list");
        check(Arrays.asList(packages).contains("com.sec.android.app.sbrowser"), "Samsung Internet is on the list");
        check(Arrays.asList(packages).contains("com.sec.android.app.myfiles"), "Samsung Files is on the list");
        check(Arrays.asList(packages).contains("com.android.settings"), "Settings is on the list");
        check(new HashSet<>(Arrays.asList(packages)).size() == packages.length, "package names are unique");
        check(!Arrays.asList(packages).contains("com.samsung.android.knox.containercore"),
                "Knox platform packages are not enabled as work apps");
        check("com.privacyshield.proxy".equals(ProfileWorkApps.SHIELD_PROXY),
                "ShieldProxy is copied into the profile when it is already on the phone");
        String hand = ProfileWorkApps.removeByHandMessage();
        check(hand.contains("Settings") && hand.contains("Work profile"),
                "a profile that cannot be opened tells the user how to remove it");
        System.out.println("PASS: " + checks + " work profile app checks");
    }
}
