package top.niunaijun.blackbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Per-virtual-user isolation switches. Missing keys use the isolated defaults:
 * private media, private Google providers, and no direct network.
 */
public final class ProfilePolicy {
    public static final String FILE_NAME = "profile_policy.properties";

    public final boolean shareHostMedia;
    public final boolean shareHostGoogleProviders;
    public final boolean requireProxy;

    public ProfilePolicy(boolean shareHostMedia, boolean shareHostGoogleProviders, boolean requireProxy) {
        this.shareHostMedia = shareHostMedia;
        this.shareHostGoogleProviders = shareHostGoogleProviders;
        this.requireProxy = requireProxy;
    }

    public static ProfilePolicy defaults() {
        return new ProfilePolicy(false, false, true);
    }

    public static File fileFor(File userDir) {
        return new File(userDir, FILE_NAME);
    }

    public static ProfilePolicy load(File file) {
        Properties properties = new Properties();
        if (file != null && file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            } catch (Throwable ignored) {
                return defaults();
            }
        }
        return parse(properties);
    }

    public static ProfilePolicy parse(Properties properties) {
        if (properties == null) return defaults();
        return new ProfilePolicy(
                flag(properties, "shareHostMedia", false),
                flag(properties, "shareHostGoogleProviders", false),
                flag(properties, "requireProxy", true));
    }

    public static boolean store(File file, ProfilePolicy policy) {
        if (file == null || policy == null) return false;
        Properties properties = new Properties();
        properties.setProperty("shareHostMedia", Boolean.toString(policy.shareHostMedia));
        properties.setProperty("shareHostGoogleProviders", Boolean.toString(policy.shareHostGoogleProviders));
        properties.setProperty("requireProxy", Boolean.toString(policy.requireProxy));
        File parent = file.getParentFile();
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                properties.store(out, "per-profile isolation");
            }
            return true;
        } catch (Throwable ignored) {
            return false;
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
