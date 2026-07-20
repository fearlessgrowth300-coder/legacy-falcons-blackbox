package top.niunaijun.blackbox.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONObject;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Reads the proxy assigned to a BlackBox User and applies it to the CURRENT
 * guest process via the native connect() redirect (NativeCore.setProxy). This is
 * how each User/clone routes through its OWN proxy — the assignment is keyed on
 * userId, which is only known inside the guest process.
 *
 * Config file: {userDir}/proxy.json
 *   { "enabled": true, "type": "http"|"socks5", "server": "...", "port": 5000,
 *     "username": "...", "password": "..." }
 */
public class GuestProxy {
    private static final String TAG = "GuestProxy";
    private static final String PACKAGE_PATTERN = "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+";
    private static final Set<String> GMS_ROUTE_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.gsf.login",
            "com.android.vending", "com.google.android.backuptransport", "com.google.android.backup",
            "com.google.android.configupdater", "com.google.android.syncadapters.contacts",
            "com.google.android.syncadapters.calendar", "com.google.android.feedback",
            "com.google.android.onetimeinitializer", "com.google.android.partnersetup",
            "com.google.android.setupwizard"
    ));

    /** Route applied to the current guest process; never contains credentials. */
    public static volatile String CURRENT_ROUTE_ID;

    // Proxy config is stored PER-APP-PER-USER: proxy_<pkg>.json. This lets two apps in the SAME
    // User (e.g. User 0 running both WhatsApp and Instagram) each route through a DIFFERENT proxy.
    // A legacy per-User proxy.json (pkg == null) is still read as a fallback for older assignments.
    private static File file(int userId, String pkg) {
        File dir = BEnvironment.getUserDir(userId);
        if (dir != null && !dir.exists()) dir.mkdirs();
        if (pkg != null && !pkg.isEmpty() && !validPackage(pkg)) {
            throw new IllegalArgumentException("Invalid package name");
        }
        String name = (pkg == null || pkg.isEmpty()) ? "proxy.json" : "proxy_" + pkg + ".json";
        return new File(dir, name);
    }

    private static File requiredFile(int userId, String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid package name");
        return new File(BEnvironment.getUserDir(userId), "proxy_required_" + pkg + ".flag");
    }

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches(PACKAGE_PATTERN);
    }

    /** A protected app stays protected even if its credential file disappears or is corrupted. */
    public static boolean isRouteRequired(int userId, String pkg) {
        try { return validPackage(pkg) && requiredFile(userId, pkg).isFile(); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean markRouteRequired(int userId, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (userId < 0 || !validPackage(pkg)) return false;
        try {
            File target = requiredFile(userId, pkg);
            if (target.isFile()) return true;
            File pending = new File(target.getParentFile(), target.getName() + ".pending");
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write("required-v1".getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!pending.renameTo(target)) {
                pending.delete();
                return target.isFile();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File file(int userId) { return file(userId, null); }

    /** Stable, credential-sensitive identifier used to prove the configured route was applied. */
    public static String routeIdFor(int userId, String pkg) {
        try {
            File f = file(userId, pkg);
            if (!f.exists()) return null;
            JSONObject o = new JSONObject(ProxyConfigCrypto.readText(f, userId, pkg));
            if (!o.optBoolean("enabled", false)) return null;
            String type = o.optString("type", "").trim().toLowerCase();
            String server = o.optString("server", "").trim().toLowerCase();
            int port = o.optInt("port", 0);
            if (server.isEmpty() || port <= 0 || port > 65535
                    || !(type.equals("http") || type.equals("https")
                    || type.equals("socks") || type.equals("socks5"))) return null;
            // Migrate existing assignments to durable fail-closed policy as soon as they are read.
            if (pkg != null && !pkg.isEmpty() && !markRouteRequired(userId, pkg)) return null;
            return routeId(o);
        } catch (Throwable e) {
            return null;
        }
    }

    public enum ApplyStatus {
        READY,
        NOT_CONFIGURED,
        INVALID_CONFIG,
        NATIVE_SETUP_FAILED
    }

    public enum GmsRouteStatus {
        NONE,
        READY,
        CONFLICT,
        INVALID
    }

    /**
     * GMS is shared by every app inside one virtual User, so it can only be enabled when that
     * User has zero routes or one common route. On conflict/corruption every Google service is
     * marked required with its credential file absent, making process initialization fail closed.
     */
    public static synchronized GmsRouteStatus syncGmsRouteForUser(int userId) {
        if (userId < 0) return GmsRouteStatus.INVALID;
        try {
            File dir = BEnvironment.getUserDir(userId);
            File[] configs = dir == null ? null : dir.listFiles();
            String commonRoute = null;
            String commonJson = null;
            if (configs != null) {
                for (File candidate : configs) {
                    String name = candidate.getName();
                    if (!candidate.isFile() || !name.startsWith("proxy_") || !name.endsWith(".json")) continue;
                    String pkg = name.substring("proxy_".length(), name.length() - ".json".length());
                    if (!validPackage(pkg) || isGmsRoutePackage(pkg) || "com.fpprobe".equals(pkg)) continue;
                    JSONObject config = new JSONObject(ProxyConfigCrypto.readText(candidate, userId, pkg));
                    String id = routeId(config);
                    if (id == null) {
                        if (blockGmsRoutes(userId)) stopGmsProcesses(userId);
                        return GmsRouteStatus.INVALID;
                    }
                    if (commonRoute == null) {
                        commonRoute = id;
                        commonJson = config.toString();
                    } else if (!commonRoute.equals(id)) {
                        if (blockGmsRoutes(userId)) stopGmsProcesses(userId);
                        return GmsRouteStatus.CONFLICT;
                    }
                }
            }
            if (commonRoute == null || commonJson == null) {
                if (clearGmsRoutes(userId)) stopGmsProcesses(userId);
                return GmsRouteStatus.NONE;
            }
            boolean changed = false;
            for (String pkg : GMS_ROUTE_PACKAGES) {
                if (!markRouteRequired(userId, pkg)) return GmsRouteStatus.INVALID;
                if (!commonRoute.equals(routeIdFor(userId, pkg))) {
                    ProxyConfigCrypto.writeText(file(userId, pkg), userId, pkg, commonJson);
                    changed = true;
                }
            }
            if (changed) stopGmsProcesses(userId);
            return GmsRouteStatus.READY;
        } catch (Throwable e) {
            if (blockGmsRoutes(userId)) stopGmsProcesses(userId);
            return GmsRouteStatus.INVALID;
        }
    }

    private static boolean isGmsRoutePackage(String pkg) {
        return GMS_ROUTE_PACKAGES.contains(pkg);
    }

    private static boolean blockGmsRoutes(int userId) {
        boolean changed = false;
        for (String pkg : GMS_ROUTE_PACKAGES) {
            try {
                markRouteRequired(userId, pkg);
                File config = file(userId, pkg);
                if (config.exists()) changed = true;
                ProxyConfigCrypto.delete(config);
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    private static boolean clearGmsRoutes(int userId) {
        boolean changed = false;
        for (String pkg : GMS_ROUTE_PACKAGES) {
            try {
                File config = file(userId, pkg);
                File required = requiredFile(userId, pkg);
                if (config.exists() || required.exists()) changed = true;
                ProxyConfigCrypto.delete(config);
                if (required.exists()) required.delete();
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    private static void stopGmsProcesses(int userId) {
        for (String pkg : GMS_ROUTE_PACKAGES) {
            try { BlackBoxCore.get().stopPackage(pkg, userId); } catch (Throwable ignored) {}
        }
    }

    private static String routeId(JSONObject o) throws Exception {
        if (!o.optBoolean("enabled", false)) return null;
        String type = o.optString("type", "").trim().toLowerCase();
        String server = o.optString("server", "").trim().toLowerCase();
        int port = o.optInt("port", 0);
        if (server.isEmpty() || port <= 0 || port > 65535
                || !(type.equals("http") || type.equals("https")
                || type.equals("socks") || type.equals("socks5"))) return null;
        String canonical = type + "\n" + server + "\n" + port + "\n"
                + o.optString("username", "") + "\n" + o.optString("password", "");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 12; i++) hex.append(String.format("%02x", digest[i]));
        return hex.toString();
    }

    /** Save (and enable) a proxy for a specific app in a User. Called from the host bridge. */
    public static boolean save(int userId, String pkg, String type, String server, int port, String user, String pass) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase();
        String normalizedServer = server == null ? "" : server.trim();
        if (userId < 0 || (pkg != null && !pkg.isEmpty() && !validPackage(pkg))
                || normalizedServer.isEmpty() || port <= 0 || port > 65535
                || !(normalizedType.equals("http") || normalizedType.equals("https")
                || normalizedType.equals("socks") || normalizedType.equals("socks5"))) {
            Slog.w(TAG, "refusing invalid proxy config for user " + userId + " pkg " + pkg);
            return false;
        }
        try {
            // Commit policy first. If anything later fails, the app remains blocked instead of direct.
            if (!markRouteRequired(userId, pkg)) return false;
            JSONObject o = new JSONObject();
            o.put("enabled", true);
            o.put("type", normalizedType);
            o.put("server", normalizedServer);
            o.put("port", port);
            o.put("username", user == null ? "" : user);
            o.put("password", pass == null ? "" : pass);

            ProxyConfigCrypto.writeText(file(userId, pkg), userId, pkg, o.toString());
            if (pkg != null && !pkg.isEmpty() && !isGmsRoutePackage(pkg)
                    && !"com.fpprobe".equals(pkg)) syncGmsRouteForUser(userId);
            Slog.d(TAG, "saved protected proxy route for user " + userId + " pkg " + pkg);
            return true;
        } catch (Throwable e) {
            Slog.w(TAG, "save proxy failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Backward-compatible per-User save (no package). */
    public static boolean save(int userId, String type, String server, int port, String user, String pass) {
        return save(userId, null, type, server, port, user, pass);
    }

    /** Remove an app's proxy (traffic goes direct). */
    public static boolean clear(int userId, String pkg) {
        try {
            File f = file(userId, pkg);
            if (f.exists() && !ProxyConfigCrypto.delete(f)) return false;
            if (pkg != null && !pkg.isEmpty()) {
                File required = requiredFile(userId, pkg);
                if (required.exists() && !required.delete()) return false;
            }
            if (pkg != null && !pkg.isEmpty() && !isGmsRoutePackage(pkg)
                    && !"com.fpprobe".equals(pkg)) syncGmsRouteForUser(userId);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean clear(int userId) { return clear(userId, null); }

    /** Current proxy for a User as "server:port:user:pass", or "" if none. */
    public static String describe(int userId) {
        try {
            File f = file(userId);
            if (!f.exists()) return "";
            JSONObject o = new JSONObject(ProxyConfigCrypto.readText(f, userId, null));
            if (!o.optBoolean("enabled", false)) return "";
            return o.optString("server") + ":" + o.optInt("port") + ":"
                    + o.optString("username") + ":" + o.optString("password");
        } catch (Throwable e) {
            return "";
        }
    }

    public static ApplyStatus apply(int userId) {
        return apply(userId, top.niunaijun.blackbox.app.BActivityThread.getAppPackageName());
    }

    public static ApplyStatus apply(int userId, String pkg) {
        // Each process starts from an explicitly direct-disabled state. If an assignment exists but
        // cannot be applied, callers receive a hard failure and must not start the guest.
        NativeCore.disableProxy();
        CURRENT_ROUTE_ID = null;
        try {
            // App launches never inherit the legacy per-user proxy. That fallback could silently
            // route two apps through the same credentials and defeats deterministic isolation.
            File f = file(userId, pkg);
            if (!f.exists()) {
                return isRouteRequired(userId, pkg)
                        ? ApplyStatus.INVALID_CONFIG : ApplyStatus.NOT_CONFIGURED;
            }
            if (!markRouteRequired(userId, pkg)) return ApplyStatus.INVALID_CONFIG;

            JSONObject o = new JSONObject(ProxyConfigCrypto.readText(f, userId, pkg));
            if (!o.optBoolean("enabled", false)) return ApplyStatus.INVALID_CONFIG;

            String type = o.optString("type", "").trim().toLowerCase();
            if (!(type.equals("http") || type.equals("https") || type.equals("socks") || type.equals("socks5")))
                return ApplyStatus.INVALID_CONFIG;
            int t = (type.equalsIgnoreCase("socks5") || type.equalsIgnoreCase("socks")) ? 1 : 0;
            String server = o.optString("server", "").trim();
            int port = o.optInt("port", 0);
            String user = o.optString("username", "");
            String pass = o.optString("password", "");
            if (server.isEmpty() || port <= 0 || port > 65535) return ApplyStatus.INVALID_CONFIG;

            if (!NativeCore.setProxy(t, server, port, user, pass)) {
                NativeCore.disableProxy();
                return ApplyStatus.NATIVE_SETUP_FAILED;
            }
            String routeId = routeIdFor(userId, pkg);
            if (routeId == null) {
                NativeCore.disableProxy();
                return ApplyStatus.INVALID_CONFIG;
            }
            CURRENT_ROUTE_ID = routeId;
            Slog.d(TAG, "guest proxy applied for user " + userId + " pkg " + pkg);

            // Make the clock + language match the exit region so a US exit IP doesn't sit on an
            // Africa/Lagos timezone (a detectable mismatch). Derived from the proxy's region/city.
            applyGeoConsistency(user, userId);
            return ApplyStatus.READY;
        } catch (Throwable e) {
            NativeCore.disableProxy();
            CURRENT_ROUTE_ID = null;
            Slog.w(TAG, "guest proxy apply failed: " + e.getClass().getSimpleName());
            return ApplyStatus.INVALID_CONFIG;
        }
    }

    /**
     * Set the guest process's default TimeZone + Locale to match the proxy's country/region, so
     * the clone's clock and language line up with its exit IP. Best-effort process-level default
     * (TimeZone.setDefault / Locale.setDefault) — covers the Java date/locale paths apps use.
     */
    private static void applyGeoConsistency(String proxyUser, int userId) {
        try {
            String u = proxyUser == null ? "" : proxyUser.toLowerCase();
            String tz = timezoneFor(u);
            java.util.Locale loc = localeFor(u);
            if (tz != null) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(tz));
            }
            if (loc != null) {
                java.util.Locale.setDefault(loc);
            }

            // GPS location — set the fake location to the proxy's city so Maps/apps that read GPS
            // agree with the exit IP (a US IP with a Lagos GPS fix is a hard mismatch). Per-clone
            // jitter so two clones in the same city aren't at the identical point.
            double[] ll = latLngForProxy(u);
            if (ll != null) {
                try {
                    String pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
                    double jLat = ll[0] + ((userId * 37 % 100) - 50) / 10000.0;   // ±0.005°
                    double jLng = ll[1] + ((userId * 53 % 100) - 50) / 10000.0;
                    top.niunaijun.blackbox.fake.frameworks.BLocationManager lm =
                            top.niunaijun.blackbox.fake.frameworks.BLocationManager.get();
                    lm.setPattern(userId, pkg, top.niunaijun.blackbox.fake.frameworks.BLocationManager.OWN_MODE);
                    lm.setLocation(userId, pkg,
                            new top.niunaijun.blackbox.entity.location.BLocation(jLat, jLng));
                    Slog.d(TAG, "geo location profile applied for " + pkg);
                } catch (Throwable t) {
                    Slog.w(TAG, "set fake location failed: " + t.getMessage());
                }
            }
            // SIM/carrier country → the phone-number country picker in WhatsApp/Instagram reads
            // this (gsm.sim.operator.*), NOT the IP. Match it to the proxy country so a US proxy
            // doesn't still show Nigeria +234.
            String[] sim = simForProxy(u);   // {mccMnc, iso, operatorName}
            if (sim != null) {
                DeviceProfile cur = DeviceProfile.CURRENT;
                if (cur != null) { cur.mccMnc = sim[0]; cur.simCountryIso = sim[1]; cur.simOperatorName = sim[2]; cur.simSpoofed = true; }
                try {
                    // Doubled per slot ("us,us") — dual-SIM phones read slot [phoneId].
                    String n = sim[0] + "," + sim[0], i = sim[1] + "," + sim[1], a = sim[2] + "," + sim[2];
                    NativeCore.spoofDevice(
                        new String[]{"gsm.operator.numeric","gsm.operator.iso-country","gsm.operator.alpha",
                                     "gsm.sim.operator.numeric","gsm.sim.operator.iso-country","gsm.sim.operator.alpha"},
                        new String[]{n, i, a, n, i, a});
                } catch (Throwable ignored) {}
            }

            if (tz != null || loc != null || sim != null) {
                Slog.d(TAG, "geo consistency profile applied");
            }
        } catch (Throwable e) {
            Slog.w(TAG, "geo consistency failed: " + e.getMessage());
        }
    }

    /** Approximate lat/lng for the proxy's city (falls back to region/country center). */
    private static double[] latLngForProxy(String u) {
        // City first (most specific)
        if (u.contains("los+angeles") || u.contains("city-losangeles")) return new double[]{34.0522, -118.2437};
        if (u.contains("city-dallas"))       return new double[]{32.7767, -96.7970};
        if (u.contains("city-miami"))        return new double[]{25.7617, -80.1918};
        if (u.contains("city-new+york") || u.contains("city-newyork")) return new double[]{40.7128, -74.0060};
        if (u.contains("city-chicago"))      return new double[]{41.8781, -87.6298};
        if (u.contains("city-houston"))      return new double[]{29.7604, -95.3698};
        if (u.contains("city-atlanta"))      return new double[]{33.7490, -84.3880};
        if (u.contains("city-seattle"))      return new double[]{47.6062, -122.3321};
        if (u.contains("city-phoenix"))      return new double[]{33.4484, -112.0740};
        if (u.contains("city-denver"))       return new double[]{39.7392, -104.9903};
        // Region (US state) fallback
        if (u.contains("region-california")) return new double[]{34.0522, -118.2437};
        if (u.contains("region-texas"))      return new double[]{32.7767, -96.7970};
        if (u.contains("region-florida"))    return new double[]{25.7617, -80.1918};
        if (u.contains("region-new+york") || u.contains("region-newyork")) return new double[]{40.7128, -74.0060};
        if (u.contains("region-illinois"))   return new double[]{41.8781, -87.6298};
        // Country fallback
        if (u.contains("country-us"))        return new double[]{39.8283, -98.5795};   // US centroid
        if (u.contains("country-gb") || u.contains("country-uk")) return new double[]{51.5074, -0.1278};
        if (u.contains("country-ca"))        return new double[]{43.6532, -79.3832};
        if (u.contains("country-de"))        return new double[]{52.5200, 13.4050};
        if (u.contains("country-fr"))        return new double[]{48.8566, 2.3522};
        if (u.contains("country-au"))        return new double[]{-33.8688, 151.2093};
        return null;
    }

    /** Map a proxy username's country to a plausible carrier: {mccMnc, iso, operatorName}. */
    private static String[] simForProxy(String u) {
        if (u.contains("country-us")) return new String[]{"310260", "us", "T-Mobile"};
        if (u.contains("country-gb") || u.contains("country-uk")) return new String[]{"23410", "gb", "O2"};
        if (u.contains("country-ca")) return new String[]{"302610", "ca", "Rogers"};
        if (u.contains("country-de")) return new String[]{"26201", "de", "Telekom"};
        if (u.contains("country-fr")) return new String[]{"20801", "fr", "Orange"};
        if (u.contains("country-au")) return new String[]{"50501", "au", "Telstra"};
        return null; // unknown → keep the profile default (US)
    }

    /** Map a SOAX-style proxy username (…country-us-region-texas-city-dallas…) to a timezone. */
    private static String timezoneFor(String u) {
        if (u.contains("country-us")) {
            if (u.contains("region-california") || u.contains("region-washington")
                    || u.contains("region-oregon") || u.contains("region-nevada")) return "America/Los_Angeles";
            if (u.contains("region-texas") || u.contains("region-illinois")
                    || u.contains("region-minnesota") || u.contains("region-missouri")
                    || u.contains("region-oklahoma") || u.contains("region-louisiana")) return "America/Chicago";
            if (u.contains("region-arizona") || u.contains("region-colorado")
                    || u.contains("region-utah") || u.contains("region-newmexico")) return "America/Denver";
            // Florida, New York, Georgia, and the rest of the East + default US
            return "America/New_York";
        }
        if (u.contains("country-gb") || u.contains("country-uk")) return "Europe/London";
        if (u.contains("country-ca")) return "America/Toronto";
        if (u.contains("country-de")) return "Europe/Berlin";
        if (u.contains("country-fr")) return "Europe/Paris";
        if (u.contains("country-au")) return "Australia/Sydney";
        return null; // unknown region — leave the device's real timezone
    }

    private static java.util.Locale localeFor(String u) {
        if (u.contains("country-us")) return java.util.Locale.US;
        if (u.contains("country-gb") || u.contains("country-uk")) return java.util.Locale.UK;
        if (u.contains("country-ca")) return java.util.Locale.CANADA;
        if (u.contains("country-de")) return java.util.Locale.GERMANY;
        if (u.contains("country-fr")) return java.util.Locale.FRANCE;
        if (u.contains("country-au")) return new java.util.Locale("en", "AU");
        return null;
    }
}
