package top.niunaijun.blackbox.core;

import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-virtual-User and package namespace for AndroidKeyStore aliases.
 * Without this, every clone shares BlackBox's real host UID and can collide on another clone's
 * social-login encryption alias. Existing users are never migrated silently because hardware
 * backed AndroidKeyStore keys are deliberately non-exportable.
 */
public final class KeystoreIsolation {
    private static final String TAG = "KeystoreIsolation";
    private static final String MARKER = "keystore_isolation_v1.flag";
    private static final String PREFIX_ROOT = "bbx1_";
    private static volatile boolean sInstalled;

    private static final Set<String> ALIAS_METHODS = new HashSet<>();
    static {
        Collections.addAll(ALIAS_METHODS,
                "getAttributes", "getKey", "getCertificateChain", "getCertificate",
                "getCreationDate", "setKeyEntry", "setCertificateEntry", "deleteEntry",
                "containsAlias", "isKeyEntry", "isCertificateEntry", "getEntry", "setEntry",
                "entryInstanceOf");
    }

    private KeystoreIsolation() {}

    private static File marker(int userId) {
        return new File(BEnvironment.getUserDir(userId), MARKER);
    }

    public static boolean isEnabled(int userId) {
        return userId >= 0 && marker(userId).isFile();
    }

    public static boolean markNewUser(int userId) {
        return enableForUser(userId);
    }

    /** Opt-in marker only; it never reads, deletes, renames, or exports an existing key. */
    public static boolean enableForUser(int userId) {
        if (userId < 0) return false;
        try {
            File dir = BEnvironment.getUserDir(userId);
            FileUtils.mkdirs(dir.getAbsolutePath());
            File target = marker(userId);
            return target.isFile() || target.createNewFile();
        } catch (Throwable t) {
            Slog.w(TAG, "Could not enable User " + userId + ": " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Install after BlackBox reads its own proxy key and before guest Application code. */
    public static synchronized boolean installForCurrentProcess() {
        int userId = BActivityThread.getUserId();
        if (!isEnabled(userId)) return true;
        if (sInstalled) return true;
        // Android 12+ routes all AndroidKeyStore operations through stable Keystore2 AIDL.
        // Namespace there instead of hooking boot-class methods: Pine 0.3.0's ARM64 object bridge
        // is not compatible with Android 16 ART and can native-crash apps such as Instagram.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            String namespace = prefix();
            sInstalled = Keystore2ServiceIsolation.install(namespace);
            return sInstalled;
        }
        try {
            int operationHooks = 0;
            for (Method method : KeyStore.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (ALIAS_METHODS.contains(method.getName()) && params.length > 0
                        && params[0] == String.class) {
                    Pine.hook(method, new AliasArgumentHook());
                    operationHooks++;
                } else if ("aliases".equals(method.getName()) && params.length == 0) {
                    Pine.hook(method, new AliasEnumerationHook());
                } else if ("size".equals(method.getName()) && params.length == 0) {
                    Pine.hook(method, new SizeHook());
                } else if ("getCertificateAlias".equals(method.getName())) {
                    Pine.hook(method, new CertificateAliasHook());
                }
            }
            Pine.hook(KeyGenParameterSpec.class.getMethod("getKeystoreAlias"), new GeneratedAliasHook());
            // Added in newer Android releases; the core alias hook must remain usable on API 24-27.
            try {
                Pine.hook(KeyGenParameterSpec.class.getMethod("getAttestKeyAlias"), new GeneratedAliasHook());
            } catch (NoSuchMethodException ignored) {
                Slog.d(TAG, "Attestation alias API not present on this Android version");
            }
            Pine.hook(KeyPairGeneratorSpec.class.getMethod("getKeystoreAlias"), new GeneratedAliasHook());
            if (operationHooks < 10) throw new IllegalStateException("Incomplete KeyStore API coverage");
            sInstalled = true;
            Slog.d(TAG, "per-clone AndroidKeyStore namespace active; operations=" + operationHooks);
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "AndroidKeyStore isolation unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isAndroidKeyStore(Object object) {
        try {
            return object instanceof KeyStore
                    && "AndroidKeyStore".equalsIgnoreCase(((KeyStore) object).getType());
        } catch (Throwable ignored) { return false; }
    }

    private static String userPrefix(int userId) {
        return PREFIX_ROOT + "u" + userId + "_";
    }

    private static String prefix() {
        int userId = BActivityThread.getUserId();
        String pkg = BActivityThread.getAppPackageName();
        if (!isEnabled(userId) || pkg == null || pkg.trim().isEmpty()) return null;
        try {
            String seed = BlackBoxCore.getHostPkg() + "|" + pkg;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(userPrefix(userId));
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i]));
            return out.append('_').toString();
        } catch (Throwable t) {
            throw new SecurityException("Could not derive clone key namespace", t);
        }
    }

