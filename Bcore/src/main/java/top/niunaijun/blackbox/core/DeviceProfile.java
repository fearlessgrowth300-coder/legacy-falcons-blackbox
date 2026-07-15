package top.niunaijun.blackbox.core;

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-clone device identity. Each virtual user (userId) gets a coherent device
 * profile plus persistent random identifiers (android_id / imei / serial / MAC),
 * so "5 clones = 5 different phones" and the identity is STABLE across restarts
 * (unstable IDs are themselves a ban signal).
 *
 * The profile is applied inside the guest process, as early as possible, by:
 *   1) pushing the ro.* properties into the native __system_property_get hook
 *      (NativeCore.spoofDevice) — this also drives Build.* on most devices, and
 *   2) reflection-setting the Build.* static fields as a timing-proof backup.
 *
 * Graphics/SoC keys (ro.hardware*, ro.board.platform, ro.soc.*, egl…) are left at
 * their REAL values — the physical GPU can't be changed and spoofing them crashes
 * the GPU driver (EGL_BAD_ACCESS). See Utils/VirtualSpoof.cpp:is_graphics_key.
 */
public class DeviceProfile {
    private static final String TAG = "DeviceProfile";

    // label, manufacturer, brand, model, device, product, board, fingerprint, gpu
    // gpu = the device's real GPU family ("mali" or "adreno"); a clone is only ever
    // given a profile whose GPU family matches the REAL phone, so Model/Board/Fingerprint
    // stay coherent with the un-spoofable GL_RENDERER.
    private static final String[][] PROFILES = {
        // ---- Mali (Exynos / Google Tensor / MediaTek Helio / Kirin) ----
        {"Samsung Galaxy A71", "samsung", "samsung", "SM-A715F", "a71", "a71xx", "exynos980",
         "samsung/a71xx/a71:13/TP1A.220624.014/A715FXXU8DWL1:user/release-keys", "mali"},
        {"Samsung Galaxy A51", "samsung", "samsung", "SM-A515F", "a51", "a51xx", "exynos9611",
         "samsung/a51xx/a51:13/TP1A.220624.014/A515FXXU5GWK2:user/release-keys", "mali"},
        {"Samsung Galaxy A53 5G", "samsung", "samsung", "SM-A536E", "a53x", "a53xxx", "s5e8825",
         "samsung/a53xxx/a53x:14/UP1A.231005.007/A536EXXS9DXA1:user/release-keys", "mali"},
        {"Samsung Galaxy A33 5G", "samsung", "samsung", "SM-A336E", "a33x", "a33xxx", "s5e8825",
         "samsung/a33xxx/a33x:14/UP1A.231005.007/A336EXXS8CXC1:user/release-keys", "mali"},
        {"Samsung Galaxy A50", "samsung", "samsung", "SM-A505F", "a50", "a50xx", "exynos9610",
         "samsung/a50xx/a50:11/RP1A.200720.012/A505FDDUACUL1:user/release-keys", "mali"},
        {"Samsung Galaxy S20", "samsung", "samsung", "SM-G980F", "x1s", "x1sxxx", "exynos990",
         "samsung/x1sxxx/x1s:13/TP1A.220624.014/G980FXXSGHWK1:user/release-keys", "mali"},
        {"Samsung Galaxy S21", "samsung", "samsung", "SM-G991B", "o1s", "o1sxxx", "exynos2100",
         "samsung/o1sxxx/o1s:14/UP1A.231005.007/G991BXXS9GXA1:user/release-keys", "mali"},
        {"Samsung Galaxy S10", "samsung", "samsung", "SM-G973F", "beyond1", "beyond1lte", "exynos9820",
         "samsung/beyond1ltexx/beyond1:12/SP1A.210812.016/G973FXXSGHVK1:user/release-keys", "mali"},
        {"Google Pixel 6", "Google", "google", "Pixel 6", "oriole", "oriole", "gs101",
         "google/oriole/oriole:14/AP2A.240905.003/12231197:user/release-keys", "mali"},
        {"Google Pixel 7", "Google", "google", "Pixel 7", "panther", "panther", "gs201",
         "google/panther/panther:14/AP2A.240905.003/12231197:user/release-keys", "mali"},
        {"Google Pixel 6a", "Google", "google", "Pixel 6a", "bluejay", "bluejay", "gs101",
         "google/bluejay/bluejay:14/AP2A.240805.005/12123123:user/release-keys", "mali"},
        {"Tecno Camon 19", "TECNO", "TECNO", "TECNO CI6", "TECNO-CI6", "CI6-GL", "mt6785",
         "TECNO/CI6-GL/TECNO-CI6:12/SP1A.210812.016/230101V123:user/release-keys", "mali"},
        {"Tecno Spark 10", "TECNO", "TECNO", "TECNO KI5q", "TECNO-KI5q", "KI5q-GL", "mt6768",
         "TECNO/KI5q-GL/TECNO-KI5q:13/TP1A.220624.014/231215V456:user/release-keys", "mali"},
        {"Infinix Note 12", "Infinix", "Infinix", "Infinix X670", "Infinix-X670", "X670-GL", "mt6768",
         "Infinix/X670-GL/Infinix-X670:12/SP1A.210812.016/221010V789:user/release-keys", "mali"},
        {"Infinix Hot 20", "Infinix", "Infinix", "Infinix X6826B", "Infinix-X6826B", "X6826B-GL", "mt6769",
         "Infinix/X6826B-GL/Infinix-X6826B:12/SP1A.210812.016/221101V321:user/release-keys", "mali"},
        {"Realme C55", "realme", "realme", "RMX3710", "RMX3710", "RMX3710", "mt6789",
         "realme/RMX3710/RMX3710:13/TP1A.220624.014/S.202303V1:user/release-keys", "mali"},
        {"Redmi Note 11S", "Xiaomi", "Redmi", "2201117SG", "fleur", "fleur_global", "mt6781",
         "Redmi/fleur_global/fleur:13/TP1A.220624.014/V14.0.3.0.TGMMIXM:user/release-keys", "mali"},
        {"Huawei P30", "HUAWEI", "HUAWEI", "ELE-L29", "HWELE", "ELE-L29", "kirin980",
         "HUAWEI/ELE-L29/HWELE:10/HUAWEIELE-L29/10.1.0.160:user/release-keys", "mali"},

        // ---- Adreno (Qualcomm Snapdragon) ----
        {"Samsung Galaxy S23", "samsung", "samsung", "SM-S911B", "dm1q", "dm1qxxx", "kalama",
         "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU5CXH9:user/release-keys", "adreno"},
        {"Samsung Galaxy S22 (US)", "samsung", "samsung", "SM-S901U", "r0q", "r0qsq", "taro",
         "samsung/r0qsq/r0q:14/UP1A.231005.007/S901USQS6DXA1:user/release-keys", "adreno"},
        {"OnePlus 11", "OnePlus", "OnePlus", "CPH2449", "OP594DL1", "CPH2449", "kalama",
         "OnePlus/CPH2449/OP594DL1:14/UKQ1.230924.001/S.202310112035:user/release-keys", "adreno"},
        {"OnePlus 9", "OnePlus", "OnePlus", "LE2113", "OnePlus9", "OnePlus9", "lahaina",
         "OnePlus/OnePlus9_EEA/OnePlus9:13/RKQ1.211119.001/S.202305251359:user/release-keys", "adreno"},
        {"Xiaomi 13", "Xiaomi", "Xiaomi", "2211133G", "fuxi", "fuxi_global", "kalama",
         "Xiaomi/fuxi_global/fuxi:14/UKQ1.230804.001/V816.0.5.0.UMCMIXM:user/release-keys", "adreno"},
        {"Redmi Note 12", "Xiaomi", "Redmi", "22111317I", "tapas", "tapas_global", "holi",
         "Redmi/tapas_global/tapas:13/TP1A.220624.014/V14.0.4.0.TMRMIXM:user/release-keys", "adreno"},
        {"Redmi Note 10", "Xiaomi", "Redmi", "M2101K7AI", "mojito", "mojito_in", "bengal",
         "Redmi/mojito_in/mojito:13/TP1A.220624.014/V14.0.2.0.TKGINXM:user/release-keys", "adreno"},
        {"POCO X5 Pro", "Xiaomi", "POCO", "22101320G", "redwood", "redwood_global", "holi",
         "POCO/redwood_global/redwood:13/TP1A.220624.014/V14.0.3.0.TMFMIXM:user/release-keys", "adreno"},
        {"OPPO Find X5", "OPPO", "OPPO", "CPH2307", "OP4EA5", "CPH2307", "taro",
         "OPPO/CPH2307/OP4EA5:13/RKQ1.211119.001/S.202304211947:user/release-keys", "adreno"},
        {"OPPO A96", "OPPO", "OPPO", "CPH2333", "OP4F0B", "CPH2333", "bengal",
         "OPPO/CPH2333/OP4F0B:12/SP1A.210812.016/S.202205231451:user/release-keys", "adreno"},
        {"Motorola Edge 30", "motorola", "motorola", "motorola edge 30", "dubai", "dubai_retail", "holi",
         "motorola/dubai_retail/dubai:13/T1TDS33.15-84-6/6b3f2:user/release-keys", "adreno"},
        {"vivo X80 Pro", "vivo", "vivo", "V2145", "PD2186", "PD2186", "taro",
         "vivo/PD2186/PD2186:13/TP1A.220624.014/compil123456:user/release-keys", "adreno"},
        {"Nothing Phone 1", "Nothing", "Nothing", "A063", "Spacewar", "Spacewar", "lahaina",
         "Nothing/Spacewar/Spacewar:13/TP1A.220905.004/1667300000:user/release-keys", "adreno"},
        {"Google Pixel 5", "Google", "google", "Pixel 5", "redfin", "redfin", "redfin",
         "google/redfin/redfin:14/AP2A.240805.005/12123123:user/release-keys", "adreno"},
    };

