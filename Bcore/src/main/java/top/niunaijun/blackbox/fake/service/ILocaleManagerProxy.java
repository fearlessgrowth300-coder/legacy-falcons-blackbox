package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;
import android.os.LocaleList;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps Android 13+ per-app locale calls inside the virtual application boundary.
 *
 * The platform locale service validates that the requested package belongs to the calling UID.
 * A guest package runs under BlackBox's host UID, so forwarding the guest package causes
 * READ_APP_SPECIFIC_LOCALES SecurityException. Returning the process locale as the guest's
 * effective locale preserves the locale selected from its proxy profile without sharing the
 * host's per-app locale state between clones.
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    private static final String TAG = "ILocaleManagerProxy";
    private static final String SERVICE_NAME = "locale";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
            if (binder == null) return null;
            Class<?> stub = Class.forName("android.app.ILocaleManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable error) {
            Slog.w(TAG, "Locale service unavailable: " + error.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return new LocaleList(java.util.Locale.getDefault());
        }
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof LocaleList && !((LocaleList) arg).isEmpty()) {
                        java.util.Locale.setDefault(((LocaleList) arg).get(0));
                        break;
                    }
                }
            }
            return null;
        }
    }
}
