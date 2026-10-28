package top.niunaijun.blackbox.core.system.accounts;

/** Password and token reads belong to the authenticator that owns the account type. */
public final class AccountCredentialPolicy {
    private AccountCredentialPolicy() {}

    public static boolean mayReadSecrets(boolean internalServerCall, String callerPackage,
                                         String authenticatorPackage) {
        if (internalServerCall) return true;
        if (callerPackage == null || callerPackage.isEmpty()) return false;
        if (authenticatorPackage == null || authenticatorPackage.isEmpty()) return false;
        return callerPackage.equals(authenticatorPackage);
    }
}
