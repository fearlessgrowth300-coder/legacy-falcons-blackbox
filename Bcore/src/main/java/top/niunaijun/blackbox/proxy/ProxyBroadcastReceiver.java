package top.niunaijun.blackbox.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;


public class ProxyBroadcastReceiver extends BroadcastReceiver {
    public static final String TAG = "ProxyBroadcastReceiver";

    // Just under the 10s foreground-broadcast ANR limit. If the guest receiver is still running
    // its work (slow, esp. with all traffic on TCP-via-proxy) we complete the HOST's broadcast so
    // the container never shows "BlackBox isn't responding". This does NOT stop the guest
    // receiver's own work (it runs in the guest process independently) — it only releases the host
    // broadcast token. Any crash from an early-completed init broadcast is caught by the
    // SimpleCrashFix main-loop guard, so the app survives instead of closing.
    private static final long FINISH_TIMEOUT_MS = 9000;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setExtrasClassLoader(context.getClassLoader());
        ProxyBroadcastRecord record = ProxyBroadcastRecord.create(intent);
        if (record.mIntent == null) {
            return;
        }
        final PendingResult pendingResult = goAsync();
        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                try { pendingResult.finish(); } catch (Throwable ignored) {}
            }
        };
        sHandler.postDelayed(timeout, FINISH_TIMEOUT_MS);
        try {
            BlackBoxCore.getBActivityManager().scheduleBroadcastReceiver(record.mIntent, new PendingResultData(pendingResult), record.mUserId);
        } catch (RemoteException e) {
            sHandler.removeCallbacks(timeout);
            if (done.compareAndSet(false, true)) {
                try { pendingResult.finish(); } catch (Throwable ignored) {}
            }
        }
    }
}