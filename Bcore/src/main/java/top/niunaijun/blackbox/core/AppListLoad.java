package top.niunaijun.blackbox.core;

/**
 * The home grid must not treat a dead workspace process as an empty user.
 * A transport failure stays an error until a later attempt returns a real list.
 */
public final class AppListLoad {
    public static final long TIMEOUT_MS = 8000L;
    public static final int MAX_ATTEMPTS = 3;
    public static final String LOADING = "Loading apps…";
    public static final String EMPTY = "No apps in this user yet";
    public static final String ERROR = "Couldn't load this user's apps. Tap Retry.";

    public enum Kind { LOADING, READY, EMPTY, ERROR }

    public static final class Outcome {
        public final Kind kind;
        public final String message;

        public Outcome(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }
    }

    private AppListLoad() {}

    /** {@code size} is negative when the binder call failed or timed out. */
    public static Outcome classify(int size) {
        if (size < 0) return new Outcome(Kind.ERROR, ERROR);
        if (size == 0) return new Outcome(Kind.EMPTY, EMPTY);
        return new Outcome(Kind.READY, null);
    }

    public static Outcome loading() {
        return new Outcome(Kind.LOADING, LOADING);
    }
}
