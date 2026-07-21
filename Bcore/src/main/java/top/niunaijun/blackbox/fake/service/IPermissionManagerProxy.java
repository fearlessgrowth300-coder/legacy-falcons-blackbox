package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";
    private static final int FLAG_PERMISSION_USER_SET = 1 << 0;

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        try {
            BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        } catch (Throwable ignored) {
            // ActivityThread.sPermissionManager is no longer the only cache on Android 15/16.
        }
        replaceFrameworkPermissionManagerCache(proxyInvocation);
        disableFrameworkPermissionCaches();
        
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    /** Android 15/16 keeps the binder inside the PermissionManager system-service instance. */
    private static void replaceFrameworkPermissionManagerCache(Object proxyInvocation) {
        try {
            Object manager = BlackBoxCore.getContext().getSystemService("permission");
            if (manager == null) return;
            for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!name.contains("permissionmanager") && !name.equals("mservice")) continue;
                    field.setAccessible(true);
                    Object current = field.get(manager);
                    if (current != null && current.getClass().getInterfaces().length > 0) {
                        field.set(manager, proxyInvocation);
                        top.niunaijun.blackbox.utils.Slog.d(TAG,
                                "updated framework PermissionManager cache " + field.getName());
                    }
                }
            }
        } catch (Throwable error) {
            top.niunaijun.blackbox.utils.Slog.w(TAG,
                    "unable to update framework PermissionManager cache", error);
        }
    }

    /**
     * ContextImpl.checkSelfPermission() goes through PermissionManager's process-local
     * PropertyInvalidatedCache on Android 12+.  A denial cached while the guest process is
     * starting otherwise survives after the permission binder has been replaced, so the guest
     * never reaches our virtual permission policy.  Disable only the two permission result
     * caches; package metadata and every unrelated framework cache remain untouched.
     */
    private static void disableFrameworkPermissionCaches() {
        try {
            Class<?> permissionManager = Class.forName("android.permission.PermissionManager");
            invokeStaticNoArg(permissionManager, "disablePermissionCache");
            invokeStaticNoArg(permissionManager, "disablePackageNamePermissionCache");
            top.niunaijun.blackbox.utils.Slog.d(TAG,
                    "disabled framework permission result caches");
        } catch (Throwable error) {
            top.niunaijun.blackbox.utils.Slog.w(TAG,
                    "unable to disable framework permission result caches", error);
        }
    }

    private static void invokeStaticNoArg(Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String permission = findPermission(args);
        if (isGuestMediaPermission(permission)) {
            String name = method.getName();
            if (name.contains("PermissionFlags")) {
                return FLAG_PERMISSION_USER_SET;
            }
            if (name.startsWith("check") && method.getReturnType() == int.class) {
                return PackageManager.PERMISSION_GRANTED;
            }
            if (name.contains("Rationale") || name.contains("Revoked")) {
                return false;
            }
        }
        return super.invoke(proxy, method, args);
    }

    private static String findPermission(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).startsWith("android.permission.")) {
                return (String) arg;
            }
            if (arg instanceof String[]) {
                for (String value : (String[]) arg) {
                    if (value != null && value.startsWith("android.permission.")) return value;
                }
            }
            if (arg instanceof List) {
                for (Object value : (List<?>) arg) {
                    if (value instanceof String
                            && ((String) value).startsWith("android.permission.")) {
                        return (String) value;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isGuestMediaPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.CAMERA)
                || permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals(android.Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                || permission.startsWith("android.permission.READ_MEDIA_");
    }

    @Override
    public boolean isBadEnv() {
        try {
            return BRActivityThread.get().sPermissionManager() != getProxyInvocation();
        } catch (Throwable error) {
            return true;
        }
    }

}
