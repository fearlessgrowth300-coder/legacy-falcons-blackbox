package top.niunaijun.blackbox.core;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
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
    private static final Set<Integer> sGmsBootstrapped = ConcurrentHashMap.newKeySet();
    private static volatile boolean sStarted = false;
    private static Handler sHandler;
    private static final long INTERVAL = 60 * 1000L; // re-ensure routed push service every minute
    private static final String GMS_PERSISTENT_PROCESS = GmsCore.GMS_PKG + ".persistent";
    private static final String GMS_PUSH_SERVICE = "com.google.android.gms.gcm.GcmService";

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
        boolean changed = false;
        for (int userId : new java.util.HashSet<>(sUsers)) {
            GuestProxy.GmsRouteStatus route = GuestProxy.syncGmsRouteForUser(userId);
            if (route == GuestProxy.GmsRouteStatus.CONFLICT || route == GuestProxy.GmsRouteStatus.INVALID) {
                sUsers.remove(userId);
                changed = true;
            }
        }
        if (changed) persist();
        ensureDaemon();
        scheduleWatchdog();
        sHandler.postDelayed(() -> new Thread(() -> {
            for (int userId : new java.util.HashSet<>(sUsers)) ensureGms(userId);
        }, "BlackBox-GMS-restore").start(), 2_000L);
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

    /** Turn background push keep-alive on/off for a User.
     *  NOTE: GMS is intentionally NOT installed — it doesn't actually run in a no-root container
     *  and only floods "FirebaseInstanceId: Failed to find package com.google.android.gms" and
     *  hangs jobs (ANR). Keep-alive is just the foreground daemon now. */
    public static synchronized boolean setEnabled(int userId, boolean on) {
        if (on) {
            GuestProxy.GmsRouteStatus route = GuestProxy.syncGmsRouteForUser(userId);
            if (route == GuestProxy.GmsRouteStatus.CONFLICT || route == GuestProxy.GmsRouteStatus.INVALID) {
                sUsers.remove(userId);
                persist();
                Slog.w(TAG, "refusing keep-alive for user with conflicting or invalid routes: " + userId);
                return false;
            }
            if (GmsCore.isSupportGms() && !GmsCore.isInstalledGoogleService(userId)) {
                if (!GmsCore.installGApps(userId).success) return false;
            }
            route = GuestProxy.syncGmsRouteForUser(userId);
            if (route == GuestProxy.GmsRouteStatus.CONFLICT || route == GuestProxy.GmsRouteStatus.INVALID) return false;
            if (route == GuestProxy.GmsRouteStatus.READY && !startGmsProcess(userId)) return false;
            sUsers.add(userId);
        } else {
            sUsers.remove(userId);
            sGmsBootstrapped.remove(userId);
        }
        persist();
        if (on) ensureDaemon();
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

    private static void ensureGms(int userId) {
        try {
            GuestProxy.GmsRouteStatus route = GuestProxy.syncGmsRouteForUser(userId);
            if (route == GuestProxy.GmsRouteStatus.CONFLICT || route == GuestProxy.GmsRouteStatus.INVALID) {
                sUsers.remove(userId);
                persist();
                return;
            }
            if (GmsCore.isSupportGms() && !GmsCore.isInstalledGoogleService(userId)) {
                Slog.d(TAG, "installing GMS into user " + userId + " for push");
                if (!GmsCore.installGApps(userId).success) return;
            }
            route = GuestProxy.syncGmsRouteForUser(userId);
            if (route == GuestProxy.GmsRouteStatus.READY) startGmsProcess(userId);
        } catch (Throwable t) {
            Slog.w(TAG, "ensureGms failed: " + t.getClass().getSimpleName());
        }
    }

    /** Keep the routed GMS persistent process present and deliver its boot receiver once per
     * BlackBox server lifetime. This is the process that receives FCM for closed guest apps. */
    private static boolean startGmsProcess(int userId) {
        try {
            if (!GmsCore.isInstalledGoogleService(userId)) return false;
            if (BlackBoxCore.getBActivityManager().initProcess(GmsCore.GMS_PKG,
                    GMS_PERSISTENT_PROCESS, userId) == null) {
                return false;
            }
            if (sGmsBootstrapped.add(userId)) {
                Intent boot = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(GmsCore.GMS_PKG);
                BlackBoxCore.getBActivityManager().sendBroadcast(boot, null, userId);
            }
            // initProcess only attaches the guest runtime. Without a live component Android may
            // reclaim the empty proxy process immediately, which drops FCM for closed clones.
            Intent push = new Intent().setComponent(
                    new ComponentName(GmsCore.GMS_PKG, GMS_PUSH_SERVICE));
            BlackBoxCore.getBActivityManager().startService(push, null, false, userId);
            return true;
        } catch (Throwable error) {
            Slog.w(TAG, "GMS process start failed: " + error.getClass().getSimpleName());
            return false;
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

    private static void scheduleWatchdog() {
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        sHandler.removeCallbacksAndMessages(null);
        sHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    reload();
                    for (int userId : sUsers) ensureGms(userId);
                    ensureDaemon();
                } catch (Throwable ignored) {}
                sHandler.postDelayed(this, INTERVAL);
            }
        }, INTERVAL);
    }
}
