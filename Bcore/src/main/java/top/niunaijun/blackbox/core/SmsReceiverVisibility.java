package top.niunaijun.blackbox.core;

/**
 * Which phone packages a clone may see for SMS/telephony broadcast queries.
 * A cloned app and any other user-installed app stay hidden. The visibility
 * guard result is passed in so this check cannot grow its own allowlist.
 */
public final class SmsReceiverVisibility {
    private SmsReceiverVisibility() {}

    public static boolean isPlatformTelephony(String packageName) {
        return "com.android.phone".equals(packageName)
                || "com.android.mms".equals(packageName)
                || "com.android.messaging".equals(packageName)
                || "com.android.providers.telephony".equals(packageName)
                || "com.android.server.telecom".equals(packageName);
    }

    /** {@code guardAllows} is the existing package-visibility guard, or platform telephony. */
    public static boolean guardAllows(String packageName, boolean openPackage) {
        return openPackage || isPlatformTelephony(packageName);
    }

    public static boolean visible(String packageName, boolean guardAllows,
                                  boolean defaultSmsHolder, boolean installedAsClone) {
        if (packageName == null || packageName.isEmpty() || installedAsClone) return false;
        return defaultSmsHolder || guardAllows;
    }

    /** The role holder is still returned when the phone's receiver query is empty. */
    public static boolean includeHolder(String holder, boolean alreadyPresent, boolean installedAsClone) {
        return holder != null && !holder.isEmpty() && !alreadyPresent && !installedAsClone;
    }

    /** SMS lookups must use this process's real user, not the clone's virtual user. */
    public static int hostQueryUser(int processUserId, int requestedUserId) {
        return processUserId;
    }

    public static Object[] roleCallArguments(Class<?>[] types, String hostPackage, int userId) {
        Object[] params = types == null ? new Object[0] : new Object[types.length];
        boolean roleSet = false;
        for (int i = 0; types != null && i < types.length; i++) {
            if (types[i] == String.class && !roleSet) {
                params[i] = "android.app.role.SMS";
                roleSet = true;
            } else if (types[i] == String.class) {
                params[i] = hostPackage;
            } else if (types[i] == int.class) {
                params[i] = userId;
            }
        }
        return params;
    }
}
