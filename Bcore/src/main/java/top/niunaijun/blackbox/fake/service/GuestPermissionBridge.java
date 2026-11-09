package top.niunaijun.blackbox.fake.service;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.GuestPermission;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.utils.Slog;

/** Resolves a virtual uid's install-time permission inside a guest process. */
public final class GuestPermissionBridge {
    private static final String TAG = "GuestPermission";

    private GuestPermissionBridge() {}

    /** {@link PackageManager#PERMISSION_GRANTED}, denied is not forced; null defers. */
    public static Integer grantedOrNull(String permission, int uid) {
        if (permission == null || uid < BUserHandle.AID_APP_START) return null;
        try {
            int userId = BUserHandle.getUserId(uid);
            int appId = BUserHandle.getAppId(uid);
            List<ApplicationInfo> installed = BlackBoxCore.getBPackageManager()
                    .getInstalledApplications(0, userId);
            if (installed == null) return null;
            boolean requested = false;
            String caller = null;
            for (ApplicationInfo info : installed) {
                if (info == null || info.packageName == null) continue;
                if (info.uid != appId && info.uid != uid) continue;
                caller = info.packageName;
                if (requests(info.packageName, userId, permission)) {
                    requested = true;
                    break;
                }
            }
            if (caller == null || !requested) return null;
            PermissionInfo defined = define(permission);
            if (defined == null) return null;
            boolean same = caller.equals(defined.packageName)
                    || GuestPermission.sameSigner(certs(caller), certs(defined.packageName));
            if (GuestPermission.decide(true, defined.protectionLevel, same)
                    != GuestPermission.Answer.GRANT) {
                return null;
            }
            Slog.i(TAG, "grant " + permission + " uid=" + uid + " pkg=" + caller);
            return PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            Slog.w(TAG, "lookup failed for " + permission + ": " + error.getClass().getSimpleName());
            return null;
        }
    }

    /** Package-name form used by PackageManager.checkPermission. */
    public static Integer grantedForPackageOrNull(String permission, String packageName) {
        if (permission == null || packageName == null) return null;
        try {
            int userId = BActivityThread.getUserId();
            if (!requests(packageName, userId, permission)) return null;
            PermissionInfo defined = define(permission);
            if (defined == null) return null;
            boolean same = packageName.equals(defined.packageName)
                    || GuestPermission.sameSigner(certs(packageName), certs(defined.packageName));
            if (GuestPermission.decide(true, defined.protectionLevel, same)
                    != GuestPermission.Answer.GRANT) {
                return null;
            }
            Slog.i(TAG, "grant " + permission + " pkg=" + packageName);
            return PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean requests(String packageName, int userId, String permission) {
        String[] names = requested(packageName, userId);
        if (names == null) return false;
        for (String name : names) {
            if (permission.equals(name)) return true;
        }
        return false;
    }

    private static String[] requested(String packageName, int userId) {
        try {
            PackageInfo virtual = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
            if (virtual != null && virtual.requestedPermissions != null) {
                return virtual.requestedPermissions;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo host = BlackBoxCore.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS);
            return host == null ? null : host.requestedPermissions;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PermissionInfo define(String permission) {
        try {
            return BlackBoxCore.getPackageManager().getPermissionInfo(permission, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[][] certs(String packageName) {
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = BlackBoxCore.getPackageManager().getPackageInfo(packageName, flags);
            List<byte[]> out = new ArrayList<>();
            if (info != null && Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                add(out, info.signingInfo.getApkContentsSigners());
                if (info.signingInfo.hasPastSigningCertificates()) {
                    add(out, info.signingInfo.getSigningCertificateHistory());
                }
            }
            if (info != null) add(out, info.signatures);
            byte[][] certs = new byte[out.size()][];
            for (int i = 0; i < out.size(); i++) certs[i] = out.get(i);
            return certs;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void add(List<byte[]> out, Signature[] signatures) {
        if (signatures == null) return;
        for (Signature signature : signatures) {
            if (signature != null) out.add(signature.toByteArray());
        }
    }
}