    /**
     * Remove only aliases owned by one virtual user. This runs in BlackBox's unhooked server
     * process before that user is deleted, so no guest can leave inaccessible hardware keys.
     */
    public static boolean deleteForUser(int userId) {
        if (userId < 0) return false;
        String ownedPrefix = userPrefix(userId);
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            List<String> owned = new ArrayList<>();
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias != null && alias.startsWith(ownedPrefix)) owned.add(alias);
            }
            for (String alias : owned) store.deleteEntry(alias);
            Slog.d(TAG, "Removed " + owned.size() + " aliases for deleted User " + userId);
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "Could not remove aliases for User " + userId + ": "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private static String protect(String alias) {
        if (alias == null) return null;
        String prefix = prefix();
        if (prefix == null || alias.startsWith(prefix)) return alias;
        return prefix + alias;
    }

    private static class AliasArgumentHook extends MethodHook {
        @Override public void beforeCall(Pine.CallFrame frame) {
            if (!isAndroidKeyStore(frame.thisObject) || frame.args == null || frame.args.length == 0) return;
            if (frame.args[0] instanceof String) frame.args[0] = protect((String) frame.args[0]);
        }
    }

    private static class GeneratedAliasHook extends MethodHook {
        @Override public void afterCall(Pine.CallFrame frame) {
            if (!frame.hasThrowable() && frame.getResult() instanceof String) {
                frame.setResult(protect((String) frame.getResult()));
            }
        }
    }

    private static class AliasEnumerationHook extends MethodHook {
        @Override public void afterCall(Pine.CallFrame frame) {
            if (!isAndroidKeyStore(frame.thisObject) || frame.hasThrowable()
                    || !(frame.getResult() instanceof Enumeration)) return;
            String prefix = prefix();
            if (prefix == null) return;
            List<String> visible = new ArrayList<>();
            Enumeration<?> raw = (Enumeration<?>) frame.getResult();
            while (raw.hasMoreElements()) {
                Object next = raw.nextElement();
                if (next instanceof String && ((String) next).startsWith(prefix)) {
                    visible.add(((String) next).substring(prefix.length()));
                }
            }
            frame.setResult(Collections.enumeration(visible));
        }
    }

    private static class SizeHook extends MethodHook {
        @Override public void afterCall(Pine.CallFrame frame) {
            if (!isAndroidKeyStore(frame.thisObject) || frame.hasThrowable()) return;
            try {
                int count = 0;
                Enumeration<String> aliases = ((KeyStore) frame.thisObject).aliases();
                while (aliases.hasMoreElements()) { aliases.nextElement(); count++; }
                frame.setResult(count);
            } catch (Throwable t) {
                frame.setThrowable(new SecurityException("Could not isolate KeyStore inventory", t));
            }
        }
    }

    private static class CertificateAliasHook extends MethodHook {
        @Override public void afterCall(Pine.CallFrame frame) {
            if (!isAndroidKeyStore(frame.thisObject) || frame.hasThrowable()
                    || !(frame.getResult() instanceof String)) return;
            String prefix = prefix();
            String alias = (String) frame.getResult();
            frame.setResult(prefix != null && alias.startsWith(prefix)
                    ? alias.substring(prefix.length()) : null);
        }
    }
}
