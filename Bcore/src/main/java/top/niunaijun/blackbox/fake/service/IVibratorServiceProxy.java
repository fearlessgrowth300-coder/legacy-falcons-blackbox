package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRIVibratorManagerServiceStub;
import black.android.os.BRServiceManager;
import black.com.android.internal.os.BRIVibratorServiceStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IVibratorServiceProxy extends BinderInvocationStub {
    private static final String TAG = "IVibratorServiceProxy";
    private static String NAME;
    static {
        if (BuildCompat.isS()) {
            NAME = "vibrator_manager";
        } else {
            NAME = Context.VIBRATOR_SERVICE;
        }
    }

    public IVibratorServiceProxy() {
        super(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected Object getWho() {
        IBinder service = BRServiceManager.get().getService(NAME);
        if (BuildCompat.isS()) {
            return BRIVibratorManagerServiceStub.get().asInterface(service);
        }
        return BRIVibratorServiceStub.get().asInterface(service);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceFirstUid(args);
        MethodParameterUtils.replaceFirstAppPkg(args);
        // Every vibrate/haptic entry point takes the requesting uid as its first argument on all
        // supported API levels. The framework only lets a caller vibrate on behalf of a DIFFERENT
        // uid when it holds UPDATE_APP_OPS_STATS, which no container process has. replaceFirstUid
        // only rewrites a value equal to the virtual uid, so anything else the guest passes here
        // reaches the system unchanged and comes back as
        //   SecurityException: uid <host> does not have android.permission.UPDATE_APP_OPS_STATS
        // on the guest's MAIN thread. Instagram vibrates the moment it captures a verification
        // selfie, so that killed the clone mid-check and dropped the user back to the feed.
        if (isHapticCall(method.getName()) && args != null && args.length > 0
                && args[0] instanceof Integer) {
            args[0] = android.os.Process.myUid();
        }
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            // Haptic feedback is cosmetic. Losing a buzz is always better than killing the guest,
            // so absorb it here rather than letting it reach app code.
            Slog.w(TAG, "vibrator call " + method.getName() + " denied: " + e.getMessage());
            return emptyResult(method);
        }
    }

    private static boolean isHapticCall(String name) {
        return name.startsWith("vibrate") || name.startsWith("performHapticFeedback");
    }

    private static Object emptyResult(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
