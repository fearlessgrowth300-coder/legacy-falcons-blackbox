package top.niunaijun.blackbox.app.dispatcher;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.ServiceRecord;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;
import top.niunaijun.blackbox.utils.Slog;

import static android.app.Service.START_NOT_STICKY;



public class AppServiceDispatcher {
    public static final String TAG = "AppServiceDispatcher";

    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();

    private Map<Intent.FilterComparison, ServiceRecord> mService = new HashMap<>();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    private final Handler mHandler = BlackBoxCore.get().getHandler();

    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null) {
            Log.w(TAG, "Missing target intent or service info during virtual bind");
            return null;
        }

        Log.d(TAG, "Binding virtual service " + serviceInfo.packageName + "/" + serviceInfo.name
                + " for user " + serviceRecord.mUserId);



        Service service = getOrCreateService(serviceRecord);
        if (service == null) {
            Log.w(TAG, "Unable to create virtual service " + serviceInfo.name);
            return null;
        }
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        record.incrementAndGetBindCount(intent);
        if (record.hasBinder(intent)) {
            if (record.isRebind()) {
                service.onRebind(intent);
                record.setRebind(false);
            }
            return record.getBinder(intent);
        }

        try {
            Log.d(TAG, "Calling guest Service.onBind for " + serviceInfo.name);
            IBinder iBinder = service.onBind(intent);
            Log.d(TAG, "Guest Service.onBind returned "
                    + (iBinder == null ? "null" : iBinder.getClass().getName()));
            iBinder = prepareAccountAuthenticatorBinder(intent, serviceInfo, service, iBinder);
            record.addBinder(intent, iBinder);
            return iBinder;
        } catch (Throwable e) {
            Log.e(TAG, "Virtual Service.onBind failed for " + serviceInfo.name, e);
        }
        return null;
    }

    /**
     * Android 16 adds generated permission enforcement to IAccountAuthenticator. A normal
     * AccountManager call comes from system_server, but BlackBox's per-user account manager is an
     * app process, so the generated stub rejects it even though both ends stay inside the same
     * isolated virtual user. Patch only this authenticator binder's PermissionEnforcer context and
     * only for ACCOUNT_MANAGER; every other permission still goes through Android normally.
     *
     * This must happen in the guest service process, before the binder is returned to the BlackBox
     * account manager. Granting the host application the signature permission is neither possible
     * nor desirable, and using Android's real AccountManager would share accounts between clones.
     */
    private IBinder prepareAccountAuthenticatorBinder(Intent intent, ServiceInfo serviceInfo,
                                                       Service service, IBinder binder) {
        if (binder == null || intent == null || service == null) return binder;
        boolean authenticatorAction = android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT
                .equals(intent.getAction());
        boolean authenticatorPermission = serviceInfo != null
                && "android.permission.BIND_ACCOUNT_AUTHENTICATOR"
                .equals(serviceInfo.permission);
        boolean googleAccountAuthenticator = serviceInfo != null
                && "com.google.android.gms.auth.account.authenticator.GoogleAccountAuthenticatorService"
                .equals(serviceInfo.name);
        boolean authenticatorDescriptor = false;
        try {
            authenticatorDescriptor = "android.accounts.IAccountAuthenticator"
                    .equals(binder.getInterfaceDescriptor());
        } catch (Throwable ignored) {
        }
        // Some Android 16/Samsung builds replace the target action with a one-time token while the
        // bind crosses system_server. Current GMS also omits BIND_ACCOUNT_AUTHENTICATOR from its
        // non-exported Google service, so use its exact component or binder interface as the final
        // narrow identifiers.
        if (!authenticatorAction && !authenticatorPermission && !googleAccountAuthenticator
                && !authenticatorDescriptor) {
            return binder;
        }
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Object enforcerOwner = findPermissionEnforcerOwner(binder, seen, 0);
            Field enforcerField = enforcerOwner == null
                    ? null : findField(enforcerOwner.getClass(), "mEnforcer");
            if (enforcerField == null) {
                Slog.w(TAG, "Account-authenticator binder has no reachable PermissionEnforcer: "
                        + binder.getClass().getName());
                return binder;
            }
            enforcerField.setAccessible(true);
            Object enforcer = enforcerField.get(enforcerOwner);
            if (enforcer == null) return binder;

            Field contextField = findField(enforcer.getClass(), "mContext");
            if (contextField == null) {
                Slog.w(TAG, "Authenticator PermissionEnforcer has no context field");
                return binder;
            }
            contextField.setAccessible(true);
            Object currentContext = contextField.get(enforcer);
            if (!(currentContext instanceof AccountManagerPermissionContext)) {
                Context base = currentContext instanceof Context
                        ? (Context) currentContext : service;
                contextField.set(enforcer, new AccountManagerPermissionContext(base));
            }
            Slog.d(TAG, "Enabled isolated account-authenticator bridge for "
                    + (serviceInfo == null ? service.getClass().getName() : serviceInfo.name));
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to prepare account-authenticator binder: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return binder;
    }

    /** GMS 25.x wraps AbstractAccountAuthenticator.Transport in private Binder delegates. Follow
     * only Binder/IInterface/account-authenticator fields and cap both depth and node count. */
    private static Object findPermissionEnforcerOwner(Object candidate, Set<Object> seen, int depth) {
        if (candidate == null || depth > 8 || seen.size() >= 64 || !seen.add(candidate)) {
            return null;
        }
        if (findField(candidate.getClass(), "mEnforcer") != null) {
            return candidate;
        }
        for (Class<?> current = candidate.getClass(); current != null;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object child = field.get(candidate);
                    if (child == null || child == candidate) continue;
                    String childName = child.getClass().getName();
                    if (!(child instanceof IBinder)
                            && !(child instanceof android.os.IInterface)
                            && !childName.startsWith("android.accounts.")) {
                        continue;
                    }
                    Object owner = findPermissionEnforcerOwner(child, seen, depth + 1);
                    if (owner != null) return owner;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static final class AccountManagerPermissionContext extends ContextWrapper {
        AccountManagerPermissionContext(Context base) {
            super(base);
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            if (android.Manifest.permission.ACCOUNT_MANAGER.equals(permission)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            return super.checkPermission(permission, pid, uid);
        }
    }

    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return START_NOT_STICKY;
        }


        Service service = getOrCreateService(stubRecord);
        if (service == null)
            return START_NOT_STICKY;
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());
        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        record.setStartId(stubRecord.mStartId);
        try {
            int i = service.onStartCommand(stubRecord.mServiceIntent, flags, stubRecord.mStartId);
            BlackBoxCore.getBActivityManager().onStartCommand(proxyIntent, stubRecord.mUserId);
            return i;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return START_NOT_STICKY;
    }

    public void onDestroy() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onDestroy();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        mService.clear();

    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onConfigurationChanged(newConfig);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onLowMemory() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onLowMemory();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onTrimMemory(int level) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onTrimMemory(level);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        
    }

    public boolean onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return false;
        }
        Intent intent = stubRecord.mServiceIntent;

        try {
            UnbindRecord unbindRecord = BlackBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, BlackBoxCore.getUserId());
            if (unbindRecord == null)
                return false;

            Service service = getOrCreateService(stubRecord);
            if (service == null)
                return false;

            stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

            ServiceRecord record = findRecord(intent);

            boolean destroy = unbindRecord.getStartId() == 0;
            if (destroy || record.decreaseConnectionCount(intent)) {
                boolean b = service.onUnbind(intent);
                if (destroy) {
                    service.onDestroy();
                    BlackBoxCore.getBActivityManager().onServiceDestroy(proxyIntent, BlackBoxCore.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
                record.setRebind(true);

            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent intent) {
        if (intent == null)
            return;
        ServiceRecord record = findRecord(intent);
        if (record == null)
            return;
        if (record.getService() != null) {
            boolean destroy = record.getStartId() > 0;
            try {
                if (destroy) {
                    mHandler.post(() -> record.getService().onDestroy());
                    BlackBoxCore.getBActivityManager().onServiceDestroy(intent, BlackBoxCore.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private ServiceRecord findRecord(Intent intent) {
        return mService.get(new Intent.FilterComparison(intent));
    }

    private Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            return record.getService();
        }
        Service service = BlackBoxCore.currentActivityThread().createService(serviceInfo, token);
        if (service == null)
            return null;
        record = new ServiceRecord();
        record.setService(service);
        mService.put(new Intent.FilterComparison(intent), record);
        return service;
    }
}
