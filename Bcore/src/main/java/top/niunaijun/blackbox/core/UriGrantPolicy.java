package top.niunaijun.blackbox.core;

/** Scopes the old blanket URI grant. Unrecognized URIs are deferred to the real check. */
public final class UriGrantPolicy {
    public static final int GRANTED = 0;
    public static final int DENIED = -1;
    public static final int DEFER = 1;

    private UriGrantPolicy() {}

    public static int decide(String uri, int userId, boolean shareHostMedia, boolean shareHostGoogle) {
        if (userId < 0) return DENIED;
        if (uri == null || uri.isEmpty()) return DEFER;
        String value = uri.toLowerCase(java.util.Locale.US);
        if (value.contains("content://media") || value.contains("com.android.providers.media")) {
            return shareHostMedia ? GRANTED : DENIED;
        }
        if (value.contains("com.google.android.gsf")
                || value.contains("com.google.android.gms")
                || value.contains("com.android.vending")) {
            return shareHostGoogle ? GRANTED : DENIED;
        }
        int marker = uri.indexOf("/blackbox/");
        if (marker >= 0) {
            String path = uri.substring(marker);
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            path = path.replace("%2F", "/").replace("%2f", "/");
            PathIsolation.Decision decision = PathIsolation.decide(path, null, null, userId);
            if (decision.denied) return DENIED;
        }
        return GRANTED;
    }
}
