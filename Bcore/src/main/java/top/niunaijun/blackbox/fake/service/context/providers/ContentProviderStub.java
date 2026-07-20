package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.utils.Slog;
import android.os.Bundle;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;


public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        
        
        String methodName = method.getName();
        
        
        
        if ("call".equals(methodName)) {
            fixProviderAttribution(args);
            // Keep package identity consistent across direct, nested and attribution
            // arguments. Android requires that package to belong to the real host UID.
            fixGuestPackageIdentity(args);
        } else {
            
            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg instanceof String) {
                        String strArg = (String) arg;
                        
                        if (!isSystemProviderAuthority(strArg)) {
                            
                            args[i] = mAppPkg;
                        }
                    }
                }
                
                fixProviderAttribution(args);
            }
        }
        
        
        methodName = method.getName();
        if (methodName.equals("query") || methodName.equals("insert") || 
            methodName.equals("update") || methodName.equals("delete") || 
            methodName.equals("bulkInsert") || methodName.equals("call")) {
            
            
            try {
                return method.invoke(mBase, args);
            } catch (Throwable e) {
                
                Throwable cause = e.getCause();
                if (isUidMismatchError(cause)) {
                    Slog.w(TAG, "UID mismatch in ContentProvider call, returning safe default: " + cause.getMessage());
                    return getSafeDefaultValue(methodName, method.getReturnType());
                } else if (cause instanceof RuntimeException) {
                    String message = cause.getMessage();
                    if (message != null && (message.contains("uid") || message.contains("permission"))) {
                        Slog.w(TAG, "Permission/UID error in ContentProvider call, returning safe default: " + message);
                        return getSafeDefaultValue(methodName, method.getReturnType());
                    }
                }
                
                
                if (methodName.equals("call")) {
                    // The real GMS account provider rejects the host UID used by a
                    // virtual guest before it can return the account list.  Return
                    // the protocol's explicit empty-account result instead of a
                    // null Bundle; Gmail then proceeds to the Google sign-in flow.
                    if (isGoogleAccountsRequest(args)) {
                        Slog.w(TAG, "GMS account lookup rejected for guest; returning empty account list");
                        return createEmptyGoogleAccountsResult();
                    }
                    Slog.w(TAG, "Error in call method, returning safe default: " + e.getMessage());
                    return getSafeDefaultValue(methodName, method.getReturnType());
                }
                
                throw e.getCause();
            }
        }
        
        
        try {
            return method.invoke(mBase, args);
        } catch (Throwable e) {
            
            Throwable cause = e.getCause();
            if (isUidMismatchError(cause)) {
                Slog.w(TAG, "UID mismatch in " + methodName + ", returning safe default: " + cause.getMessage());
                return getSafeDefaultValue(methodName, method.getReturnType());
            }
            throw e.getCause();
        }
    }

    private boolean isGoogleAccountsRequest(Object[] args) {
        if (args == null || args.length < 3) return false;
        return "com.google.android.gms.auth.accounts".equals(args[1])
                && "get_accounts".equals(args[2]);
    }

    private Bundle createEmptyGoogleAccountsResult() {
        Bundle result = new Bundle();
        result.putParcelableArray("accounts", new android.accounts.Account[0]);
        return result;
    }

    private void fixGuestPackageIdentity(Object[] args) {
        if (args == null) return;
        String hostPkg = BlackBoxCore.getHostPkg();
        String guestPkg = effectiveGuestPackage();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (int i = 0; i < args.length; i++) {
            args[i] = rewriteGuestIdentity(args[i], hostPkg, guestPkg, visited);
        }
    }

    /**
     * Google auth/certificate calls carry the caller package in several shapes on
     * Android 14/15/16: a direct String, an AttributionSource, or a nested Bundle.
     * Rewriting only the top-level argument leaves the host package in the nested
     * form and Google rejects the guest as BlackBox.
     */
    private Object rewriteGuestIdentity(Object value, String hostPkg, String guestPkg,
                                        Set<Object> visited) {
        if (value == null || value == this || visited.contains(value)) return value;
        if (value instanceof String) {
            return hostPkg.equals(value) ? guestPkg : value;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isPrimitive() || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum) {
            return value;
        }
        visited.add(value);

        if (valueClass.getName().contains("AttributionSource")) {
            fixAttributionSourceUid(value);
            return value;
        }
        if (value instanceof Bundle) {
            Bundle bundle = (Bundle) value;
            for (String key : bundle.keySet()) {
                Object child = bundle.get(key);
                Object rewritten = rewriteGuestIdentity(child, hostPkg, guestPkg, visited);
                if (rewritten instanceof String && !rewritten.equals(child)) {
                    bundle.putString(key, (String) rewritten);
                } else if (rewritten != child && rewritten != null
                        && rewritten.getClass().isArray()) {
                    bundle.putParcelableArray(key, (android.os.Parcelable[]) rewritten);
                }
            }
            return bundle;
        }
        if (valueClass.isArray() && !valueClass.getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object child = Array.get(value, i);
                Object rewritten = rewriteGuestIdentity(child, hostPkg, guestPkg, visited);
                if (rewritten != child) Array.set(value, i, rewritten);
            }
            return value;
        }
        if (value instanceof List) {
            List list = (List) value;
            for (int i = 0; i < list.size(); i++) {
                Object child = list.get(i);
                Object rewritten = rewriteGuestIdentity(child, hostPkg, guestPkg, visited);
                if (rewritten != child) list.set(i, rewritten);
            }
        }
        return value;
    }

    private String effectiveGuestPackage() {
        return mAppPkg != null ? mAppPkg : BlackBoxCore.getHostPkg();
    }

    private void fixProviderAttribution(Object[] args) {
        int uid = BlackBoxCore.getHostUid();
        String packageName = BlackBoxCore.getHostPkg();
        if (isVirtualProvider()) {
            int virtualCallerUid = BActivityThread.getCallingBUid();
            if (virtualCallerUid > 0) {
                uid = virtualCallerUid;
                try {
                    String[] packages = BPackageManager.get().getPackagesForUid(virtualCallerUid);
                    if (packages != null && packages.length > 0 && packages[0] != null) {
                        packageName = packages[0];
                    }
                } catch (Throwable e) {
                    String currentPackage = BlackBoxCore.getAppPackageName();
                    if (currentPackage != null) packageName = currentPackage;
                }
            }
        }
        AttributionSourceUtils.fixAttributionSourceInArgs(args, uid, packageName);
    }

    private boolean isVirtualProvider() {
        return mAppPkg != null && !BlackBoxCore.getHostPkg().equals(mAppPkg);
    }
    
    private Object getSafeDefaultValue(String methodName) {
        switch (methodName) {
            case "query":
                return null; 
            case "insert":
                return null; 
            case "update":
            case "delete":
                return 0; 
            case "bulkInsert":
                return 0; 
            case "call":
                return new Bundle(); 
            case "getType":
                return null; 
            case "openFile":
                return null; 
            case "openAssetFile":
                return null; 
            default:
                return null; 
        }
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        
        
        return authority.equals("settings") || 
               authority.equals("settings_global") || 
               authority.equals("settings_system") || 
               authority.equals("settings_secure") ||
               authority.equals("media") ||
               authority.equals("telephony") ||
               authority.startsWith("android.provider.Settings");
    }
    
    
    private boolean isUidMismatchError(Throwable error) {
        if (error == null) return false;
        
        String message = error.getMessage();
        if (message == null) return false;
        
        
        return message.contains("Calling uid") && 
               message.contains("doesn't match source uid") ||
               message.contains("uid") && 
               message.contains("permission") ||
               message.contains("SecurityException") ||
               message.contains("UID mismatch");
    }
    
    
    private Object getSafeDefaultValue(String methodName, Class<?> returnType) {
        if (returnType == null) {
            return getSafeDefaultValue(methodName);
        }
        
        
        if (returnType == String.class) {
            return "true"; 
        } else if (returnType == int.class || returnType == Integer.class) {
            return 1; 
        } else if (returnType == long.class || returnType == Long.class) {
            return 1L; 
        } else if (returnType == float.class || returnType == Float.class) {
            return 1.0f; 
        } else if (returnType == boolean.class || returnType == Boolean.class) {
            return true; 
        } else if (returnType == Bundle.class) {
            return new Bundle(); 
        }
        
        
        return getSafeDefaultValue(methodName);
    }

    
    private void fixAttributionSourceUid(Object attributionSource) {
        try {
            if (attributionSource == null) return;
            int uid = isVirtualProvider()
                    ? BActivityThread.getCallingBUid()
                    : BlackBoxCore.getHostUid();
            String packageName = BlackBoxCore.getHostPkg();
            if (isVirtualProvider()) {
                try {
                    String[] packages = BPackageManager.get().getPackagesForUid(uid);
                    if (packages != null && packages.length > 0 && packages[0] != null) {
                        packageName = packages[0];
                    }
                } catch (Throwable ignored) {
                }
            }
            AttributionSourceUtils.fixAttributionSourceUid(attributionSource, uid, packageName);
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage());
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
