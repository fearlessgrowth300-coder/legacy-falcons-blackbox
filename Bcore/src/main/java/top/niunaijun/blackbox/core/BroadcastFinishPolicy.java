package top.niunaijun.blackbox.core;

/**
 * A guest receiver that calls goAsync() clears its pending result and finishes
 * that same object later, often from queued SharedPreferences work. Finishing
 * it again from the dispatcher throws "Broadcast already finished" on the
 * queued-work thread and kills the clone.
 */
public final class BroadcastFinishPolicy {
    private BroadcastFinishPolicy() {}

    /**
     * @param receiverInstalled onReceive was entered on a live receiver
     * @param pendingResultStillHeld getPendingResult() is still set afterwards
     */
    public static boolean shouldFinish(boolean receiverInstalled, boolean pendingResultStillHeld) {
        if (!receiverInstalled) return true;
        return pendingResultStillHeld;
    }
}
