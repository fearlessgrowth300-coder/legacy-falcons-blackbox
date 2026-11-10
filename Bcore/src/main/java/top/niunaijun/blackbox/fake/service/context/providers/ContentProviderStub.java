package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.Build;
import android.os.IInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
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
        // A guest calling its own provider stays inside this process, where Binder reports the
        // real uid. Rewriting the caller there makes apps such as Facebook reject themselves.
        boolean ownProvider = mProviderPackage != null
                && mProviderPackage.equals(BActivityThread.getAppPackageName());
        if (!ownProvider && !"asBinder".equals(method.getName())) {
            boolean virtual = mProviderPackage != null
                    && !BlackBoxCore.getHostPkg().equals(mProviderPackage);
            // This process sends the transaction. Its original launcher is unrelated.
            // GMS and GSF are virtual providers, so they must see this caller's uid,
            // the same uid Binder.getCallingUid returns in their process.
            int uid = ProviderCallIdentity.attributionUid(Build.VERSION.SDK_INT, virtual,
                    BActivityThread.getBUid(), BlackBoxCore.getHostUid());
            String pkg = virtual ? BActivityThread.getAppPackageName() : BlackBoxCore.getHostPkg();
            if (pkg == null) throw new IllegalStateException("Provider caller is not initialized");
            if ("query".equals(method.getName()) || "call".equals(method.getName())
                    || "insert".equals(method.getName())) {
                Slog.i(TAG, "provider " + method.getName()
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
        }
    }
}
