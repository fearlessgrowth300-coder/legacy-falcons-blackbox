package top.niunaijun.blackbox.fake.service.context.providers;

import android.content.ContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;
import top.niunaijun.blackbox.utils.Slog;

/** Adapts only the IPC caller envelope; provider payloads and failures stay intact. */
public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mProviderPackage;

    public IInterface wrapper(IInterface provider, String providerPackage) {
        mBase = provider;
        mProviderPackage = providerPackage;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override protected Object getWho() { return mBase; }
    @Override protected void inject(Object base, Object proxy) {}
    @Override public boolean isBadEnv() { return false; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        // A guest calling its own provider is checked against the uid Binder really reports.
        // That has to be this process uid, paired with the guest package. Facebook rejects
        // the virtual uid and the host package.
        boolean local = mBase != null && mBase.asBinder() instanceof Binder;
        String guest = BActivityThread.getAppPackageName();
        String host = BlackBoxCore.getHostPkg();
        ContentProvider provider = localProvider(mBase);
        String providerPackage = providerPackage(provider, guest);
        boolean sameApp = ProviderCallIdentity.selfProvider(providerPackage, guest, host, local);
        boolean virtual = providerPackage != null && host != null && !host.equals(providerPackage);
        int uid = sameApp
                ? ProviderCallIdentity.presentedUid(true, BlackBoxCore.getHostUid(),
                BActivityThread.getBUid())
                : ProviderCallIdentity.attributionUid(Build.VERSION.SDK_INT, virtual,
                BActivityThread.getBUid(), BlackBoxCore.getHostUid());
        String pkg = sameApp || virtual
                ? ProviderCallIdentity.callerPackage(true, guest, host)
                : host;
        if (pkg == null) throw new IllegalStateException("Provider caller is not initialized");
        if (LocalProviderCall.isDirectCall(method.getName())) {
            Slog.i(TAG, "provider " + method.getName()
                    + " sameApp=" + sameApp
                    + " virtual=" + virtual
                    + " attrUid=" + uid
                    + " callerPkg=" + pkg
                    + " providerPkg=" + providerPackage);
        }
        ProviderCallIdentity.rewriteLegacyCaller(args, pkg);
        Object attribution = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                    // Do not recurse into the call Bundle: it is application data,
                    // not the Binder caller envelope.
                    Object[] envelope = {arg};
                    AttributionSourceUtils.fixAttributionSourceInArgs(envelope, uid, pkg);
                    args[i] = envelope[0];
                    if (attribution == null) attribution = args[i];
                }
            }
        }
        if (sameApp && LocalProviderCall.isDirectCall(method.getName()) && provider != null) {
            Object direct = invokeLocal(provider, method.getName(), args, attribution, pkg);
            if (direct != LocalProviderCall.NO_MATCH) return direct;
        }
        long identity = 0;
        boolean cleared = false;
        if (sameApp) {
            NativeCore.enterSelfProviderCall();
            identity = Binder.clearCallingIdentity();
            cleared = true;
        }
        try {
            return method.invoke(mBase, args);
        } catch (InvocationTargetException error) {
            throw unwrap(method, error);
        } finally {
            if (cleared) {
                Binder.restoreCallingIdentity(identity);
                NativeCore.exitSelfProviderCall();
            }
        }
    }

    /**
     * Calls the provider implementation directly so Transport does not replace the
     * caller with the host uid. Returns {@link LocalProviderCall#NO_MATCH} when this
     * API's method shape is not on the provider and the Binder path must be used.
     */
    private Object invokeLocal(ContentProvider provider, String name, Object[] args,
                               Object attribution, String packageName) throws Throwable {
        Object[] directArgs = LocalProviderCall.directArguments(args);
        Method direct = match(provider.getClass(), name, directArgs);
        if (direct == null) {
            Object[] withoutSignal = LocalProviderCall.withoutTrailingSignal(directArgs);
            if (withoutSignal != directArgs) {
                direct = match(provider.getClass(), name, withoutSignal);
                directArgs = withoutSignal;
            }
        }
        if (direct == null) return LocalProviderCall.NO_MATCH;
        Runnable restoreCaller = installCaller(provider, packageName, attribution);
        NativeCore.enterSelfProviderCall();
        long identity = Binder.clearCallingIdentity();
        try {
            // Values exactly as the guest's provider code reads them, after
            // clearCallingIdentity and before the provider method runs.
            Slog.i(TAG, "self-call " + name
                    + " callingUid=" + Binder.getCallingUid()
                    + " callingPid=" + Binder.getCallingPid()
                    + " myUid=" + android.os.Process.myUid()
                    + " myPid=" + android.os.Process.myPid()
                    + " javaDepth=" + NativeCore.javaSelfProviderDepth()
                    + " nativeDepth=" + NativeCore.selfProviderNativeDepth());
            direct.setAccessible(true);
            return direct.invoke(provider, directArgs);
        } catch (InvocationTargetException error) {
            throw unwrap(direct, error);
        } finally {
            Binder.restoreCallingIdentity(identity);
            NativeCore.exitSelfProviderCall();
            restoreCaller.run();
        }
    }

    private static Throwable unwrap(Method method, InvocationTargetException error) {
        Throwable cause = error.getCause();
        // Returning 1/true/empty accounts after an exception misreports success.
        // Log only the operation and exception type, never provider data or tokens.
        Slog.w(TAG, "Provider operation failed: " + method.getName() + " ("
                + (cause == null ? error.getClass().getSimpleName()
                : cause.getClass().getSimpleName()) + ")");
        return cause == null ? error : cause;
    }

    private String providerPackage(ContentProvider provider, String guest) {
        if (guest != null && mProviderPackage != null && guest.equals(mProviderPackage)) {
            return guest;
        }
        if (provider != null) {
            try {
                android.content.Context context = provider.getContext();
                if (context != null && guest != null && guest.equals(context.getPackageName())) {
                    return guest;
                }
            } catch (Throwable ignored) {
            }
        }
        return mProviderPackage;
    }

    /** The ContentProvider behind a local Transport. Remote providers have none. */
    static ContentProvider localProvider(IInterface transport) {
        if (transport == null) return null;
        Object target = transport.asBinder();
        if (target == null) target = transport;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                boolean providerType = ContentProvider.class.isAssignableFrom(field.getType());
                if (!providerType && !"mInterface".equals(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof ContentProvider) return (ContentProvider) value;
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Method match(Class<?> type, String name, Object[] args) {
        if (args == null) args = new Object[0];
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != args.length) continue;
                boolean compatible = true;
                for (int i = 0; i < params.length; i++) {
                    if (args[i] == null) continue;
                    if (params[i].isPrimitive()) {
                        compatible = boxed(params[i], args[i]);
                    } else {
                        compatible = params[i].isInstance(args[i]);
                    }
                    if (!compatible) break;
                }
                if (compatible) return method;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static boolean boxed(Class<?> primitive, Object value) {
        if (primitive == int.class) return value instanceof Integer;
        if (primitive == long.class) return value instanceof Long;
        if (primitive == boolean.class) return value instanceof Boolean;
        return false;
    }

    /** Makes getCallingPackage and getCallingAttributionTag name this guest. */
    private static Runnable installCaller(Object provider, String packageName, Object attribution) {
        List<Runnable> restores = new ArrayList<>();
        setThreadLocal(provider, "mCallingAttributionSource", attribution, restores);
        setThreadLocal(provider, "mCallingPackage", packageName, restores);
        return () -> {
            for (int i = restores.size() - 1; i >= 0; i--) restores.get(i).run();
        };
    }

    private static void setThreadLocal(Object provider, String fieldName, Object value,
                                       List<Runnable> restores) {
        Field field = findField(provider.getClass(), fieldName);
        if (field == null || value == null) return;
        try {
            field.setAccessible(true);
            Object local = field.get(provider);
            if (!(local instanceof ThreadLocal)) return;
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> threadLocal = (ThreadLocal<Object>) local;
            Object previous = threadLocal.get();
            threadLocal.set(value);
            restores.add(() -> {
                if (previous == null) threadLocal.remove();
                else threadLocal.set(previous);
            });
        } catch (Throwable error) {
            Slog.w(TAG, "caller " + fieldName + " not set: " + error.getClass().getSimpleName());
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
