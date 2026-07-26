package top.niunaijun.blackbox.core;

import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Namespaces Android Keystore2 aliases at the Binder boundary.
 *
 * Android 12+ sends both AndroidKeyStore reads and key generation through these two stable AIDL
 * interfaces. Rewriting KeyDescriptor aliases here avoids ART method hooks in boot classes, which
 * are unsafe on Android 16, while still covering reads, writes, deletes, grants, wrapped-key
 * imports, attestation-key references and inventory calls.
 */
final class Keystore2ServiceIsolation {
    private static final String TAG = "Keystore2Isolation";
    private static final String SERVICE =
            "android.system.keystore2.IKeystoreService/default";
    private static final String SERVICE_STUB =
            "android.system.keystore2.IKeystoreService$Stub";
    private static final String DESCRIPTOR_CLASS =
            "android.system.keystore2.KeyDescriptor";
    private static final String DOMAIN_CLASS =
            "android.system.keystore2.Domain";

    private static volatile boolean sInstalled;
    private static String sPrefix;
    private static Class<?> sDescriptorClass;
    private static Field sDomainField;
    private static Field sAliasField;
    private static int sAppDomain;

    private Keystore2ServiceIsolation() {}

    static synchronized boolean install(String prefix) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        if (sInstalled) return prefix != null && prefix.equals(sPrefix);
        if (prefix == null || prefix.isEmpty()) return false;
        try {
            Class<?> descriptor = Class.forName(DESCRIPTOR_CLASS);
            Field domainField = descriptor.getField("domain");
            Field aliasField = descriptor.getField("alias");
            Class<?> domain = Class.forName(DOMAIN_CLASS);
            int appDomain = domain.getField("APP").getInt(null);

            IBinder binder = BRServiceManager.get().getService(SERVICE);
            if (binder == null) throw new IllegalStateException("Keystore2 service missing");
            Class<?> stubClass = Class.forName(SERVICE_STUB);
            Object base = stubClass.getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (base == null) throw new IllegalStateException("Keystore2 interface missing");

            sDescriptorClass = descriptor;
            sDomainField = domainField;
            sAliasField = aliasField;
            sAppDomain = appDomain;
            sPrefix = prefix;

            KeystoreBinderStub proxy = new KeystoreBinderStub(binder, base);
            proxy.injectHook();
            if (BRServiceManager.get().getService(SERVICE) != proxy) {
                throw new IllegalStateException("Keystore2 service replacement failed");
            }
            sInstalled = true;
            Slog.d(TAG, "per-clone Keystore2 Binder namespace active");
            return true;
        } catch (Throwable t) {
            sPrefix = null;
            Slog.w(TAG, "Keystore2 isolation unavailable: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static final class KeystoreBinderStub extends BinderInvocationStub {
        private final Object mService;

        KeystoreBinderStub(IBinder binder, Object service) {
            super(binder);
            mService = service;
        }

        @Override protected Object getWho() {
            return mService;
        }

        @Override protected void inject(Object baseInvocation, Object proxyInvocation) {
            replaceSystemService(SERVICE);
        }

        @Override public boolean isBadEnv() {
            return BRServiceManager.get().getService(SERVICE) != this;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return invokeIsolated(getBase(), method, args, true);
        }
    }

    private static final class SecurityLevelHandler implements InvocationHandler {
        private final Object mBase;

        SecurityLevelHandler(Object base) {
            mBase = base;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return invokeIsolated(mBase, method, args, false);
        }
    }

    private static Object invokeIsolated(
            Object base, Method method, Object[] args, boolean mainService) throws Throwable {
        List<AliasEdit> edits = new ArrayList<>();
        Object oldStart = null;
        boolean changedStart = false;
        try {
            protectDescriptors(args, edits);
            if (mainService && "listEntriesBatched".equals(method.getName())
                    && isAppInventoryCall(args) && args.length >= 3) {
                oldStart = args[2];
                args[2] = oldStart instanceof String
                        ? protect((String) oldStart) : beforePrefix();
                changedStart = true;
            }

            if (mainService && "getNumberOfEntries".equals(method.getName())
                    && isAppInventoryCall(args)) {
                return countVisibleEntries(base, method, args);
            }

            Object result = invokeBase(base, method, args);
            if (mainService && "getSecurityLevel".equals(method.getName())
                    && result != null) {
                Class<?>[] interfaces = MethodParameterUtils.getAllInterface(result.getClass());
                if (interfaces.length == 0) {
                    throw new SecurityException("Keystore security-level interface unavailable");
                }
                return Proxy.newProxyInstance(
                        result.getClass().getClassLoader(),
                        interfaces,
                        new SecurityLevelHandler(result));
            }
            if (mainService && ("listEntries".equals(method.getName())
                    || "listEntriesBatched".equals(method.getName()))
                    && isAppInventoryCall(args)) {
                return visibleDescriptors(result);
            }
            return result;
        } finally {
            for (int i = edits.size() - 1; i >= 0; i--) edits.get(i).restore();
            if (changedStart) args[2] = oldStart;
        }
    }

    private static Object invokeBase(Object base, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(base, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    private static void protectDescriptors(Object[] args, List<AliasEdit> edits)
            throws IllegalAccessException {
        if (args == null) return;
        for (Object arg : args) {
            if (arg == null) continue;
            if (sDescriptorClass.isInstance(arg)) {
                protectDescriptor(arg, edits);
            } else if (arg.getClass().isArray()
                    && sDescriptorClass.isAssignableFrom(arg.getClass().getComponentType())) {
                int length = Array.getLength(arg);
                for (int i = 0; i < length; i++) {
                    Object descriptor = Array.get(arg, i);
                    if (descriptor != null) protectDescriptor(descriptor, edits);
                }
            }
        }
    }

    private static void protectDescriptor(Object descriptor, List<AliasEdit> edits)
            throws IllegalAccessException {
        if (sDomainField.getInt(descriptor) != sAppDomain) return;
        Object value = sAliasField.get(descriptor);
        if (!(value instanceof String)) return;
        String alias = (String) value;
        String protectedAlias = protect(alias);
        if (!alias.equals(protectedAlias)) {
            edits.add(new AliasEdit(descriptor, alias));
            sAliasField.set(descriptor, protectedAlias);
        }
    }

    private static boolean isAppInventoryCall(Object[] args) {
        return args != null && args.length >= 2
                && args[0] instanceof Integer && ((Integer) args[0]) == sAppDomain;
    }

    private static String protect(String alias) {
        if (alias == null || alias.startsWith(sPrefix)) return alias;
        return sPrefix + alias;
    }

    /** AIDL's "startingPastAlias" is exclusive; this sorts immediately before our namespace. */
    private static String beforePrefix() {
        return sPrefix.substring(0, sPrefix.length() - 1);
    }

    private static Object visibleDescriptors(Object raw) throws IllegalAccessException {
        if (raw == null || !raw.getClass().isArray()) return raw;
        List<Object> visible = new ArrayList<>();
        int length = Array.getLength(raw);
        for (int i = 0; i < length; i++) {
            Object descriptor = Array.get(raw, i);
            if (descriptor == null || !sDescriptorClass.isInstance(descriptor)) continue;
            Object aliasValue = sAliasField.get(descriptor);
            if (!(aliasValue instanceof String)) continue;
            String alias = (String) aliasValue;
            if (!alias.startsWith(sPrefix)) continue;
            sAliasField.set(descriptor, alias.substring(sPrefix.length()));
            visible.add(descriptor);
        }
        Object out = Array.newInstance(raw.getClass().getComponentType(), visible.size());
        for (int i = 0; i < visible.size(); i++) Array.set(out, i, visible.get(i));
        return out;
    }

    private static int countVisibleEntries(Object base, Method countMethod, Object[] args)
            throws Throwable {
        Method batched = null;
        for (Method candidate : countMethod.getDeclaringClass().getMethods()) {
            if ("listEntriesBatched".equals(candidate.getName())
                    && candidate.getParameterTypes().length == 3) {
                batched = candidate;
                break;
            }
        }
        if (batched == null) {
            throw new SecurityException("Keystore inventory isolation unavailable");
        }

        int count = 0;
        String start = beforePrefix();
        while (true) {
            Object raw = invokeBase(base, batched, new Object[]{args[0], args[1], start});
            if (raw == null || !raw.getClass().isArray() || Array.getLength(raw) == 0) break;
            int length = Array.getLength(raw);
            String last = null;
            for (int i = 0; i < length; i++) {
                Object descriptor = Array.get(raw, i);
                if (descriptor == null || !sDescriptorClass.isInstance(descriptor)) continue;
                Object aliasValue = sAliasField.get(descriptor);
                if (!(aliasValue instanceof String)) continue;
                last = (String) aliasValue;
                if (last.startsWith(sPrefix)) count++;
            }
            if (last == null || !last.startsWith(sPrefix)) break;
            start = last;
        }
        return count;
    }

    private static final class AliasEdit {
        private final Object descriptor;
        private final String alias;

        AliasEdit(Object descriptor, String alias) {
            this.descriptor = descriptor;
            this.alias = alias;
        }

        void restore() {
            try {
                sAliasField.set(descriptor, alias);
            } catch (Throwable ignored) {
            }
        }
    }
}
