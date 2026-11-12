package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/**
 * A system service with no special behavior. Calls still pass through
 * {@code ClassInvocationStub}, which presents the host package to the phone.
 */
public final class HostPackageServiceProxy extends BinderInvocationStub {
    private final String mServiceName;
    private final Object mService;

    public HostPackageServiceProxy(String serviceName, String stubClass) {
        super(binder(serviceName));
        mServiceName = serviceName;
        mService = BinderInvocationStub.asInterface(stubClass, binder(serviceName));
    }

    private static IBinder binder(String serviceName) {
        return BRServiceManager.get().getService(serviceName);
    }

    @Override
    protected Object getWho() {
        return mService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(mServiceName);
    }

    @Override
    public boolean isBadEnv() {
        IBinder current = BRServiceManager.get().getService(mServiceName);
        return current != this;
    }
}
