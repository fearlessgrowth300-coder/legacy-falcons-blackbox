package top.niunaijun.blackbox.proxy;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;

/** Dispatches service PendingIntents back into the correct virtual user. */
public final class ProxyPendingService extends Service {
    private static final String CHANNEL = "blackbox_pending_service";
    private static final int ID = 0x42585053;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ProxyPendingRecord record = ProxyPendingRecord.create(intent);
        if (record.mRequireForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Background delivery", NotificationManager.IMPORTANCE_MIN));
            android.app.Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("BlackBox")
                    .setContentText("Delivering a background event")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build();
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(ID, notification);
                }
            } catch (Throwable error) {
                // Android 14+ can reject a missing type. Delivery still has to run.
                android.util.Log.w("ProxyPendingService",
                        "Foreground promotion skipped: " + error.getClass().getSimpleName());
            }
        }
        if (record.mTarget != null) {
            BlackBoxCore.getBActivityManager().startService(record.mTarget, null,
                    record.mRequireForeground, record.mUserId);
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
