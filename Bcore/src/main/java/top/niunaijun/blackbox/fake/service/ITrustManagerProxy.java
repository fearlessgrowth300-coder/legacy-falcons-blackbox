package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;
import java.lang.reflect.Method;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/**
 * A cloned Google account screen asks for the phone's screen lock before adding an account.
 * Clones cannot show that prompt (see IAuthServiceProxy), so the request failed and sign-in
 * closed at once. Cloned Google apps see a device without a screen lock and skip the prompt.
 */
public final class ITrustManagerProxy extends BinderInvocationStub {
    private static final String SERVICE = "trust";

    public ITrustManagerProxy() { super(BRServiceManager.get().getService(SERVICE)); }

    @Override protected Object getWho() {
        return asInterface("android.app.trust.ITrustManager",
                BRServiceManager.get().getService(SERVICE));
    }

    @Override protected void inject(Object base, Object proxy) { replaceSystemService(SERVICE); }
    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (("isDeviceSecure".equals(name) || "isDeviceLocked".equals(name))
                && BActivityThread.getAppConfig() != null
                && GmsCore.isGoogleAppOrService(BActivityThread.getAppPackageName())) {
            return false;
        }
        return super.invoke(proxy, method, args);
    }
}
