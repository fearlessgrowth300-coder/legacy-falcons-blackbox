package top.niunaijun.blackbox.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Caller identity stored inside a Parcelable. {@code EditorInfo.packageName}
 * is one. Package lookups and job descriptions are left alone.
 */
public final class CallerIdentityParcels {
    private CallerIdentityParcels() {}

    public static int rewrite(Object[] args, String guestPackage, String hostPackage) {
        if (args == null) return 0;
        int replaced = 0;
        for (Object arg : args) {
            replaced += rewriteObject(arg, guestPackage, hostPackage);
        }
        return replaced;
    }

    public static int rewriteObject(Object value, String guestPackage, String hostPackage) {
        if (value == null || skipClassName(value.getClass().getName())) return 0;
        int replaced = 0;
        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !isCallerPackageField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object current = field.get(value);
                    if (current instanceof String) {
                        String visible = SystemCallerIdentity.packageForSystem(
                                (String) current, guestPackage, hostPackage);
                        if (!current.equals(visible)) {
                            field.set(value, visible);
                            replaced++;
                        }
                    } else if (current instanceof CharSequence
                            && guestPackage != null
                            && guestPackage.equals(current.toString())
                            && hostPackage != null
                            && !guestPackage.equals(hostPackage)) {
                        field.set(value, hostPackage);
                        replaced++;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return replaced;
    }

    public static boolean isCallerPackageField(String name) {
        return "packageName".equals(name)
                || "mPackageName".equals(name)
                || "opPackageName".equals(name)
                || "mOpPackageName".equals(name)
                || "callingPackage".equals(name)
                || "mCallingPackage".equals(name);
    }

    public static boolean skipClassName(String name) {
        if (name == null) return true;
        return name.equals("android.content.Intent")
                || name.equals("android.content.ComponentName")
                || name.equals("android.app.job.JobInfo")
                || name.equals("android.app.PendingIntent")
                || name.startsWith("android.content.pm.");
    }
}
