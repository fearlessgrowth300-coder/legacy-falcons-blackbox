package top.niunaijun.blackbox.utils.compat;

/**
 * Facebook's trusted-caller helper reads {@code signingInfo} when it is present
 * and the legacy {@code signatures} array only otherwise. Either source has to
 * contain at least one certificate or it throws with the package name.
 */
public final class TrustedCallerCertificateRule {
    private TrustedCallerCertificateRule() {}

    public static boolean visible(boolean signingInfoPresent, int signerCount, int legacyCount) {
        if (signingInfoPresent) return signerCount > 0;
        return legacyCount > 0;
    }
}
