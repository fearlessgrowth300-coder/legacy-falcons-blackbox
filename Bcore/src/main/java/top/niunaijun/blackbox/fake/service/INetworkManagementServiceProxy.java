package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.os.BRINetworkManagementServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class INetworkManagementServiceProxy extends BinderInvocationStub {
    public static final String NAME = "network_management";

    public INetworkManagementServiceProxy() {
        super(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected Object getWho() {
        return BRINetworkManagementServiceStub.get().asInterface(BRServiceManager.get().getService(NAME));
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
    protected void onBindMethod() {
        super.onBindMethod();
        // A guest must never mutate policy for the container's real Linux UID: that UID is shared
        // by every virtual user, and Android reserves these calls for the system network stack.
        // Socket-level proxy enforcement remains active independently of these advisory policies.
        addMethodHook(new NoOpUidPolicy("setUidCleartextNetworkPolicy"));
        addMethodHook(new NoOpUidPolicy("setUidMeteredNetworkBlacklist"));
        addMethodHook(new NoOpUidPolicy("setUidMeteredNetworkWhitelist"));
    }

    private static final class NoOpUidPolicy extends MethodHook {
        private final String name;

        NoOpUidPolicy(String name) {
            this.name = name;
        }

        @Override
        protected String getMethodName() {
            return name;
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("getNetworkStatsUidDetail")
    public static class getNetworkStatsUidDetail extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstUid(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
}
