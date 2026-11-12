import top.niunaijun.blackbox.utils.compat.TrustedCallerCertificateRule;

public final class TrustedCallerCertificateRuleTest {
    public static void main(String[] args) {
        if (TrustedCallerCertificateRule.visible(true, 0, 1)) {
            throw new AssertionError("a signingInfo with no signers hides the legacy array");
        }
        if (!TrustedCallerCertificateRule.visible(true, 1, 0)) {
            throw new AssertionError("one signer on signingInfo is visible");
        }
        if (!TrustedCallerCertificateRule.visible(false, 0, 1)) {
            throw new AssertionError("legacy signatures are visible when signingInfo is absent");
        }
        if (TrustedCallerCertificateRule.visible(false, 0, 0)) {
            throw new AssertionError("no certificate is not visible");
        }
        System.out.println("TrustedCallerCertificateRuleTest ok (4 checks)");
    }
}
