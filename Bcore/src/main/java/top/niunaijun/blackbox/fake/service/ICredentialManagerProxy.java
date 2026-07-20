package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps Android's host Credential Manager outside virtual app processes.
 *
 * Credential Manager validates the binder caller UID against the package supplied by the app.
 * A virtual package cannot pass that system-server check. Replacing the package with BlackBox's
 * host package would make the request work, but it would expose the phone owner's shared password
 * and passkey vault to every clone. Instead, guest reads fail closed as "no credential" so the
 * app can fall back to its own sign-in UI without seeing host or sibling-profile credentials.
 */
public final class ICredentialManagerProxy extends BinderInvocationStub {
    private static final String TAG = "CredentialManagerStub";
    private static final String SERVICE_NAME = "credential";
    private static final String NO_CREDENTIAL =
            "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL";

    public ICredentialManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
        if (binder == null) {
            return null;
        }
        try {
            Class<?> stub = Class.forName("android.credentials.ICredentialManager$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable error) {
            Slog.e(TAG, "Unable to isolate the credential service", error);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("executeGetCredential")
    public static final class ExecuteGetCredential extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            notifyNoCredential(args, "IGetCredentialCallback");
            return null;
        }
    }

    @ProxyMethod("executePrepareGetCredential")
    public static final class ExecutePrepareGetCredential extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            notifyNoCredential(args, "IPrepareGetCredentialCallback");
            return null;
        }
    }

    @ProxyMethod("getCandidateCredentials")
    public static final class GetCandidateCredentials extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            notifyNoCredential(args, "IGetCandidateCredentialsCallback");
            return null;
        }
    }

    private static void notifyNoCredential(Object[] args, String callbackInterface) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null || !implementsInterface(arg.getClass(), callbackInterface)) {
                continue;
            }
            try {
                Method onError = arg.getClass().getMethod(
                        "onError", String.class, String.class);
                onError.invoke(arg, NO_CREDENTIAL, "No credential is stored for this isolated profile");
                return;
            } catch (Throwable error) {
                Slog.e(TAG, "Unable to deliver isolated credential result", error);
                return;
            }
        }
        Slog.w(TAG, "Credential callback was not available");
    }

    private static boolean implementsInterface(Class<?> type, String simpleName) {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            for (Class<?> iface : candidate.getInterfaces()) {
                if (simpleName.equals(iface.getSimpleName())
                        || implementsInterface(iface, simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
