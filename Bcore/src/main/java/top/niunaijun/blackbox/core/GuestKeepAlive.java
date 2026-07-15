package top.niunaijun.blackbox.core;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
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
    private static final long INTERVAL = 5 * 60 * 1000L; // re-ensure GMS every 5 min

    private static File file() {
        File dir = BEnvironment.getSystemDir();
        if (dir != null && !dir.exists()) dir.mkdirs();
        return new File(dir, "keepalive.txt");
    }

    /** Load persisted opt-ins and start the daemon + watchdog. Call from the server process. */
    public static synchronized void start() {
        if (sStarted) return;
        sStarted = true;
        reload();
        ensureDaemon();
        scheduleWatchdog();
        Slog.d(TAG, "started, kept-alive users=" + sUsers);
    }

    /** Re-read the persisted set (opt-ins are written from the host process; the watchdog runs in
     *  the server process, so reload each tick to pick up cross-process changes). */
    private static void reload() {
        try {
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
    public static void setEnabled(int userId, boolean on) {
        if (on) sUsers.add(userId); else sUsers.remove(userId);
        persist();
        if (on) ensureDaemon();
        Slog.d(TAG, "setEnabled user " + userId + " = " + on);
    }

    public static boolean isEnabled(int userId) {
        return sUsers.contains(userId);
    }

    private static void persist() {
        StringBuilder sb = new StringBuilder();
        for (int u : sUsers) { if (sb.length() > 0) sb.append(","); sb.append(u); }
        try { FileUtils.writeToFile(sb.toString().getBytes(), file()); } catch (Throwable ignored) {}
    }

    private static void ensureGms(int userId) {
        try {
            if (GmsCore.isSupportGms() && !GmsCore.isInstalledGoogleService(userId)) {
                Slog.d(TAG, "installing GMS into user " + userId + " for push");
                GmsCore.installGApps(userId);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "ensureGms failed: " + t.getMessage());
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
                    ensureDaemon();
                } catch (Throwable ignored) {}
                sHandler.postDelayed(this, INTERVAL);
            }
        }, INTERVAL);
    }
}
