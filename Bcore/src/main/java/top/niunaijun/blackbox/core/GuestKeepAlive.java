package top.niunaijun.blackbox.core;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.DaemonService;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keep clones receiving push in the background.
 *
 * The honest mechanism on a NO-ROOT device: (1) the container runs a foreground {@link DaemonService}
 * so Android keeps the container process group alive, and (2) each kept-alive clone gets Google Play
 * Services installed (see {@link GmsCore}) so Instagram/etc. can be woken by FCM even after their own
 * process is killed — FCM is the only reliable "wake a dead app" path without root. This class just
 * records which users opted in, makes sure the daemon is up, and a light watchdog re-ensures GMS is
 * present. It does NOT re-launch apps to the foreground (that would hijack the screen).
 */
public class GuestKeepAlive {
    private static final String TAG = "GuestKeepAlive";
    private static final Set<Integer> sUsers = ConcurrentHashMap.newKeySet();
    private static volatile boolean sStarted = false;
    private static Handler sHandler;
    private static final long INTERVAL = 60 * 1000L; // re-ensure the foreground daemon every minute

    private static File file() {
        File dir = BEnvironment.getSystemDir();
        if (dir != null && !dir.exists()) dir.mkdirs();
        return new File(dir, "keepalive.txt");
    }

    /** Load persisted opt-ins and start the daemon + watchdog. Call from the server process. */
    public static synchronized void start() {
        if (!BlackBoxCore.get().isServerProcess()) {
            // Connecting to the activity manager starts the BlackBox server, whose own
            // initialization calls this method again in the correct process.
            try { BlackBoxCore.getBActivityManager().isAppProcessRunning(GmsCore.GMS_PKG, 0); }
            catch (Throwable ignored) {}
            ensureDaemon();
            return;
        }
        if (sStarted) return;
        sStarted = true;
        reload();
        ensureDaemon();
        scheduleWatchdog();
        Slog.d(TAG, "started, kept-alive users=" + sUsers);
    }

    /** Re-read the persisted set (opt-ins are written from the host process; the watchdog runs in
     *  the server process, so reload each tick to pick up cross-process changes). */
    private static synchronized void reload() {
        try {
            sUsers.clear();
            String s = file().exists() ? FileUtils.readToString(file().getAbsolutePath()) : null;
            if (s != null) {
                for (String part : s.split(",")) {
                    part = part.trim();
                    if (!part.isEmpty()) try { sUsers.add(Integer.parseInt(part)); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Turn background keep-alive on/off for a User.
     *  Keep-alive is ONLY the foreground {@link DaemonService} that keeps the container process
     *  group alive so a running clone (or its own push socket, e.g. WhatsApp) can still deliver
     *  notifications. GMS is deliberately NOT installed or started here — it does not actually run
     *  in a no-root container, delivers no FCM push, and its per-minute install/spawn only wasted
     *  battery and froze the main thread ("BlackBox isn't responding"). */
    public static synchronized boolean setEnabled(int userId, boolean on) {
        if (on) {
            sUsers.add(userId);
            ensureDaemon();
        } else {
            sUsers.remove(userId);
        }
        persist();
        Slog.d(TAG, "setEnabled user " + userId + " = " + on);
        return true;
    }

    public static boolean isEnabled(int userId) {
        return sUsers.contains(userId);
    }

    private static void persist() {
        StringBuilder sb = new StringBuilder();
        for (int u : sUsers) { if (sb.length() > 0) sb.append(","); sb.append(u); }
        AtomicFile atomic = new AtomicFile(file());
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            atomic.finishWrite(out);
        } catch (Throwable error) {
            if (out != null) atomic.failWrite(out);
            Slog.w(TAG, "persist failed: " + error.getClass().getSimpleName());
        }
    }

    private static void ensureDaemon() {
        try {
            Intent i = new Intent(BlackBoxCore.getContext(), DaemonService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                BlackBoxCore.getContext().startForegroundService(i);
            } else {
                BlackBoxCore.getContext().startService(i);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "ensureDaemon failed: " + t.getMessage());
        }
    }

    /** Watchdog handler backed by a dedicated background thread. Re-ensuring the foreground service
     *  is a blocking Context/binder call and MUST NOT run on the server process main thread, or it
     *  can ANR the container ("BlackBox isn't responding") when the timer fires. */
    private static synchronized Handler bgHandler() {
        if (sHandler == null) {
            HandlerThread thread = new HandlerThread("BlackBox-KeepAlive");
            thread.start();
            sHandler = new Handler(thread.getLooper());
        }
        return sHandler;
    }

    private static void scheduleWatchdog() {
        Handler handler = bgHandler();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    reload();
                    ensureDaemon();
                } catch (Throwable ignored) {}
                bgHandler().postDelayed(this, INTERVAL);
            }
        }, INTERVAL);
    }
}
