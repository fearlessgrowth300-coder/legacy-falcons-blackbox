package top.niunaijun.blackbox.core.system.accounts;

/**
 * Marks password and token strings that have been encrypted for disk.
 * A failed seal returns null so the caller can refuse to persist plaintext.
 * A failed open returns null so ciphertext is never handed to a guest as a password.
 */
public final class AccountSecrets {
    public static final String PREFIX = "enc:v1:";

    public interface Sealer {
        String seal(String plain) throws Exception;

        String open(String stored) throws Exception;
    }

    private AccountSecrets() {}

    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String seal(String plain, Sealer sealer) {
        if (plain == null || isSealed(plain)) return plain;
        if (sealer == null) return null;
        try {
            String sealed = sealer.seal(plain);
            if (sealed == null || sealed.isEmpty()) return null;
            return PREFIX + sealed;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String open(String stored, Sealer sealer) {
        if (!isSealed(stored)) return stored;
        if (sealer == null) return null;
        try {
            return sealer.open(stored.substring(PREFIX.length()));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
