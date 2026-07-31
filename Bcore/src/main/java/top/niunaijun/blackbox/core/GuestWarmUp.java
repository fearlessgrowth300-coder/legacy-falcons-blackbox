package top.niunaijun.blackbox.core;

import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Bring a clone's own push connection back up without showing any UI.
 *
 * <p>Why this exists: every OEM power manager SIGKILLs the whole container process group when its
 * task is removed from Recents — Transsion logs it as
 * {@code Usf_Hiber/appStateHandler: removeTask: reason=remove-task} followed by
 * {@code Zygote: Process NNNN exited due to signal 9}, and Samsung/MIUI/ColorOS have equivalents.
 * That takes the guest's push process with it (Instagram's {@code :fbns}, WhatsApp's socket), so no
 * message can be delivered until something starts the guest again. A foreground
 * {@link top.niunaijun.blackbox.core.system.DaemonService} does NOT survive that kill, and neither
 * the battery whitelist nor an EXEMPTED standby bucket prevents it — all three were verified active
 * on a device that still got killed.
 *
 * <p>The fix cannot live in this app: whatever restarts the guest has to outlive the kill. ShieldProxy
 * is a separate package and uid, so it survives, and it calls this through the bridge from its
 * foreground guard. That is also why this must not need a foreground Activity — the older
 * {@code launchApk} path can only run from the foreground because Android blocks background activity
 * starts, which is precisely what made "revive a killed clone" look impossible before.
 *
 * <p>Push does NOT depend on Google Play Services here. Instagram's {@code :fbns} process is
 * Facebook Notification Service, its own MQTT connection, and WhatsApp likewise keeps its own socket.
 * Those work in-container as long as the process exists, which makes this purely a process-lifetime
 * problem rather than the unsolvable GMS/FCM one.
 */
public class GuestWarmUp {
    private static final String TAG = "GuestWarmUp";

    /**
     * Cap on non-main processes started per clone. Push lives in at most one or two helper processes;
     * starting every process a heavy app declares would cost far more battery and RAM than it buys,
     * which matters because these devices are often 4GB and already memory-starved.
     */
    private static final int MAX_SECONDARY_PROCESSES = 2;

    /**
     * Below this many free proxy slots, warm only the clone's main process and leave its helper
     * processes alone. Warming is speculative work on behalf of a clone nobody is looking at, so it
     * must never be what consumes the last slots -- those belong to whatever the user actually opens
     * next. Reviving push for one clone is worth far less than being able to launch any clone at all.
     */
    private static final int LOW_SLOT_MARK = 6;

    public static class Result {
        /** True when the clone's processes are up (whether this call started them or not). */
        public boolean ok;
        /** True when nothing needed doing because the clone was already alive. */
        public boolean alreadyRunning;
        /** How many processes this call actually started. */
        public int warmed;
        /** User-facing reason the clone could not be warmed, or null. */
        public String err;
    }

    /**
     * Start {@code packageName}'s processes for {@code userId} so its push client can reconnect.
     * Safe to call repeatedly: it no-ops when the clone is already running.
     */
    public static Result warm(int userId, String packageName) {
        Result result = new Result();
        if (userId < 0 || packageName == null || packageName.trim().isEmpty()) {
            result.err = "A BlackBox user and package are required";
            return result;
        }
        if (!BlackBoxCore.get().isInstalled(packageName, userId)) {
            result.err = packageName + " is not cloned in User " + userId;
            return result;
        }
        // Fail closed exactly like launchApk: a clone whose route is unusable must never be started,
        // because the guest proxy is what keeps its traffic off the phone's real IP. Reviving a clone
        // in the background is precisely the case where nobody is watching the screen to notice a leak.
        String routeBlock = BlackBoxCore.get().getLaunchBlockReason(userId);
        if (routeBlock != null) {
            result.err = routeBlock;
            return result;
        }
        if (isRunning(packageName, userId)) {
            result.ok = true;
            result.alreadyRunning = true;
            return result;
        }

        // Check the pool BEFORE starting anything. Slot exhaustion is not a per-clone problem: once
        // the pool is full nothing on the device can start a process, so saying so plainly is the
        // difference between a user closing a few clones and a user believing the app is broken.
        int freeSlots = freeSlots();
        if (freeSlots == 0) {
            result.err = "No free app slots left in the container (all "
                    + ProxyManifest.freeCount() + " are in use). Close some clones and try again.";
            Slog.w(TAG, "warm refused, slot pool exhausted: user=" + userId + " pkg=" + packageName);
            return result;
        }
        // freeSlots < 0 means the container could not be asked; carry on rather than block push.
        boolean mainProcessOnly = freeSlots > 0 && freeSlots <= LOW_SLOT_MARK;

        for (String processName : warmableProcessNames(userId, packageName)) {
            if (mainProcessOnly && !packageName.equals(processName)) {
                Slog.w(TAG, "skipping helper process " + processName + " for User " + userId
                        + ": only " + freeSlots + " slots free");
                break;
            }
            boolean started;
            try {
                started = BlackBoxCore.getBActivityManager()
                        .prewarmProcess(packageName, processName, userId);
            } catch (Throwable error) {
                Slog.w(TAG, "Could not warm " + processName + " for User " + userId + ": "
                        + error.getClass().getSimpleName());
                break;
            }
            if (started) {
                result.warmed++;
                continue;
            }
            // prewarmProcess kills the whole package when bindApplication throws, so a failure here
            // may have torn down what earlier iterations started. Stop and report the real state
            // below rather than pressing on against a package that is being killed.
            Slog.w(TAG, "Warm-up refused for " + packageName + ":" + processName
                    + " in User " + userId);
            break;
        }

        result.ok = isRunning(packageName, userId);
        if (!result.ok && result.err == null) {
            // Distinguish the two ways this ends. A pool that emptied while we were starting
            // processes is a device-wide capacity problem the user can act on; anything else is a
            // genuine per-clone failure. Reporting both as "could not start" is what made a full
            // container look like a broken push feature.
            result.err = freeSlots() == 0
                    ? "No free app slots left in the container (all " + ProxyManifest.freeCount()
                        + " are in use). Close some clones and try again."
                    : "Could not start " + packageName + " for User " + userId;
        }
        Slog.d(TAG, "warm user=" + userId + " pkg=" + packageName
                + " started=" + result.warmed + " ok=" + result.ok);
        return result;
    }

    /** Free proxy process slots, or -1 when the container could not be asked. */
    private static int freeSlots() {
        try {
            return BlackBoxCore.getBActivityManager().freeProcessSlots();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isRunning(String packageName, int userId) {
        try {
            return BlackBoxCore.getBActivityManager().isAppProcessRunning(packageName, userId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The clone's main process, followed by the distinct processes its declared services run in.
     * Push clients are services, so their process is where the connection is established — for
     * Instagram that is {@code com.instagram.android:fbns}. Reading it from the guest manifest keeps
     * this generic instead of hardcoding per-app process names.
     */
    private static List<String> warmableProcessNames(int userId, String packageName) {
        Set<String> names = new LinkedHashSet<>();
        names.add(packageName);
        try {
            PackageInfo info = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, android.content.pm.PackageManager.GET_SERVICES, userId);
            if (info != null && info.services != null) {
                for (ServiceInfo service : info.services) {
                    if (service == null || service.processName == null) continue;
                    if (names.size() > MAX_SECONDARY_PROCESSES) break;
                    names.add(service.processName);
                }
            }
        } catch (Throwable error) {
            Slog.w(TAG, "Could not read services of " + packageName + ": "
                    + error.getClass().getSimpleName());
        }
        return new ArrayList<>(names);
    }
}