    // Each profile's real screen density (dpi), same order as PROFILES. Used to prefer a
    // spoof model whose screen roughly matches the REAL phone — the physical screen can't
    // be changed without breaking rendering, so coherence comes from choosing a close model.
    private static final int[] PROFILE_DPI = {
        // Mali
        393, 405, 405, 400, 403, 421, 421, 550, 411, 420, 429, 392, 280, 393, 280, 392, 409, 422,
        // Adreno
        425, 425, 450, 402, 414, 395, 409, 395, 456, 269, 402, 452, 402, 432
    };

    public String label, manufacturer, brand, model, device, product, board, fingerprint;
    public String androidId, imei, imsi, serial, macWifi, gaid;
    public String buildId, incremental, securityPatch;   // dynamic per-clone build variance
    public String widevineId;                             // per-clone Widevine deviceUniqueId (hex)
    // SIM / carrier identity — drives the country picker in WhatsApp/Instagram (they read
    // gsm.sim.operator.iso-country / getNetworkOperator, NOT the IP). Only spoofed when a proxy
    // with a country is assigned (GuestProxy sets these + simSpoofed=true); with NO proxy the SIM
    // stays REAL so a no-proxy clone shows the real country (coherent: real IP + real SIM).
    public String mccMnc = "310260";        // MCC 310 (US) + MNC 260 (T-Mobile), used when spoofed
    public String simCountryIso = "us";
    public String simOperatorName = "T-Mobile";
    public volatile boolean simSpoofed = false;

