package top.niunaijun.blackbox.proxy;

import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;


public class ProxyManifest {
    /** Max is 50 (classes P0..P49). The ACTIVE pool is per-variant (see VariantConfig) — the
     *  flavor manifest declares only this many stubs, so different variants expose a different
     *  proxy-stub component count. */
    public static final int FREE_COUNT = 50;

    /** Per-variant active stub pool size. Never exceeds the stubs the flavor manifest declares. */
    public static int freeCount() {
        return top.niunaijun.blackbox.core.VariantConfig.stubPoolSize();
    }

    public static boolean isProxy(String msg) {
        return getBindProvider().equals(msg) || msg.contains("proxy_content_provider_");
    }

    public static String getBindProvider() {
        return BlackBoxCore.getHostPkg() + ".blackbox.SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.CHINA, "%s.proxy_content_provider_%d", BlackBoxCore.getHostPkg(), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.CHINA, "top.niunaijun.blackbox.proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.CHINA, "top.niunaijun.blackbox.proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.CHINA, "top.niunaijun.blackbox.proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.CHINA, "top.niunaijun.blackbox.proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.CHINA, "top.niunaijun.blackbox.proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return BlackBoxCore.getHostPkg() + ".blackbox.FileProvider";
    }

    public static String getProxyReceiver() {
        return BlackBoxCore.getHostPkg() + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return BlackBoxCore.getHostPkg() + ":" + procToken() + bPid;
    }

    /**
     * Per-variant stub-process token (default "p"). Read from a Bcore string resource that each
     * build flavor overrides, so different app variants don't all present the same "<pkg>:p<n>"
     * process-name signature. Falls back to "p" if resources aren't ready yet.
     */
    private static volatile String sProcToken;

    public static String procToken() {
        String t = sProcToken;
        if (t != null) return t;
        try {
            t = BlackBoxCore.getContext().getString(
                    top.niunaijun.blackbox.R.string.black_box_proc_token);
        } catch (Throwable ignored) {
            t = "p";
        }
        if (t == null || t.isEmpty()) t = "p";
        sProcToken = t;
        return t;
    }
}
