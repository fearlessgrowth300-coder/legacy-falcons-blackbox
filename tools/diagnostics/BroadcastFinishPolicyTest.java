import top.niunaijun.blackbox.core.BroadcastFinishPolicy;

public final class BroadcastFinishPolicyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(BroadcastFinishPolicy.shouldFinish(true, true),
                "a receiver that returns normally is finished once");
        check(!BroadcastFinishPolicy.shouldFinish(true, false),
                "goAsync keeps ownership of the result");
        check(BroadcastFinishPolicy.shouldFinish(false, false),
                "a receiver that never started still releases the broadcast");
        check(BroadcastFinishPolicy.shouldFinish(false, true),
                "a missing receiver does not leave the broadcast open");
        System.out.println("PASS: " + checks + " broadcast finish checks");
    }
}
