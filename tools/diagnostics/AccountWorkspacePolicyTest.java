import top.niunaijun.blackbox.core.system.accounts.AccountWorkspacePolicy;

public final class AccountWorkspacePolicyTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Account workspace check " + checks);
    }
    public static void main(String[] args) {
        check(AccountWorkspacePolicy.allows(0, 200, 100, 0));
        check(AccountWorkspacePolicy.allows(17, 201, 100, 17));
        check(!AccountWorkspacePolicy.allows(17, 200, 100, 0));
        check(!AccountWorkspacePolicy.allows(0, 201, 100, 17));
        check(!AccountWorkspacePolicy.allows(0, 202, 100, null));
        check(!AccountWorkspacePolicy.allows(17, 202, 100, null));
        check(AccountWorkspacePolicy.allows(17, 100, 100, null));
        check(!AccountWorkspacePolicy.allows(-1, 100, 100, null));
        check(!AccountWorkspacePolicy.allows(0, 0, 100, 0));
        check(!AccountWorkspacePolicy.allows(0, 100, 0, 0));
        check(!AccountWorkspacePolicy.allows(0, 0, 0, null));
        check(!AccountWorkspacePolicy.allows(0, 200, 100, -1));
        System.out.println("Account workspace checks passed: " + checks);
    }
}
