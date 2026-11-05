import java.util.Properties;
import top.niunaijun.blackbox.core.BuildConsistency;
import top.niunaijun.blackbox.core.NetworkIdentity;
import top.niunaijun.blackbox.core.PathIsolation;
import top.niunaijun.blackbox.core.ProfilePolicy;
import top.niunaijun.blackbox.core.ProviderIsolation;
import top.niunaijun.blackbox.core.UriGrantPolicy;
import top.niunaijun.blackbox.core.system.accounts.AccountCredentialPolicy;
import top.niunaijun.blackbox.core.system.accounts.AccountSecrets;
import top.niunaijun.blackbox.core.system.notification.NotificationVisibility;

public final class IsolationBoundaryTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        String root = "/data/user/0/top.niunaijun.blackbox/blackbox";
        String ext = "/storage/emulated/0/Android/data/top.niunaijun.blackbox/files/blackbox";
        String ownProfile = root + "/data/user/0/device_profile.properties";
        String otherProfile = root + "/data/user/17/device_profile.properties";
        check(PathIsolation.decide(ownProfile, root, ext, 0).denied == false, "own profile stays readable");
        check(!PathIsolation.decide(ownProfile, root, ext, 0).applyRules, "own profile is not rewritten by prefix rules");
        PathIsolation.Decision other = PathIsolation.decide(otherProfile, root, ext, 0);
        check(other.denied, "another user's device profile is denied");
        check(other.path != null && other.path.contains("/data/user/0/"), "denial stays inside the caller");
        String climbed = root + "/data/user/0/com.example/../../17/device_profile.properties";
        check(PathIsolation.decide(climbed, root, ext, 0).denied, "dotdot into another user is denied");
        check(PathIsolation.decide(root + "/system/accounts.conf", root, ext, 0).denied,
                "combined account store is denied");
        check(!PathIsolation.decide(root + "/data/app/com.example/base.apk", root, ext, 0).denied,
                "shared apk storage stays readable");
        check(PathIsolation.decide(ext + "/storage/emulated/17/DCIM/a.jpg", root, ext, 0).denied,
                "another user's external tree is denied");
        check(!PathIsolation.decide(ext + "/storage/emulated/0/DCIM/a.jpg", root, ext, 0).denied,
                "this user's external tree is allowed");
        check(PathIsolation.decide("/sdcard/DCIM/a.jpg", root, ext, 0).applyRules,
                "ordinary sdcard paths still use redirect rules");
        check(PathIsolation.normalize("/a/b/../../c").equals("/c"), "normalize collapses parents");

        ProfilePolicy defaults = ProfilePolicy.defaults();
        check(!defaults.shareHostMedia, "media is private by default");
        check(!defaults.shareHostGoogleProviders, "google providers are private by default");
        check(defaults.requireProxy, "direct network is blocked by default");
        Properties properties = new Properties();
        properties.setProperty("shareHostMedia", "true");
        properties.setProperty("requireProxy", "0");
        ProfilePolicy parsed = ProfilePolicy.parse(properties);
        check(parsed.shareHostMedia && !parsed.requireProxy, "explicit policy flags are honored");
        check(!parsed.shareHostGoogleProviders, "missing google flag stays isolated");

        check(!ProviderIsolation.useHostProvider("media", defaults), "media provider is not the phone's");
        check(ProviderIsolation.useHostProvider("media", parsed), "shared media follows the switch");
        check(ProviderIsolation.useHostProvider("settings", defaults), "settings stays available for android_id");
        check(!ProviderIsolation.useHostProvider("com.google.android.gsf.gservices", defaults),
                "gsf is not the phone provider by default");
        check(ProviderIsolation.useHostProvider("com.google.android.gms.chimera",
                new ProfilePolicy(false, true, true)), "google can be opted back in");

        check(UriGrantPolicy.decide("content://media/external/images/media/1", 0, false, false)
                == UriGrantPolicy.DENIED, "media uri is denied when media is private");
        check(UriGrantPolicy.decide("content://media/external/images/media/1", 0, true, false)
                == UriGrantPolicy.GRANTED, "media uri follows the share switch");
        check(UriGrantPolicy.decide("content://com.google.android.gsf.gservices/prefix", 0, false, false)
                == UriGrantPolicy.DENIED, "gsf uri is denied by default");
        check(UriGrantPolicy.decide("file://" + otherProfile, 0, false, false) == UriGrantPolicy.DENIED,
                "file uri for another profile is denied");
        check(UriGrantPolicy.decide(null, 0, false, false) == UriGrantPolicy.DEFER,
                "an unreadable uri is not blanket-granted");

        check(AccountCredentialPolicy.mayReadSecrets(true, null, null), "the account service can read secrets");
        check(AccountCredentialPolicy.mayReadSecrets(false, "com.google.android.gms", "com.google.android.gms"),
                "the owning authenticator can read secrets");
        check(!AccountCredentialPolicy.mayReadSecrets(false, "com.example.app", "com.google.android.gms"),
                "another app in the workspace cannot read secrets");
        check(!AccountCredentialPolicy.mayReadSecrets(false, "com.example.app", null),
                "a missing authenticator fails closed");

        AccountSecrets.Sealer sealer = new AccountSecrets.Sealer() {
            public String seal(String plain) { return new StringBuilder(plain).reverse().toString(); }
            public String open(String stored) {
                if (stored.indexOf('%') >= 0) throw new IllegalArgumentException("bad");
                return new StringBuilder(stored).reverse().toString();
            }
        };
        String sealed = AccountSecrets.seal("secret-token", sealer);
        check(AccountSecrets.isSealed(sealed), "sealed values are marked");
        check("secret-token".equals(AccountSecrets.open(sealed, sealer)), "sealed values round-trip");
        check(AccountSecrets.seal(null, null) == null, "null stays null");
        check(AccountSecrets.seal("x", null) == null, "a missing sealer does not persist plaintext");
        check(AccountSecrets.open(AccountSecrets.PREFIX + "%%%", sealer) == null,
                "a broken secret is not returned as a password");
        check("legacy".equals(AccountSecrets.open("legacy", sealer)), "plaintext still migrates");

        check(NotificationVisibility.ownsPostedTag("blackbox|17|com.example|tag", 17, "com.example"),
                "a notification tag matches its owner");
        check(!NotificationVisibility.ownsPostedTag("blackbox|0|com.example|tag", 17, "com.example"),
                "another user's notification tag does not match");
        check(!NotificationVisibility.ownsPostedTag("blackbox|17|com.other|tag", 17, "com.example"),
                "another package's notification tag does not match");
        check(!NotificationVisibility.ownsPostedTag(null, 17, "com.example"), "a missing tag is hidden");

        String ip0 = NetworkIdentity.dotted(0, "seed-a");
        String ip17 = NetworkIdentity.dotted(17, "seed-b");
        check(!ip0.equals(ip17), "profiles do not share a local address");
        check(ip0.startsWith("10."), "local address is not a phone LAN prefix");
        check(!NetworkIdentity.ssid("seed-a").equals(NetworkIdentity.ssid("seed-b")),
                "wifi ssid is per profile");
        String bssid = NetworkIdentity.bssid("seed-a");
        check(!bssid.equals(NetworkIdentity.bssid("seed-b")), "wifi bssid is per profile");
        int first = Integer.parseInt(bssid.substring(0, 2), 16);
        check((first & 0x02) != 0 && (first & 0x01) == 0, "bssid is locally administered unicast");
        String proc = NetworkIdentity.procNet("fib_trie", ip0, bssid);
        check(proc.contains(ip0) && !proc.contains("192.168.1.20"), "proc net does not carry a real LAN address");
        check(NetworkIdentity.uptimeOffsetMs("seed-a") > 0, "uptime offset is non-zero");
        check(!BuildConsistency.host("seed-a").equals(BuildConsistency.host("seed-b")),
                "build host differs per profile");
        check(BuildConsistency.timeMillis("seed-a") != 0L, "build time is profile-stable");

        System.out.println("PASS: " + checks + " isolation boundary checks");
    }
}
