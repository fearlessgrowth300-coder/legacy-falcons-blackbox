package top.niunaijun.blackbox.utils.compat;

import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;

import java.lang.reflect.Method;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;

public class TaskDescriptionCompat {
    /**
     * Label and colour only. {@code Bitmap.asShared} SIGSEGVs on API 33+ when the
     * icon is hardware, recycled, or has no native buffer, including the bitmap
     * built for the recents tile. That abort cannot be caught, so the published
     * description never carries an icon.
     */
    public static ActivityManager.TaskDescription fix(ActivityManager.TaskDescription td) {
        String label = null;
        int color = 0;
        if (td != null) {
            try {
                label = td.getLabel();
            } catch (Throwable ignored) {
            }
            try {
                color = td.getPrimaryColor();
            } catch (Throwable ignored) {
            }
        }
        if (label == null) {
            label = getTaskDescriptionLabel(BlackBoxCore.getUserId(), getApplicationLabel());
        }
        return withoutIcon(label, color);
    }

    public static ActivityManager.TaskDescription withoutIcon(String label, int color) {
        try {
            return new ActivityManager.TaskDescription(label, (Bitmap) null, color);
        } catch (Throwable ignored) {
        }
        try {
            return new ActivityManager.TaskDescription(label);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Replace every task description in the binder call, then invoke.
     * A parcel or bitmap failure returns without killing the guest.
     */
    public static Object publish(Object who, Method method, Object[] args) {
        try {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof ActivityManager.TaskDescription) {
                        ActivityManager.TaskDescription stripped =
                                fix((ActivityManager.TaskDescription) args[i]);
                        if (stripped == null) return null;
                        args[i] = stripped;
                    }
                }
            }
            return method.invoke(who, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getTaskDescriptionLabel(int userId, CharSequence label) {
        return String.format(Locale.CHINA, "[B%d]%s", userId, label);
    }

    private static CharSequence getApplicationLabel() {
        try {
            PackageManager pm = BlackBoxCore.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(BlackBoxCore.getAppPackageName(), 0));
        } catch (Throwable e) {
            return "";
        }
    }
}
