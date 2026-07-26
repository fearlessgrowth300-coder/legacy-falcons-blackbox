package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.role.BRIRoleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps Android's role checks at the virtual-package boundary.
 *
 * The physical RoleService verifies that a requested package belongs to the calling UID. Guest
 * packages intentionally run under BlackBox's host UID, so forwarding a guest package makes
 * Android 16 throw SecurityException. BlackBox does not currently expose virtual default-app
 * roles, therefore a guest is never the holder of a physical system role.
 */
public class IRoleManagerProxy extends BinderInvocationStub {
    private static final String TAG = "IRoleManagerProxy";
    private static final String SERVICE_NAME = "role";

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
        return binder == null ? null : BRIRoleManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isRoleHeldAsUser")
    public static class IsRoleHeldAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            Slog.d(TAG, "Returning false for physical role ownership of a virtual package");
            return false;
        }
    }
}
