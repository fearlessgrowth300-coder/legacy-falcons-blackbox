package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;


/**
 * The input method rejects a client whose {@code EditorInfo.packageName} is not
 * owned by this process uid. The proxy makes those calls visible so the caller
 * package can be rewritten to the host. {@code getPackageName()} is unchanged.
 */
public class IInputMethodManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IInputMethodManagerProxy";
    private final Object mService;

    public IInputMethodManagerProxy() {
        super(realBinder());
        mService = asInterface("com.android.internal.view.IInputMethodManager", realBinder());
    }

    private static IBinder realBinder() {
        return BRServiceManager.get().getService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    protected Object getWho() {
        return mService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.INPUT_METHOD_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            setStatic("android.view.inputmethod.IInputMethodManagerGlobalInvoker",
                    new String[] {"sServiceCache", "sService"}, proxyInvocation);
        }
        rebindInputMethodManagers(proxyInvocation);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    static Object asInterface(String stubClass, IBinder binder) {
        if (binder == null || stubClass == null) return null;
        try {
            Class<?> stub = Class.forName(stubClass + "$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable error) {
            Log.w(TAG, "input method service is unavailable");
            return null;
        }
    }

    private static void setStatic(String className, String[] fieldNames, Object value) {
        try {
            Class<?> type = Class.forName(className);
            for (String name : fieldNames) {
                Field field = findField(type, name);
                if (field == null) continue;
                field.set(null, value);
            }
        } catch (Throwable error) {
            Log.w(TAG, "could not install " + className);
        }
    }

    /** Older releases keep the service on each InputMethodManager instance. */
    private static void rebindInputMethodManagers(Object service) {
        try {
            Class<?> type = Class.forName("android.view.inputmethod.InputMethodManager");
            Field single = findField(type, "sInstance");
            if (single != null) {
                Object instance = single.get(null);
                if (instance instanceof Map) {
                    for (Object value : ((Map<?, ?>) instance).values()) {
                        setService(value, service);
                    }
                } else {
                    setService(instance, service);
                }
            }
            Field map = findField(type, "sInstanceMap");
            if (map != null) {
                Object value = map.get(null);
                if (value instanceof Map) {
                    for (Object instance : ((Map<?, ?>) value).values()) {
                        setService(instance, service);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "could not rebind InputMethodManager");
        }
    }

    private static void setService(Object instance, Object service) {
        if (instance == null || !(service instanceof IInterface)) return;
        Field field = findField(instance.getClass(), "mService");
        if (field == null) return;
        try {
            field.set(instance, service);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
