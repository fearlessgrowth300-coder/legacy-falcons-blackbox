package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Spell-checker calls carry the caller package. The shared rewrite presents the host. */
public class ITextServicesManagerProxy extends BinderInvocationStub {
    public ITextServicesManagerProxy() {
        super(realBinder());
    }

    private static IBinder realBinder() {
        return BRServiceManager.get().getService("textservices");
    }

    @Override
    protected Object getWho() {
        return top.niunaijun.blackbox.fake.hook.BinderInvocationStub.asInterface(
                "com.android.internal.textservice.ITextServicesManager", realBinder());
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("textservices");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
