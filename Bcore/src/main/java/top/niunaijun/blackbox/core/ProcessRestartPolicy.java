package top.niunaijun.blackbox.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Caps how often one guest process may be started again after it dies.
 * A crash that comes straight back was relaunching Facebook about forty times.
 */
public final class ProcessRestartPolicy {
    public static final int MAX_STARTS = 3;
    public static final long WINDOW_MS = 30_000L;
    private static final Map<String, long[]> STARTS = new HashMap<>();

    private ProcessRestartPolicy() {}

    public static synchronized boolean allow(int userId, String packageName, String processName, long nowMs) {
        if (packageName == null || processName == null) return true;
        String key = userId + "\0" + packageName + "\0" + processName;
        long[] prior = STARTS.get(key);
        long[] kept = new long[MAX_STARTS];
        int count = 0;
        if (prior != null) {
            for (long started : prior) {
                if (started != 0L && nowMs >= started && nowMs - started < WINDOW_MS) {
                    if (count == MAX_STARTS) break;
                    kept[count++] = started;
                }
            }
        }
        if (count >= MAX_STARTS) return false;
        kept[count] = nowMs;
        STARTS.put(key, kept);
        return true;
    }

    /** Visible to tests. */
    public static synchronized void resetForTests() {
        STARTS.clear();
    }
}
