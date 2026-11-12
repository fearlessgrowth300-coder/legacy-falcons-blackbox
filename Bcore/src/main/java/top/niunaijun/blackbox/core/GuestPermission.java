package top.niunaijun.blackbox.core;

import java.util.Arrays;

/**
 * Install-time permission answer for one guest calling another. A requested
 * normal permission is granted. A requested signature permission is granted
 * only when the caller and the defining package share a signer. Dangerous
 * permissions are left to the real runtime grant.
 */
public final class GuestPermission {
    public static final int NORMAL = 0;
    public static final int DANGEROUS = 1;
    public static final int SIGNATURE = 2;
    public static final int SIGNATURE_OR_SYSTEM = 3;
    public static final int PROTECTION_MASK = 0xf;

    public enum Answer { GRANT, DEFER }

    private GuestPermission() {}

    public static Answer decide(boolean requested, int protectionLevel, boolean sameSigner) {
        if (!requested) return Answer.DEFER;
        int base = protectionLevel & PROTECTION_MASK;
        if (base == NORMAL) return Answer.GRANT;
        if (base == SIGNATURE || base == SIGNATURE_OR_SYSTEM) {
            return sameSigner ? Answer.GRANT : Answer.DEFER;
        }
        return Answer.DEFER;
    }

    public static boolean sameSigner(byte[][] callerCerts, byte[][] ownerCerts) {
        if (callerCerts == null || ownerCerts == null) return false;
        for (byte[] caller : callerCerts) {
            if (caller == null || caller.length == 0) continue;
            for (byte[] owner : ownerCerts) {
                if (owner != null && Arrays.equals(caller, owner)) return true;
            }
        }
        return false;
    }
}
