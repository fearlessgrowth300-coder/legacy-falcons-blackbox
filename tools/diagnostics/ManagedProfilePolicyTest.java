import top.niunaijun.blackbox.core.ManagedProfilePolicy;
import top.niunaijun.blackbox.core.PlayProcessPolicy;

public final class ManagedProfilePolicyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(ManagedProfilePolicy.recommended(0) == ManagedProfilePolicy.Mode.HOOKED,
                "the first workspace stays hooked");
        check(ManagedProfilePolicy.recommended(1) == ManagedProfilePolicy.Mode.SEPARATE,
                "the second workspace recommends a real Android profile");
        check(ManagedProfilePolicy.shortLabel(ManagedProfilePolicy.Mode.SEPARATE)
                        .contains("separate"),
                "separate mode is labeled");
        check(ManagedProfilePolicy.blockReason(true, false, false, true) == null,
                "provisioning can start when Android allows it");
        check(ManagedProfilePolicy.blockReason(true, true, false, true) == null,
                "an existing profile owner is not a new-provision block");
        String knox = ManagedProfilePolicy.blockReason(false, false, true, true);
        check(knox != null && knox.contains("Samsung"), "Knox names Samsung when the slot is taken");
        String generic = ManagedProfilePolicy.blockReason(false, false, false, true);
        check(generic != null && generic.contains("work profile"), "a taken slot is explained");
        String missing = ManagedProfilePolicy.blockReason(false, false, false, false);
        check(missing != null && missing.contains("cannot start"), "a missing setup activity is explained");
        check(PlayProcessPolicy.refuseStart("com.android.vending", "com.android.vending:quick_launch"),
                "Play quick_launch is not started");
        check(!PlayProcessPolicy.refuseStart("com.android.vending", "com.android.vending"),
                "the main Play process still starts");
        check(!PlayProcessPolicy.refuseStart("com.example.app", "com.example.app:quick_launch"),
                "other apps are not refused");
        System.out.println("PASS: " + checks + " managed profile policy checks");
    }
}
