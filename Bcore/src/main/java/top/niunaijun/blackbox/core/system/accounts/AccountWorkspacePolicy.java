package top.niunaijun.blackbox.core.system.accounts;

/** Authorization for the account store, not device-identity spoofing. */
public final class AccountWorkspacePolicy {
    private AccountWorkspacePolicy() {}

    public static boolean allows(int requestedUser, int callerPid, int servicePid,
                                 Integer registeredCallerUser) {
        if (requestedUser < 0 || callerPid <= 0 || servicePid <= 0) return false;
        if (callerPid == servicePid) return true;
        return registeredCallerUser != null && registeredCallerUser == requestedUser;
    }
}
