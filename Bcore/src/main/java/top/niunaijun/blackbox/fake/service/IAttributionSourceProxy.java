package top.niunaijun.blackbox.fake.service;



import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;


public class IAttributionSourceProxy extends ClassInvocationStub {
    public static final String TAG = "IAttributionSourceProxy";

    public IAttributionSourceProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        
        return null;
    }

    @Override
    protected void inject(Object base, Object proxy) {
        
        Slog.d(TAG, "AttributionSource proxy initialized for UID mismatch prevention");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    @ProxyMethod("AttributionSource")
    public static class AttributionSourceConstructor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                int uid = BlackBoxCore.getHostUid();
                String packageName = callerPackage();
                
                Slog.d(TAG, "Creating AttributionSource with UID: " + uid + ", package: " + packageName);
                
                
                Object attributionSource = createSafeAttributionSource(uid, packageName);
                if (attributionSource != null) {
                    return attributionSource;
                }
                
                
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "Error creating AttributionSource, using fallback: " + e.getMessage());
                return createSafeAttributionSource(BlackBoxCore.getHostUid(), callerPackage());
            }
        }

        private String callerPackage() {
            return BlackBoxCore.getHostPkg();
        }
        
        private Object createSafeAttributionSource(int uid, String packageName) {
            try {
                
                Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
                
                
                Constructor<?> constructor = null;
                try {
                    
                    constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class, String.class);
                } catch (NoSuchMethodException e) {
                    try {
                        
                        constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                    } catch (NoSuchMethodException e2) {
                        
                        constructor = attributionSourceClass.getDeclaredConstructor();
                    }
                }
                
                if (constructor != null) {
                    constructor.setAccessible(true);
                    
                    if (constructor.getParameterCount() == 3) {
                        return constructor.newInstance(uid, packageName, null);
                    } else if (constructor.getParameterCount() == 2) {
                        return constructor.newInstance(uid, packageName);
                    } else {
                        return constructor.newInstance();
                    }
                }
                
                return null;
            } catch (Exception e) {
                Slog.e(TAG, "Failed to create safe AttributionSource: " + e.getMessage());
                return null;
            }
        }
    }

    
    @ProxyMethod("enforceCallingUid")
    public static class EnforceCallingUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                Slog.d(TAG, "Intercepting enforceCallingUid to prevent UID mismatch crashes");
                return null; 
            } catch (Exception e) {
                Slog.w(TAG, "Error in enforceCallingUid hook: " + e.getMessage());
                return null;
            }
        }
    }

    
    @ProxyMethod("enforceCallingUidAndPid")
    public static class EnforceCallingUidAndPid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                Slog.d(TAG, "Intercepting enforceCallingUidAndPid to prevent UID mismatch crashes");
                return null; 
            } catch (Exception e) {
                Slog.w(TAG, "Error in enforceCallingUidAndPid hook: " + e.getMessage());
                return null;
            }
        }
    }

    
    @ProxyMethod("fromParcel")
    public static class FromParcel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    AttributionSourceUtils.fixAttributionSourceUid(result);
                    return result;
                }
                
                
                return createSafeAttributionSource(BlackBoxCore.getHostUid(), callerPackage());
            } catch (Exception e) {
                Slog.w(TAG, "Error in fromParcel, using fallback: " + e.getMessage());
                return createSafeAttributionSource(BlackBoxCore.getHostUid(), callerPackage());
            }
        }
        
        private Object createSafeAttributionSource(int uid, String packageName) {
            try {
                Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
                Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class, String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(uid, packageName, null);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to create safe AttributionSource fallback: " + e.getMessage());
                return null;
            }
        }

        private String callerPackage() {
            return BlackBoxCore.getHostPkg();
        }
    }
}
