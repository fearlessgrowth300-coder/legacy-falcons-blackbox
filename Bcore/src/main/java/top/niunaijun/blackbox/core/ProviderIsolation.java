package top.niunaijun.blackbox.core;

/**
 * Host content providers a guest is allowed to see.
 * Settings stays on the host so the per-profile android_id rewrite can run.
 * Media and Google follow the profile switches. Isolated Google providers are
 * required for separate GSF ids and will break host-backed sign-in and push
 * until {@code shareHostGoogleProviders} is turned on.
 */
public final class ProviderIsolation {
    private ProviderIsolation() {}

    public static boolean useHostProvider(String authority, ProfilePolicy policy) {
        if (authority == null || policy == null) return false;
        if ("settings".equals(authority) || "telephony".equals(authority)) return true;
        if (authority.contains("launcher.settings")) return true;
        if ("media".equals(authority) || authority.contains("providers.media")) {
            return policy.shareHostMedia;
        }
        if (authority.contains("com.google.android.gms")
                || authority.contains("com.google.android.gsf")
                || authority.contains("com.android.vending")) {
            return policy.shareHostGoogleProviders;
        }
        return false;
    }
}
