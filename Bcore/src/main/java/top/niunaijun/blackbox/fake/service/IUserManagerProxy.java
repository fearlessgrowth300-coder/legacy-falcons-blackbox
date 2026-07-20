package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRUserInfo;
import black.android.os.BRIUserManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IUserManagerProxy extends BinderInvocationStub {
    public IUserManagerProxy() {
        super(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIUserManagerStub.get().asInterface(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isMainUser")
    public static class IsMainUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Android's real UserManager rejects the host process because the caller does not
            // hold cross-user permissions.  Expose only the current BlackBox workspace role;
            // never query or disclose profiles belonging to the physical phone owner.
            return BActivityThread.getUserId() == 0;
        }
    }

    @ProxyMethod("isSystemUser")
    public static class IsSystemUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BActivityThread.getUserId() == 0;
        }
    }

    @ProxyMethod("isAdminUser")
    public static class IsAdminUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BActivityThread.getUserId() == 0;
        }
    }

    @ProxyMethod("isGuestUser")
    public static class IsGuestUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("isRestricted")
    public static class IsRestricted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("isProfile")
    public static class IsProfile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("isUserOfType")
    public static class IsUserOfType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String requestedType = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) requestedType = (String) arg;
                }
            }
            if (requestedType == null) return false;
            if (BActivityThread.getUserId() == 0) {
                return "android.os.usertype.full.SYSTEM".equals(requestedType);
            }
            return "android.os.usertype.full.SECONDARY".equals(requestedType);
        }
    }

    @ProxyMethod("getUserType")
    public static class GetUserType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BActivityThread.getUserId() == 0
                    ? "android.os.usertype.full.SYSTEM"
                    : "android.os.usertype.full.SECONDARY";
        }
    }

    @ProxyMethod("getSeedAccountName")
    public static class GetSeedAccountName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getSeedAccountType")
    public static class GetSeedAccountType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getSeedAccountOptions")
    public static class GetSeedAccountOptions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Seed account data is provisioning state for the physical Android user.  A fresh
            // virtual workspace has none; returning null also avoids exposing the owner's data.
            return null;
        }
    }

    @ProxyMethod("someUserHasSeedAccount")
    public static class SomeUserHasSeedAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("someUserHasAccount")
    public static class SomeUserHasAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("clearSeedAccountData")
    public static class ClearSeedAccountData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Restrictions belong to the real Android user and must never bleed into a virtual
            // BlackBox user. There is currently no virtual device-policy owner, so the isolated
            // and privacy-safe result is an empty per-user policy bundle.
            return new Bundle();
        }
    }

    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new Bundle();
        }
    }

    @ProxyMethod("getProfileParent")
    public static class GetProfileParent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object blackBox = BRUserInfo.get()._new(BActivityThread.getUserId(), "BlackBox", BRUserInfo.get().FLAG_PRIMARY());
            return blackBox;
        }
    }

    @ProxyMethod("getUsers")
    public static class getUsers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }
}
