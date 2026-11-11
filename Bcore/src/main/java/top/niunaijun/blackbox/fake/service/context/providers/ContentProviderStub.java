package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
        // A guest calling its own provider is checked against the uid Binder really reports.
        // That has to be this process uid, paired with the guest package. Facebook rejects
        // the virtual uid and the host package.
        boolean sameApp = false;
        if (!"asBinder".equals(method.getName())) {
            boolean local = mBase != null && mBase.asBinder() instanceof Binder;
            String guest = BActivityThread.getAppPackageName();
            String host = BlackBoxCore.getHostPkg();
            boolean ownProvider = mProviderPackage != null && mProviderPackage.equals(guest);
            sameApp = ownProvider || ProviderCallIdentity.sameProcessProvider(local, guest, host);
            boolean virtual = mProviderPackage != null && host != null && !host.equals(mProviderPackage);
            int uid = sameApp
                    ? ProviderCallIdentity.presentedUid(true, BlackBoxCore.getHostUid(),
                    BActivityThread.getBUid())
                    : ProviderCallIdentity.attributionUid(Build.VERSION.SDK_INT, virtual,
                    BActivityThread.getBUid(), BlackBoxCore.getHostUid());
            String pkg = sameApp || virtual
                    ? ProviderCallIdentity.callerPackage(true, guest, host)
                    : host;
            if (pkg == null) throw new IllegalStateException("Provider caller is not initialized");
            if ("query".equals(method.getName()) || "call".equals(method.getName())
                    || "insert".equals(method.getName()) || "update".equals(method.getName())
                    || "delete".equals(method.getName())) {
                Slog.i(TAG, "provider " + method.getName()
                        + " sameApp=" + sameApp
                        + " virtual=" + virtual
                        + " attrUid=" + uid
                        + " callerPkg=" + pkg
                        + " providerPkg=" + mProviderPackage);
            }
            ProviderCallIdentity.rewriteLegacyCaller(args, pkg);
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                        // Do not recurse into the call Bundle: it is application data,
                        // not the Binder caller envelope.
                        Object[] envelope = {arg};
                        AttributionSourceUtils.fixAttributionSourceInArgs(envelope, uid, pkg);
                        args[i] = envelope[0];
                    }
                }
            }
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
            Throwable cause = error.getCause();
            // Returning 1/true/empty accounts after an exception misreports success.
            // Log only the operation and exception type, never provider data or tokens.
            Slog.w(TAG, "Provider operation failed: " + method.getName() + " ("
                    + (cause == null ? error.getClass().getSimpleName()
                    : cause.getClass().getSimpleName()) + ")");
            throw cause == null ? error : cause;
        } finally {
            if (cleared) {
                Binder.restoreCallingIdentity(identity);
                NativeCore.exitSelfProviderCall();
            }
        }
    }
}
