package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.view.WindowManager;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;



public class IWindowSessionProxy extends BinderInvocationStub {
    public static final String TAG = "WindowSessionStub";

    private IInterface mSession;

    public IWindowSessionProxy(IInterface session) {
        super(session.asBinder());
        mSession = session;
    }

    @Override
    protected Object getWho() {
        return mSession;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object getProxyInvocation() {
        return super.getProxyInvocation();
    }

    private static void applyVirtualWindowCompatibility(WindowManager.LayoutParams layoutParams) {
        if (layoutParams == null) {
            return;
        }

        // Instagram's challenge and messaging screens place their editor inside a scrolling
        // container. On recent Android releases an ADJUST_RESIZE relayout of a virtual activity
        // causes that editor to be replaced while the IME animation is starting. The replacement
        // closes the fresh InputConnection, so the keyboard flashes and immediately disappears.
        // Panning keeps the editor instance and its per-process InputConnection alive.
        if ("com.instagram.android".equals(BActivityThread.getAppPackageName())) {
            int adjustment = layoutParams.softInputMode
                    & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
            if (adjustment == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                layoutParams.softInputMode = (layoutParams.softInputMode
                        & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            }
        }
    }

    @ProxyMethod("addToDisplay")
    public static class AddToDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) arg;
                    lp.packageName = BlackBoxCore.getHostPkg();
                    applyVirtualWindowCompatibility(lp);
                    if (BlackBoxCore.get().isDisableFlagSecure()) {
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("addToDisplayAsUser")
    public static class AddToDisplayAsUser extends AddToDisplay {
    }

    @ProxyMethod("relayout")
    public static class Relayout extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) arg;
                    applyVirtualWindowCompatibility(lp);
                    if (BlackBoxCore.get().isDisableFlagSecure()) {
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("relayoutAsync")
    public static class RelayoutAsync extends Relayout {
    }
}
