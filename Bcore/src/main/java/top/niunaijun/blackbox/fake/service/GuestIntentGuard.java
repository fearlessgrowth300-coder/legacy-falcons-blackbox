package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.HostLaunchGuard;

/** True when handing this intent to the phone would start a cloned app's real copy. */
public final class GuestIntentGuard {
    private GuestIntentGuard() {}

    public static boolean refuse(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        String target = HostLaunchGuard.targetPackage(intent.getPackage(),
                component == null ? null : component.getPackageName());
        if (target == null) return false;
        boolean installed = false;
        try {
            installed = BlackBoxCore.getBPackageManager().isInstalled(target, BActivityThread.getUserId());
        } catch (Throwable ignored) {
        }
        return HostLaunchGuard.blockHostCopy(target, BlackBoxCore.getHostPkg(), installed);
    }
}
