package top.niunaijun.blackbox.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.Map;

/** Logs every guest thread stack. Shell can ask with a DUMP-protected broadcast. */
public final class GuestStackDump {
    public static final String ACTION = "top.niunaijun.blackbox.action.DUMP_GUEST_STACKS";
    private static final String TAG = "BlackBoxStackDump";

    private GuestStackDump() {}

    public static void install(Context context) {
        if (context == null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                dump();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, android.Manifest.permission.DUMP,
                    null, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null);
        }
    }

    public static void dump() {
        java.util.LinkedHashMap<Thread, StackTraceElement[]> stacks =
                new java.util.LinkedHashMap<>(Thread.getAllStackTraces());
        Thread current = Thread.currentThread();
        stacks.putIfAbsent(current, current.getStackTrace());
        try {
            Thread main = android.os.Looper.getMainLooper().getThread();
            stacks.putIfAbsent(main, main.getStackTrace());
        } catch (Throwable ignored) {
        }
        Slog.i(TAG, "guest stacks threads=" + stacks.size());
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            Thread thread = entry.getKey();
            Slog.i(TAG, thread.getName() + " id=" + thread.getId() + " state=" + thread.getState());
            for (StackTraceElement frame : entry.getValue()) {
                Slog.i(TAG, "  at " + frame);
            }
        }
    }
}
