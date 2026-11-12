import top.niunaijun.blackbox.core.GuestPermission;

public final class GuestPermissionTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        byte[] google = new byte[] {1, 2, 3};
        byte[] other = new byte[] {9, 9};
        check(GuestPermission.sameSigner(new byte[][] {google}, new byte[][] {google}),
                "identical certs are the same signer");
        check(!GuestPermission.sameSigner(new byte[][] {google}, new byte[][] {other}),
                "a different cert is not the same signer");
        check(!GuestPermission.sameSigner(null, new byte[][] {google}),
                "a missing caller cert is not the same signer");

        int signaturePrivileged = GuestPermission.SIGNATURE | 0x10;
        check(GuestPermission.decide(true, signaturePrivileged, true) == GuestPermission.Answer.GRANT,
                "Play Store is granted READ_GSERVICES from the same Google signer");
        check(GuestPermission.decide(true, signaturePrivileged, false) == GuestPermission.Answer.DEFER,
                "a signature permission is not granted to a different signer");
        check(GuestPermission.decide(false, signaturePrivileged, true) == GuestPermission.Answer.DEFER,
                "an unrequested signature permission is not granted");
        check(GuestPermission.decide(true, GuestPermission.NORMAL, false) == GuestPermission.Answer.GRANT,
                "a requested normal permission is granted");
        check(GuestPermission.decide(true, GuestPermission.DANGEROUS, true) == GuestPermission.Answer.DEFER,
                "a dangerous permission is not an install-time grant");
        System.out.println("PASS: " + checks + " guest permission checks");
    }
}
