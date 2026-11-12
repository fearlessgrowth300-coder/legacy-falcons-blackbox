package top.niunaijun.blackbox.utils.compat;

import java.security.MessageDigest;
import java.util.Arrays;

/** Compares a presented certificate with the cloned APK's own certificates. */
public final class SigningCertificateMatch {
    public static final int RAW_X509 = 1;
    public static final int SHA256 = 2;

    private SigningCertificateMatch() {}

    public static boolean matches(byte[][] certificates, byte[] presented, int type) {
        if (certificates == null || presented == null || presented.length == 0) return false;
        for (byte[] certificate : certificates) {
            if (certificate == null || certificate.length == 0) continue;
            if (type == RAW_X509 && Arrays.equals(certificate, presented)) return true;
            if (type == SHA256 && Arrays.equals(sha256(certificate), presented)) return true;
        }
        return false;
    }

    private static byte[] sha256(byte[] certificate) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(certificate);
        } catch (Exception error) {
            return new byte[0];
        }
    }
}