    /**
     * The profile applied to the CURRENT process (guest). Set by apply(); read by the
     * framework proxies (AndroidIdProxy / ITelephonyManagerProxy / IWifiManagerProxy)
     * so the Java-layer identifiers match this clone's native profile. null in the host.
     */
    public static volatile DeviceProfile CURRENT;

    /** Load the persisted profile for this clone, or generate+persist a fresh one. */
    public static DeviceProfile forUser(int userId) {
        DeviceProfile p = new DeviceProfile();
        File dir = BEnvironment.getUserDir(userId);
        if (dir != null && !dir.exists()) dir.mkdirs();
        File conf = new File(dir, "device_profile.properties");

        Properties props = new Properties();
        boolean loaded = false;
        if (conf.exists()) {
            try (FileInputStream in = new FileInputStream(conf)) {
                props.load(in);
                loaded = props.containsKey("idx") && props.containsKey("androidId");
            } catch (Exception e) {
                Slog.w(TAG, "load device_profile failed: " + e.getMessage());
            }
        }

        int idx;
        if (loaded) {
            idx = clampIdx(parseInt(props.getProperty("idx"), 0));
            p.androidId = props.getProperty("androidId");
            p.imei = props.getProperty("imei");
            p.imsi = props.getProperty("imsi");
            p.serial = props.getProperty("serial");
            p.macWifi = props.getProperty("macWifi");
            p.gaid = props.getProperty("gaid");
            p.buildId = props.getProperty("buildId");
            p.incremental = props.getProperty("incremental");
            p.securityPatch = props.getProperty("securityPatch");
            p.fingerprint = props.getProperty("fingerprint");
            p.widevineId = props.getProperty("widevineId");
        } else {
            // First run for this clone: TRUE randomness (SecureRandom), NOT a userId-derived
            // seed — otherwise every user's "User 0" would be identical across all phones.
            // Each clone anywhere gets a unique fingerprint set.
            Random r = new java.security.SecureRandom();
            idx = pickCoherentIndex(r);
            p.androidId = hex(r, 16);
            p.imei = digits(r, 15);
            p.imsi = "310260" + digits(r, 9);   // MCC 310 / MNC 260 (T-Mobile US) + MSIN
            p.serial = hexUpper(r, 12);
            p.macWifi = localMac(r);
            p.gaid = uuid(r);
            // Dynamic build variance: unique build id / incremental / patch date, mixed
            // within the coherent base device (model/board/GPU stay valid).
            Fp fp = genFingerprint(PROFILES[idx][7], r);
            p.buildId = fp.buildId;
            p.incremental = fp.incremental;
            p.securityPatch = fp.patch;
            p.fingerprint = fp.fingerprint;
            // Widevine deviceUniqueId: 32 bytes (64 hex chars) on real devices; Meta reads it
            // as a stable hardware ID. Give each clone its own so it can't link clones on one phone.
            p.widevineId = hex(r, 64);
        }

        String[] pr = PROFILES[idx];
        p.label = pr[0]; p.manufacturer = pr[1]; p.brand = pr[2]; p.model = pr[3];
        p.device = pr[4]; p.product = pr[5]; p.board = pr[6];

        // Back-fill fields for profiles persisted before they existed (one-off, random).
        boolean needsSave = !loaded;
        Random bf = new java.security.SecureRandom();
        if (p.imsi == null) { p.imsi = "310260" + digits(bf, 9); needsSave = true; }
        if (p.gaid == null) { p.gaid = uuid(bf); needsSave = true; }
        if (p.widevineId == null) { p.widevineId = hex(bf, 64); needsSave = true; }
        if (p.fingerprint == null) {
            Fp fp = genFingerprint(pr[7], bf);
            p.buildId = fp.buildId; p.incremental = fp.incremental;
            p.securityPatch = fp.patch; p.fingerprint = fp.fingerprint;
            needsSave = true;
        }

        if (needsSave) {
            props.setProperty("idx", String.valueOf(idx));
            props.setProperty("androidId", p.androidId);
            props.setProperty("imei", p.imei);
            props.setProperty("imsi", p.imsi);
            props.setProperty("serial", p.serial);
            props.setProperty("macWifi", p.macWifi);
            props.setProperty("gaid", p.gaid);
            props.setProperty("buildId", p.buildId);
            props.setProperty("incremental", p.incremental);
            props.setProperty("securityPatch", p.securityPatch);
            props.setProperty("fingerprint", p.fingerprint);
            props.setProperty("widevineId", p.widevineId);
            try (FileOutputStream out = new FileOutputStream(conf)) {
                props.store(out, "per-clone device identity");
            } catch (Exception e) {
                Slog.w(TAG, "persist device_profile failed: " + e.getMessage());
            }
        }
        return p;
    }

