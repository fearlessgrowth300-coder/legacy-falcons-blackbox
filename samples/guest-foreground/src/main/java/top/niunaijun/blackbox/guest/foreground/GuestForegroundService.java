package top.niunaijun.blackbox.guest.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Posts an immutable PendingIntent notification and refreshes it on a 10s heartbeat. */
public class GuestForegroundService extends Service {
    public static final String TAG = "GuestForeground";
    private static final String CHANNEL = "guest-foreground";
    private static final int ID = 42;
    private int beats;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            beats++;
            Log.i(TAG, "heartbeat " + beats);
            promote();
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Guest foreground", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        handler.removeCallbacks(beat);
        handler.postDelayed(beat, 10_000L);
        Log.i(TAG, "created");
        return START_STICKY;
    }

    private void promote() {
        Intent open = new Intent(this, GuestActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, open, piFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("GuestForeground")
                .setContentText("heartbeat " + beats)
                .setContentIntent(content)
                .setOngoing(true);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, notification);
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(beat);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
