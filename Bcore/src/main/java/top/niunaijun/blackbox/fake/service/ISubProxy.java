package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRISubStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * Isolates Android's physical subscription database from virtual applications.
 *
 * Modern Google Play Services queries ISub during check-in. Passing that call to the phone's
 * real service both requires a privileged permission the host cannot hold and would expose SIM
 * subscription metadata across every clone. A BlackBox user has no physical subscription, so
 * the privacy-safe model is an empty subscription set.
 */
public class ISubProxy extends BinderInvocationStub {
    private static final String SERVICE_NAME = "isub";

    public ISubProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        return BRISubStub.get().asInterface(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getActiveSubIdList")
    public static class GetActiveSubIdList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new int[0];
        }
    }

    @ProxyMethod("getActiveSubscriptionInfoList")
    public static class GetActiveSubscriptionInfoList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("getAllSubInfoList")
    public static class GetAllSubInfoList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("getAvailableSubscriptionInfoList")
    public static class GetAvailableSubscriptionInfoList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("getAccessibleSubscriptionInfoList")
    public static class GetAccessibleSubscriptionInfoList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("getActiveSubInfoCount")
    public static class GetActiveSubInfoCount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getActiveSubscriptionInfo")
    public static class GetActiveSubscriptionInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getActiveSubscriptionInfoForIccId")
    public static class GetActiveSubscriptionInfoForIccId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getActiveSubscriptionInfoForSimSlotIndex")
    public static class GetActiveSubscriptionInfoForSimSlotIndex extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }
}