    private static volatile boolean sAndroidIdHooked = false;

    /**
     * Directly hook Settings.Secure.getString/getStringForUser so android_id reads
     * return THIS clone's value. BlackBox's own settings proxies are dead stubs, so
     * without this every clone reports the host's real android_id (linkage).
     */
    private static void hookAndroidId() {
        if (sAndroidIdHooked) return;
        try {
            top.canyie.pine.PineConfig.debug = false;
            top.canyie.pine.PineConfig.debuggable = false;
            Class<?> secure = android.provider.Settings.Secure.class;
            top.canyie.pine.callback.MethodHook cb = new top.canyie.pine.callback.MethodHook() {
                @Override public void afterCall(top.canyie.pine.Pine.CallFrame frame) {
                    if (CURRENT == null || CURRENT.androidId == null) return;
                    Object[] a = frame.args;
                    if (a != null) {
                        for (Object o : a) {
                            if (o instanceof String && "android_id".equals(o)) {
                                frame.setResult(CURRENT.androidId);
                                return;
                            }
                        }
                    }
                }
            };
            try {
                java.lang.reflect.Method m = secure.getMethod("getString",
                        android.content.ContentResolver.class, String.class);
                top.canyie.pine.Pine.hook(m, cb);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Method m = secure.getDeclaredMethod("getStringForUser",
                        android.content.ContentResolver.class, String.class, int.class);
                top.canyie.pine.Pine.hook(m, cb);
            } catch (Throwable ignored) {}
            sAndroidIdHooked = true;
            Slog.d(TAG, "android_id hook installed");
        } catch (Throwable t) {
            Slog.w(TAG, "hookAndroidId failed: " + t.getMessage());
        }
    }

