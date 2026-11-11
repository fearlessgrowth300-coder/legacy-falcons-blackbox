package top.niunaijun.blackbox.core;

/**
 * Cold proxy listeners drop the first dial. The clone stays closed until an
 * in-process probe succeeds, so a longer budget is still fail-closed.
 */
public final class ExitProbeBudget {
    public static final int ATTEMPTS = 3;
    public static final int CONNECT_TIMEOUT_MS = 12000;
    public static final int READ_TIMEOUT_MS = 8000;
    public static final long PAUSE_MS = 1000;

    private ExitProbeBudget() {}

    public static boolean retry(int attempt) {
        return attempt + 1 < ATTEMPTS;
    }
}
