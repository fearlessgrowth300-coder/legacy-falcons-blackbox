package top.niunaijun.blackbox.core;

import java.util.function.IntUnaryOperator;

/** Transaction-scoped identity selection; unknown callers keep their Linux identity. */
public final class CallerUidResolver {
    private CallerUidResolver() {}

    public static int resolve(int originalUid, int hostUid, int callerPid,
                              int ownPid, int ownVirtualUid, IntUnaryOperator lookup) {
        if (originalUid != hostUid || callerPid <= 0) return originalUid;
        if (callerPid == ownPid) {
            return ownVirtualUid >= 10000 ? ownVirtualUid : originalUid;
        }
        int virtualUid = lookup.applyAsInt(callerPid);
        return virtualUid >= 10000 ? virtualUid : originalUid;
    }
}