    /**
     * Per-clone Google Advertising ID (GAID). Meta reads it heavily. Hook the standard
     * AdvertisingIdClient.getAdvertisingIdInfo() to return this clone's value. The GMS
     * ads SDK class loads lazily inside the guest, so retry a few times until it exists.
     */
    private void hookGaid() {
        final String id = gaid;
        if (id == null) return;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                try {
                    Class<?> cls = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient");
                    Class<?> infoCls = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient$Info");
                    final Object info = infoCls.getConstructor(String.class, boolean.class).newInstance(id, false);
                    java.lang.reflect.Method m = cls.getMethod("getAdvertisingIdInfo", android.content.Context.class);
                    top.canyie.pine.Pine.hook(m, new top.canyie.pine.callback.MethodReplacement() {
                        @Override public Object replaceCall(top.canyie.pine.Pine.CallFrame f) { return info; }
                    });
                    Slog.d(TAG, "GAID hook installed = " + id);
                } catch (ClassNotFoundException notYet) {
                    if (tries++ < 6) h.postDelayed(this, 3000); // GMS SDK not loaded yet — retry
                } catch (Throwable t) {
                    Slog.w(TAG, "gaid hook failed: " + t.getMessage());
                }
            }
        }, 1500);
    }

    private static volatile boolean sMediaDrmHooked = false;

    /**
     * Per-clone Widevine device identity. MediaDrm.getPropertyByteArray(WIDEVINE, "deviceUniqueId")
     * returns a stable per-hardware ID that Meta/DRM SDKs read to link a physical device. Without
     * this, every clone on one phone returns the SAME real Widevine ID = a hard linker. Hook the
     * MediaDrm getters so each clone reports its own value. Also normalise a couple of
     * device-revealing string properties (vendor/deviceId) to the spoofed identity.
     */
    private void hookMediaDrm() {
        if (sMediaDrmHooked) return;
        final byte[] wid = hexToBytes(widevineId);
        if (wid == null) return;
        try {
            final Class<?> cls = android.media.MediaDrm.class;
            // getPropertyByteArray(String) -> "deviceUniqueId"
            top.canyie.pine.Pine.hook(
                cls.getDeclaredMethod("getPropertyByteArray", String.class),
                new top.canyie.pine.callback.MethodHook() {
                    @Override public void afterCall(top.canyie.pine.Pine.CallFrame f) {
                        try {
                            Object arg = f.args != null && f.args.length > 0 ? f.args[0] : null;
                            if ("deviceUniqueId".equals(arg)) f.setResult(wid.clone());
                        } catch (Throwable ignored) {}
                    }
                });
            // getPropertyString(String) -> normalise a few identifying strings so the clone
            // looks internally consistent (best-effort; unknown props pass through unchanged).
            top.canyie.pine.Pine.hook(
                cls.getDeclaredMethod("getPropertyString", String.class),
                new top.canyie.pine.callback.MethodHook() {
                    @Override public void afterCall(top.canyie.pine.Pine.CallFrame f) {
                        try {
                            Object arg = f.args != null && f.args.length > 0 ? f.args[0] : null;
                            if ("deviceUniqueId".equals(arg)) {
                                f.setResult(widevineId);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            sMediaDrmHooked = true;
            Slog.d(TAG, "MediaDrm/Widevine hook installed");
        } catch (Throwable t) {
            Slog.w(TAG, "hookMediaDrm failed: " + t.getMessage());
        }
    }

    private static byte[] hexToBytes(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        try {
            int n = s.length() / 2;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++)
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Apply this profile to the current (guest) process. Call as early as possible. */
    public void apply() {
        CURRENT = this;   // make it visible to the framework proxies
        hookAndroidId();
        hookGaid();
        hookMediaDrm();
        List<String> keys = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        add(keys, vals, "ro.product.model", model);
        add(keys, vals, "ro.product.brand", brand);
        add(keys, vals, "ro.product.name", product);
        add(keys, vals, "ro.product.device", device);
        add(keys, vals, "ro.product.manufacturer", manufacturer);
        add(keys, vals, "ro.product.board", board);
        add(keys, vals, "ro.build.fingerprint", fingerprint);
        add(keys, vals, "ro.build.product", device);
        add(keys, vals, "ro.serialno", serial);
        add(keys, vals, "ro.boot.serialno", serial);
        // NOTE: SIM/carrier country props are NOT set here — they're pushed by GuestProxy only
        // when a proxy with a country is assigned, so a no-proxy clone keeps its REAL SIM.
        // Dynamic per-clone build variance
        add(keys, vals, "ro.build.id", buildId);
        add(keys, vals, "ro.build.display.id", buildId);
        add(keys, vals, "ro.build.version.incremental", incremental);
        add(keys, vals, "ro.build.version.security_patch", securityPatch);
        // vendor/system partition variants some detectors read
        add(keys, vals, "ro.product.vendor.model", model);
        add(keys, vals, "ro.product.vendor.brand", brand);
        add(keys, vals, "ro.product.vendor.manufacturer", manufacturer);
        add(keys, vals, "ro.product.vendor.device", device);
        add(keys, vals, "ro.product.vendor.name", product);
        add(keys, vals, "ro.product.system.model", model);
        add(keys, vals, "ro.product.system.brand", brand);

        // Per-variant push order. Each key is set independently on the native side, so the order
        // is functionally irrelevant — but permuting it deterministically per build variant means
        // the spoof engine's write sequence isn't an identical signature across all variants.
        try {
            java.util.List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) idx.add(i);
            java.util.Collections.shuffle(idx, new java.util.Random(VariantConfig.propSpoofSeed()));
            String[] ks = new String[keys.size()];
            String[] vs = new String[vals.size()];
            for (int i = 0; i < idx.size(); i++) { ks[i] = keys.get(idx.get(i)); vs[i] = vals.get(idx.get(i)); }
            NativeCore.spoofDevice(ks, vs);
        } catch (Throwable t) {
            Slog.w(TAG, "native spoofDevice failed: " + t.getMessage());
        }

        // Timing-proof backup: overwrite the Build static fields directly.
        setBuild("MODEL", model);
        setBuild("BRAND", brand);
        setBuild("DEVICE", device);
        setBuild("PRODUCT", product);
        setBuild("MANUFACTURER", manufacturer);
        setBuild("BOARD", board);
        // Build.HARDWARE (Java) was leaking the REAL SoC (e.g. exynos980) while BOARD/MODEL claimed a
        // different device (kirin980/HUAWEI) — an obvious "this fingerprint is fake" mismatch apps like
        // Instagram flag → session logout. Set it to the profile's SoC so Java reads are coherent. We do
        // NOT push ro.hardware natively (kept real so the GPU driver loads correctly — no EGL crash).
        setBuild("HARDWARE", board);
        setBuild("FINGERPRINT", fingerprint);
        setBuild("SERIAL", serial);
        setBuild("ID", buildId);
        setBuild("DISPLAY", buildId);
        setBuildVersion("INCREMENTAL", incremental);
        setBuildVersion("SECURITY_PATCH", securityPatch);
        Slog.d(TAG, "applied profile '" + label + "' fp=" + fingerprint);
    }

    private static void setBuildVersion(String field, String value) {
        try {
            java.lang.reflect.Field f = Build.VERSION.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static void add(List<String> k, List<String> v, String key, String val) {
        if (val != null) { k.add(key); v.add(val); }
    }

    private static void setBuild(String field, String value) {
        try {
            Field f = Build.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    private static int clampIdx(int i) { return (i < 0 || i >= PROFILES.length) ? 0 : i; }

    /** Pick a profile whose GPU family AND screen density match the REAL phone, so
     *  Model↔GPU↔screen stay coherent (neither the GPU nor the physical screen can be
     *  changed in a no-root container, so coherence comes from choosing a close model). */
    private static int pickCoherentIndex(Random r) {
        String want = realGpuFamily();
        java.util.ArrayList<Integer> pool = new java.util.ArrayList<>();
        for (int i = 0; i < PROFILES.length; i++) {
            if (want == null || (PROFILES[i].length > 8 && PROFILES[i][8].equals(want))) pool.add(i);
        }
        if (pool.isEmpty()) return r.nextInt(PROFILES.length);

        int realDpi = realDensity();
        if (realDpi > 0) {
            java.util.ArrayList<Integer> close = new java.util.ArrayList<>();
            for (int i : pool) if (i < PROFILE_DPI.length && Math.abs(PROFILE_DPI[i] - realDpi) <= 35) close.add(i);
            if (!close.isEmpty()) return close.get(r.nextInt(close.size()));
        }
        return pool.get(r.nextInt(pool.size()));
    }

    /** Real screen density (dpi) — ro.sf.lcd_density isn't spoofed, so this is the true value. */
    private static int realDensity() {
        try {
            String d = readReal("ro.sf.lcd_density");
            if (d != null && !d.isEmpty()) return Integer.parseInt(d.trim());
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Infer the real device's GPU family from its (un-spoofed, graphics-blocklisted)
     * SoC properties. Adreno = Qualcomm; Mali = Exynos / MediaTek / Kirin / Tensor.
     * Returns null if unknown (then any profile is allowed).
     */
    private static String realGpuFamily() {
        try {
            String s = (readReal("ro.soc.manufacturer") + " " + readReal("ro.board.platform")
                    + " " + readReal("ro.hardware") + " " + readReal("ro.chipname")
                    + " " + readReal("ro.soc.model")).toLowerCase();
            if (s.contains("qualcomm") || s.contains("qti") || s.contains("qcom")
                    || s.contains("kalama") || s.contains("lahaina") || s.contains("taro")
                    || s.contains("bengal") || s.contains("holi") || s.contains("sdm")
                    || s.contains("msm") || s.contains("kona") || s.contains("lito"))
                return "adreno";
            if (s.contains("exynos") || s.contains("samsung") || s.contains("universal")
                    || s.contains("mediatek") || s.contains("mt6") || s.contains("mt8")
                    || s.contains("mtk") || s.contains("kirin") || s.contains("hi3")
                    || s.contains("tensor") || s.contains("gs101") || s.contains("gs201")
                    || s.contains("s5e"))
                return "mali";
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Read a system property. Graphics/SoC keys are NOT spoofed, so this returns the
     *  REAL value even inside a guest (used to detect the phone's true GPU family). */
    private static String readReal(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    // Spread userIds so consecutive clones don't seed to adjacent values.
    private static long mix(int userId) {
        long x = (userId + 0x9E3779B9L) * 0x2545F4914F6CDD1DL;
        x ^= (x >>> 29);
        return x;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] HEXU = "0123456789ABCDEF".toCharArray();

    private static String hex(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(HEX[r.nextInt(16)]);
        return sb.toString();
    }

    private static String hexUpper(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(HEXU[r.nextInt(16)]);
        return sb.toString();
    }

    private static String digits(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append(1 + r.nextInt(9)); // no leading zero
        for (int i = 1; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    /** A GAID-style lowercase UUID (8-4-4-4-12). */
    private static String uuid(Random r) {
        String h = hex(r, 32);
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
    }

    // ---- dynamic build/fingerprint variance --------------------------------

    private static class Fp {
        String fingerprint = "", buildId = "", incremental = "", patch = "";
    }

    /**
     * Build a UNIQUE, still-coherent fingerprint from a base one: the model/brand/device/
     * Android version stay valid, but the build id, incremental and security-patch date are
     * randomized — so two clones on the same base device still have different fingerprints.
     * base = "brand/product/device:ver/BUILDID/INCREMENTAL:tags"
     */
    private static Fp genFingerprint(String base, Random r) {
        Fp fp = new Fp();
        try {
            int firstColon = base.indexOf(':');
            int lastColon = base.lastIndexOf(':');
            String pre = base.substring(0, firstColon);              // brand/product/device
            String mid = base.substring(firstColon + 1, lastColon);  // ver/BUILDID/INCREMENTAL
            String tags = base.substring(lastColon + 1);             // user/release-keys
            String[] m = mid.split("/");
            String ver = m.length > 0 ? m[0] : "13";
            String baseBuild = m.length > 1 ? m[1] : "TP1A.220624.014";
            String baseInc = m.length > 2 ? m[2] : "12345678";
            String prefix = baseBuild.contains(".") ? baseBuild.substring(0, baseBuild.indexOf('.')) : baseBuild;
            fp.buildId = prefix + "." + randDate(r) + "." + String.format("%03d", r.nextInt(1000));
            fp.incremental = randomizeIncremental(baseInc, r);
            fp.patch = randPatch(r);
            fp.fingerprint = pre + ":" + ver + "/" + fp.buildId + "/" + fp.incremental + ":" + tags;
        } catch (Throwable t) {
            fp.fingerprint = base;
            fp.buildId = "TP1A.220624.014";
            fp.incremental = digits(r, 8);
            fp.patch = "2023-06-05";
        }
        return fp;
    }

    private static String randDate(Random r) {
        int yy = 22 + r.nextInt(2);          // 2022-2023
        int mm = 1 + r.nextInt(12);
        int dd = 1 + r.nextInt(28);
        return String.format("%02d%02d%02d", yy, mm, dd);
    }

    private static String randPatch(Random r) {
        return String.format("2023-%02d-05", 1 + r.nextInt(12));
    }

    /** Keep the leading manufacturer letters, randomize the trailing chars. All-digit
     *  (Pixel-style) incrementals become random digits of the same length. */
    private static String randomizeIncremental(String base, Random r) {
        if (base == null || base.isEmpty()) return digits(r, 8);
        if (base.matches("\\d+")) return digits(r, base.length());
        int i = 0;
        while (i < base.length() && Character.isLetter(base.charAt(i))) i++;
        int keep = Math.min(Math.max(i, 4), base.length());
        StringBuilder sb = new StringBuilder(base.substring(0, keep));
        String cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int k = keep; k < base.length(); k++) sb.append(cs.charAt(r.nextInt(cs.length())));
        return sb.toString();
    }

    // Locally-administered, unicast MAC (bit1=1, bit0=0 in first octet).
    private static String localMac(Random r) {
        int[] o = new int[6];
        o[0] = (r.nextInt(256) & 0xFC) | 0x02;
        for (int i = 1; i < 6; i++) o[i] = r.nextInt(256);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", o[i]));
        }
        return sb.toString();
    }
}
