package top.niunaijun.blackbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-virtual-user isolation switches. Missing keys use the isolated defaults:
 * private media, private Google providers, and no direct network.
 */
public final class ProfilePolicy {
    public static final String FILE_NAME = "profile_policy.properties";

    public final boolean shareHostMedia;
    public final boolean shareHostGoogleProviders;
    public final boolean requireProxy;
    /** Per-clone sensor calibration. Missing on older profiles means enabled. */
    public final boolean sensorIsolation;

    public ProfilePolicy(boolean shareHostMedia, boolean shareHostGoogleProviders,
                         boolean requireProxy, boolean sensorIsolation) {
        this.shareHostMedia = shareHostMedia;
        this.shareHostGoogleProviders = shareHostGoogleProviders;
        this.requireProxy = requireProxy;
        this.sensorIsolation = sensorIsolation;
    }

    public ProfilePolicy(boolean shareHostMedia, boolean shareHostGoogleProviders, boolean requireProxy) {
        this(shareHostMedia, shareHostGoogleProviders, requireProxy, true);
    }

    public static ProfilePolicy defaults() {
        return new ProfilePolicy(false, false, true, true);
    }

    public static File fileFor(File userDir) {
        return new File(userDir, FILE_NAME);
    }

    public static ProfilePolicy load(File file) {
        Properties properties = new Properties();
        boolean existed = file != null && file.isFile();
        if (existed) {
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            } catch (Throwable error) {
                Slog.w("ProfilePolicy", "could not read " + file + ": " + error);
                return defaults();
            }
        }
        ProfilePolicy policy = parse(properties);
        boolean missingSensor = !existed || properties.getProperty("sensorIsolation") == null;
        File parent = file == null ? null : file.getParentFile();
        if (missingSensor && parent != null && parent.isDirectory()) {
            boolean wrote = store(file, policy);
            Slog.w("ProfilePolicy", "sensorIsolation=" + policy.sensorIsolation
                    + " path=" + file.getAbsolutePath() + " wrote=" + wrote);
        }
        return policy;
    }

    public static ProfilePolicy parse(Properties properties) {
        if (properties == null) return defaults();
        return new ProfilePolicy(
                flag(properties, "shareHostMedia", false),
                flag(properties, "shareHostGoogleProviders", false),
                flag(properties, "requireProxy", true),
                flag(properties, "sensorIsolation", true));
    }

    public static boolean store(File file, ProfilePolicy policy) {
        if (file == null || policy == null) return false;
        Properties properties = new Properties();
        properties.setProperty("shareHostMedia", Boolean.toString(policy.shareHostMedia));
        properties.setProperty("shareHostGoogleProviders", Boolean.toString(policy.shareHostGoogleProviders));
        properties.setProperty("requireProxy", Boolean.toString(policy.requireProxy));
        properties.setProperty("sensorIsolation", Boolean.toString(policy.sensorIsolation));
        File parent = file.getParentFile();
        File temporary = null;
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }
            temporary = File.createTempFile("profile_policy", ".tmp", parent);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                properties.store(out, "per-profile isolation");
                out.getFD().sync();
            }
            if (!temporary.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    properties.store(out, "per-profile isolation");
                    out.getFD().sync();
                }
            }
            return true;
        } catch (Throwable error) {
            Slog.w("ProfilePolicy", "could not write " + file + ": " + error);
            return false;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.equals(file)) {
                temporary.delete();
            }
        }
    }

    private static boolean flag(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        String normalized = value.trim();
        if ("1".equals(normalized)) return true;
        if ("0".equals(normalized)) return false;
        if ("true".equalsIgnoreCase(normalized)) return true;
        if ("false".equalsIgnoreCase(normalized)) return false;
        return fallback;
    }
}
