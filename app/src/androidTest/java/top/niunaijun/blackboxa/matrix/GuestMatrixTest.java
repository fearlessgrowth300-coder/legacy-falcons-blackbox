package top.niunaijun.blackboxa.matrix;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.ProfilePolicy;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.core.system.user.BUserInfo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives two lightweight guests inside one virtual user for about three minutes,
 * then stops and relaunches them. The workflow shell reads logcat for heartbeats,
 * FATAL exceptions, ANRs and tombstones.
 */
@RunWith(AndroidJUnit4.class)
public class GuestMatrixTest {
    private static final String TAG = "MATRIX";
    private static final int USER = 7;
    private static final String WEB = "top.niunaijun.blackbox.guest.webview";
    private static final String FOREGROUND = "top.niunaijun.blackbox.guest.foreground";
    private static final long SOAK_MS = 180_000L;

    @Test(timeout = 480_000L)
    public void guestsStayAliveThenRestart() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BlackBoxCore core = BlackBoxCore.get();
        assertTrue("virtual services did not start", core.waitForServicesAvailable(60_000L));
        BUserInfo user = core.createUser(USER);
        assertNotNull("createUser returned null", user);
        Log.i(TAG, "user-created id=" + USER);
        // Production profiles refuse a guest with no proxy. This soak has no upstream
        // node, so the virtual user explicitly allows direct network. That does not
        // change the default, which stays fail-closed.
        File policyFile = ProfilePolicy.fileFor(BEnvironment.getUserDir(USER));
        assertTrue("could not store soak profile policy",
                ProfilePolicy.store(policyFile, new ProfilePolicy(false, false, false)));
        Log.i(TAG, "profile requireProxy=false");

        File webApk = copyAsset("guest-webview.apk");
        File foregroundApk = copyAsset("guest-foreground.apk");
        install(core, webApk, WEB);
        install(core, foregroundApk, FOREGROUND);
        assertTrue(WEB + " missing after install", core.isInstalled(WEB, USER));
        assertTrue(FOREGROUND + " missing after install", core.isInstalled(FOREGROUND, USER));

        assertTrue("webview launch failed " + core.getLaunchBlockReason(USER), core.launchApk(WEB, USER));
        assertTrue("foreground launch failed " + core.getLaunchBlockReason(USER), core.launchApk(FOREGROUND, USER));
        SystemClock.sleep(8_000L);
        assertFalse("no proxy process after launch", proxyPids(target).isEmpty());
        Log.i(TAG, "soak-begin pids=" + proxyPids(target));

        long end = SystemClock.elapsedRealtime() + SOAK_MS;
        while (SystemClock.elapsedRealtime() < end) {
            interact(core);
            Set<Integer> pids = proxyPids(target);
            Log.i(TAG, "soak-sample pids=" + pids);
            assertFalse("guest proxy process disappeared during soak", pids.isEmpty());
            long remaining = end - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            SystemClock.sleep(Math.min(15_000L, remaining));
        }

        Set<Integer> beforeStop = proxyPids(target);
        Log.i(TAG, "restart-begin pids=" + beforeStop);
        core.stopPackage(WEB, USER);
        core.stopPackage(FOREGROUND, USER);
        SystemClock.sleep(3_000L);
        Set<Integer> afterStop = proxyPids(target);
        Log.i(TAG, "stopped pids=" + afterStop);
        assertTrue("stopPackage left the previous guest pid running: " + afterStop,
                disjoint(beforeStop, afterStop));

        assertTrue("webview relaunch failed", core.launchApk(WEB, USER));
        assertTrue("foreground relaunch failed", core.launchApk(FOREGROUND, USER));
        // The guest's first heartbeat is posted 10s after onCreate.
        SystemClock.sleep(15_000L);
        Set<Integer> restarted = proxyPids(target);
        Log.i(TAG, "restart-end pids=" + restarted);
        assertFalse("guest did not return after relaunch", restarted.isEmpty());
    }

    private static void install(BlackBoxCore core, File apk, String expectedPackage) {
        InstallResult result = core.installPackageAsUser(apk, USER);
        assertNotNull(expectedPackage + " install returned null", result);
        assertTrue(expectedPackage + " install failed: " + result.msg, result.success);
        Log.i(TAG, "installed " + expectedPackage);
    }

    private static void interact(BlackBoxCore core) {
        try {
            core.launchApk(WEB, USER);
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(2_000L);
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
            core.launchApk(FOREGROUND, USER);
            device.waitForIdle(2_000L);
            device.click(device.getDisplayWidth() / 2, Math.max(1, device.getDisplayHeight() / 3));
        } catch (Throwable error) {
            Log.w(TAG, "interaction attempt failed: " + error.getClass().getSimpleName());
        }
    }

    private static boolean disjoint(Set<Integer> before, Set<Integer> after) {
        for (Integer pid : before) {
            if (after.contains(pid)) return false;
        }
        return true;
    }

    private static Set<Integer> proxyPids(Context target) {
        Set<Integer> pids = new HashSet<>();
        String prefix = target.getPackageName() + ":p";
        String dump = shell("ps -A");
        if (dump.isEmpty()) dump = shell("ps");
        for (String line : dump.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2 || !parts[parts.length - 1].startsWith(prefix)) continue;
            try {
                pids.add(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return pids;
    }

    private static String shell(String command) {
        StringBuilder out = new StringBuilder();
        try {
            ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(fd), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "shell " + command + " failed: " + error.getClass().getSimpleName());
        }
        return out.toString();
    }

    private static File copyAsset(String name) throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File out = new File(target.getCacheDir(), name);
        try (InputStream in = test.getAssets().open(name);
             OutputStream output = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
        return out;
    }
}
