package top.niunaijun.blackbox.utils.compat;

/**
 * Recognises a Settings provider {@code call} for {@code secure/android_id}.
 *
 * <p>The provider arguments move around across API levels (calling package, attribution
 * source, authority), but the method name and the setting name stay strings. Matching
 * those avoids a Pine hook on {@code Settings.Secure.getString}, which native-crashes
 * on Android 16.
 */
public final class SettingsIdentity {
    private SettingsIdentity() {}

    public static boolean isSecureAndroidIdCall(Object[] args) {
        if (args == null) return false;
        boolean secureGet = false;
        boolean androidId = false;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = (String) arg;
            if ("GET_secure".equals(value)) secureGet = true;
            else if ("android_id".equals(value) || "ANDROID_ID".equals(value)) androidId = true;
        }
        return secureGet && androidId;
    }
}
