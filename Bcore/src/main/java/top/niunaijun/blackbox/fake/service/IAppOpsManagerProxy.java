package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    private static volatile Object sProxyService;

    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        sProxyService = proxyInvocation;
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    /**
     * ContextImpl caches framework managers per application context. A guest context created after
     * the global hook can therefore retain the physical IAppOpsService and fail checkPackage()
     * because the guest package name does not belong to BlackBox's real UID. Rebind the already
     * installed proxy before the guest Application is attached.
     */
    public static void bindToGuestContext(Context context) {
        Object proxyService = sProxyService;
        if (context == null || proxyService == null
                || BRAppOpsManager.get(null)._check_mService() == null) {
            return;
        }
        try {
            AppOpsManager appOpsManager =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager != null) {
                BRAppOpsManager.get(appOpsManager)._set_mService(proxyService);
            }
        } catch (Throwable t) {
            Slog.e(TAG, "Unable to bind AppOps proxy to guest context", t);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        // Bypass the system's appop check and always allow — BUT return a value of the
        // method's ACTUAL return type. On Android 12+ (API 31+) noteOperation/startOperation/
        // noteProxyOperation return an android.app.SyncNotedAppOp object, NOT an int. Returning
        // a bare int (MODE_ALLOWED) makes the framework's AppOpsManager cast it to SyncNotedAppOp
        // → ClassCastException → the guest crashes (e.g. WhatsApp's Environment.isExternalStorage
        // Legacy() on the backup "Skip" path). So key off the declared return type.
        if (methodName.startsWith("check") ||
            methodName.startsWith("note") ||
            methodName.startsWith("start")) {
            return allowedResult(proxy, method, args);
        }


        if (methodName.startsWith("finish")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName);
            return null;
        }


        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {

            Slog.w(TAG, "AppOps invoke: SecurityException caught for " + methodName + ", allowing operation", e);
            return AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Slog.e(TAG, "AppOps invoke: Error in method " + methodName, e);

            return AppOpsManager.MODE_ALLOWED;
        }
    }

    /** An "operation allowed" result matching [method]'s declared return type. */
    private Object allowedResult(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?> ret = method.getReturnType();
        if ("android.app.SyncNotedAppOp".equals(ret.getName())) {
            Object v = buildSyncNotedAppOp(args);
            if (v != null) return v;
            // Couldn't synthesize it (unexpected constructor shape) — let the real
            // service answer with a genuine SyncNotedAppOp rather than crash.
            try {
                MethodParameterUtils.replaceFirstAppPkg(args);
                MethodParameterUtils.replaceLastUid(args);
                return super.invoke(proxy, method, args);
            } catch (Throwable t) {
                return null;
            }
        }
        if (ret == void.class || ret == Void.class) return null;
        // int / Integer (and any numeric mode) → allowed
        return AppOpsManager.MODE_ALLOWED;
    }

    /** Build an android.app.SyncNotedAppOp representing MODE_ALLOWED for the op in [args]. */
    private static Object buildSyncNotedAppOp(Object[] args) {
        try {
            Class<?> c = Class.forName("android.app.SyncNotedAppOp");
            int code = (args != null && args.length > 0 && args[0] instanceof Integer) ? (Integer) args[0] : 0;
            String attributionTag = null;
            String opName;
            try {
                Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
                Object n = m.invoke(null, code);
                opName = n != null ? n.toString() : "android:unknown";
            } catch (Throwable t) {
                opName = "android:unknown";
            }
            // Canonical Android 12–14 ctor: (int opMode, int opCode, String attributionTag, String opName)
            try {
                java.lang.reflect.Constructor<?> ct = c.getConstructor(int.class, int.class, String.class, String.class);
                ct.setAccessible(true);
                return ct.newInstance(AppOpsManager.MODE_ALLOWED, code, attributionTag, opName);
            } catch (NoSuchMethodException ignored) {
            }
            // Fallback ctor seen on some builds: (int opCode, String attributionTag)
            try {
                java.lang.reflect.Constructor<?> ct = c.getConstructor(int.class, String.class);
                ct.setAccessible(true);
                return ct.newInstance(code, attributionTag);
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            Slog.e(TAG, "buildSyncNotedAppOp failed", t);
        }
        return null;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            Slog.d(TAG, "AppOps CheckOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOperationForDevice: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOp: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && isMediaStorageOrAudioOp(name)) {
                    Slog.d(TAG, "AppOps FinishOp: Finishing operation: " + name);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    
    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOp: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOpNoThrow: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    private static boolean isMediaStorageOrAudioOp(String opPublicNameOrStr) {
        if (opPublicNameOrStr == null) return false;
        
        String n = opPublicNameOrStr.toUpperCase();
        return n.contains("READ_MEDIA")
                || n.contains("READ_EXTERNAL_STORAGE")
                || n.contains("RECORD_AUDIO")
                || n.contains("CAPTURE_AUDIO_OUTPUT")
                || n.contains("MODIFY_AUDIO_SETTINGS")
                || n.contains("AUDIO")
                || n.contains("MICROPHONE")
                || n.contains("FOREGROUND_SERVICE")
                || n.contains("SYSTEM_ALERT_WINDOW")
                || n.contains("WRITE_SETTINGS")
                || n.contains("ACCESS_FINE_LOCATION")
                || n.contains("ACCESS_COARSE_LOCATION")
                || n.contains("CAMERA")
                || n.contains("BODY_SENSORS")
                || n.contains("BLUETOOTH_SCAN")
                || n.contains("BLUETOOTH_CONNECT")
                || n.contains("BLUETOOTH_ADVERTISE")
                || n.contains("NEARBY_WIFI_DEVICES")
                || n.contains("POST_NOTIFICATIONS");
    }

    private static String getOpPublicName(int op) {
        try {
            
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
