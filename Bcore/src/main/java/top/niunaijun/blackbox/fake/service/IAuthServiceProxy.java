package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;
import java.lang.reflect.Method;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Isolated profiles do not own the host's biometric enrollment or credential
 * vault. Report unsupported authentication, never success or the host identity.
 */
public final class IAuthServiceProxy extends BinderInvocationStub {
    private static final String SERVICE = "auth";
    private static final int HW_UNAVAILABLE = 1;

    public IAuthServiceProxy() { super(BRServiceManager.get().getService(SERVICE)); }

    @Override protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        if (binder == null) return null;
        try {
            return Class.forName("android.hardware.biometrics.IAuthService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException error) {
            Slog.w("IAuthServiceProxy", "Biometric adapter unavailable");
            return null;
        }
    }

    @Override protected void inject(Object base, Object proxy) { replaceSystemService(SERVICE); }
    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (BActivityThread.getAppConfig() == null) return super.invoke(proxy, method, args);
        switch (method.getName()) {
            case "canAuthenticate":
                return HW_UNAVAILABLE;
            case "hasEnrolledBiometrics":
                return false;
            case "authenticate":
                if (args == null || args.length < 4 || args[3] == null) {
                    throw new IllegalStateException("Biometric error receiver is missing");
                }
                // Deliver the documented error callback so the guest can show an
                // error or choose its own fallback instead of waiting forever.
                Class.forName("android.hardware.biometrics.IBiometricServiceReceiver")
                        .getMethod("onError", int.class, int.class, int.class)
                        .invoke(args[3], 0, HW_UNAVAILABLE, 0);
                Slog.i("IAuthServiceProxy", "Reported unavailable biometrics to isolated guest");
                return method.getReturnType() == long.class ? Long.valueOf(-1L) : null;
            case "cancelAuthentication":
                return null; // No host authentication session was started.
            default:
                return super.invoke(proxy, method, args);
        }
    }
}
