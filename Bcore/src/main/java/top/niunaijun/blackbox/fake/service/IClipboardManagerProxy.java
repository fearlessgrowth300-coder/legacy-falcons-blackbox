package top.niunaijun.blackbox.fake.service;

import android.content.ClipData;
import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.content.BRClipboardManager;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.CloneClipboardStore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * The clone clipboard never reaches ClipboardService. Android 16 dropped the
 * static service field, so the proxy has to be the IClipboard object
 * ServiceManager returns. A call that did reach the service was rejected
 * because the guest package does not belong to this process uid.
 */
public final class IClipboardManagerProxy extends BinderInvocationStub {
    private final Object mService;

    public IClipboardManagerProxy() {
        super(realBinder());
        mService = BinderInvocationStub.asInterface("android.content.IClipboard", realBinder());
    }

    private static IBinder realBinder() {
        return BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE);
    }

    @Override
    protected Object getWho() {
        return mService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CLIPBOARD_SERVICE);
        try {
            BRClipboardManager.get()._set_sService((IInterface) proxyInvocation);
        } catch (Throwable ignored) {
        }
        rebindStaticService(proxyInvocation);
    }

    @Override
    public boolean isBadEnv() {
        IBinder current = BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE);
        return current != this;
    }

    private static void rebindStaticService(Object proxy) {
        try {
            Field field = Class.forName("android.content.ClipboardManager").getDeclaredField("sService");
            field.setAccessible(true);
            field.set(null, proxy);
        } catch (Throwable ignored) {
        }
    }

    private static int userId() {
        return BActivityThread.getUserId();
    }

    private static String packageName() {
        return BActivityThread.getAppPackageName();
    }

    @ProxyMethod("setPrimaryClip")
    public static class SetPrimaryClip extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            CloneClipboardStore.set(userId(), packageName(), args != null && args.length > 0
                    && args[0] instanceof ClipData ? (ClipData) args[0] : null);
            return null;
        }
    }

    @ProxyMethod("setPrimaryClipAsPackage")
    public static class SetPrimaryClipAsPackage extends SetPrimaryClip {}

    @ProxyMethod("getPrimaryClip")
    public static class GetPrimaryClip extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return CloneClipboardStore.get(userId(), packageName());
        }
    }

    @ProxyMethod("getPrimaryClipDescription")
    public static class GetPrimaryClipDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            ClipData clip = CloneClipboardStore.get(userId(), packageName());
            return clip == null ? null : clip.getDescription();
        }
    }

    @ProxyMethod("hasPrimaryClip")
    public static class HasPrimaryClip extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return CloneClipboardStore.get(userId(), packageName()) != null;
        }
    }

    @ProxyMethod("hasClipboardText")
    public static class HasClipboardText extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            ClipData clip = CloneClipboardStore.get(userId(), packageName());
            return clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null;
        }
    }

    @ProxyMethod("clearPrimaryClip")
    public static class ClearPrimaryClip extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            CloneClipboardStore.clear(userId(), packageName());
            return null;
        }
    }

    @ProxyMethod("getPrimaryClipSource")
    public static class GetPrimaryClipSource extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return CloneClipboardStore.get(userId(), packageName()) == null ? null : packageName();
        }
    }

    /** Listener binders are deliberately kept out of the real clipboard service. */
    @ProxyMethod("addPrimaryClipChangedListener")
    public static class AddPrimaryClipChangedListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("removePrimaryClipChangedListener")
    public static class RemovePrimaryClipChangedListener extends AddPrimaryClipChangedListener {}

    @ProxyMethod("areClipboardAccessNotificationsEnabledForUser")
    public static class AreClipboardAccessNotificationsEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("setClipboardAccessNotificationsEnabledForUser")
    public static class SetClipboardAccessNotificationsEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }
}
