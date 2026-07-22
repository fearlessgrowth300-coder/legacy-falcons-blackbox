package top.niunaijun.blackbox.utils;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;


public class AttributionSourceUtils {
    private static final String TAG = "AttributionSourceUtils";

    
    public static void fixAttributionSourceInArgs(Object[] args) {
        fixAttributionSourceInArgs(args, BlackBoxCore.getHostUid(), currentCallerPackage());
    }

    /**
     * Rewrite only the outer caller identity that the destination Binder checks.
     * Physical providers see BlackBox's host UID, while a provider hosted inside
     * another virtual process sees the originating virtual UID through BinderHook.
     */
    public static void fixAttributionSourceInArgs(Object[] args, int uid, String packageName) {
        if (args == null) return;
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                try {
                    fixAttributionSourceUid(arg, uid, packageName);
                    // Android 16 keeps an immutable parcel state in some vendor builds.  A
                    // reflective field write can appear to succeed while the old uid is still
                    // marshalled to the destination provider.  Rebuilding the outer source makes
                    // the Binder-visible identity deterministic.
                    args[i] = rebuildAttributionSource(arg, uid, packageName);
                    Slog.d(TAG, "Fixed AttributionSource UID in method arguments");
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in args: " + e.getMessage());
                }
            }
        }
        
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("Bundle")) {
                try {
                    fixAttributionSourceInBundle(arg, uid, packageName);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in Bundle: " + e.getMessage());
                }
            }
        }
    }

    
    public static void fixAttributionSourceUid(Object attributionSource) {
        fixAttributionSourceUid(attributionSource, BlackBoxCore.getHostUid(), currentCallerPackage());
    }

    public static void fixAttributionSourceUid(Object attributionSource, int uid, String packageName) {
        try {
            if (attributionSource == null) return;

            // Android 12+ keeps the real uid/package in AttributionSourceState.
            // The public AttributionSource object often has no writable mUid or
            // mPackageName fields, so update the nested state through the generated
            // reflection bridge before attempting legacy fields.
            fixNestedAttributionState(attributionSource, uid, packageName);
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            
            
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    uidField.set(attributionSource, uid);
                    Slog.d(TAG, "Fixed AttributionSource UID via field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    
                }
            }
            
            
            try {
                java.lang.reflect.Method setUidMethod = attributionSourceClass.getDeclaredMethod("setUid", int.class);
                setUidMethod.setAccessible(true);
                setUidMethod.invoke(attributionSource, uid);
                Slog.d(TAG, "Fixed AttributionSource UID via setter method");
            } catch (Exception e) {
                
            }
            
            
            String[] packageFieldNames = {"mPackageName", "packageName", "mSourcePackage", "sourcePackage"};
            
            for (String fieldName : packageFieldNames) {
                try {
                    java.lang.reflect.Field packageField = attributionSourceClass.getDeclaredField(fieldName);
                    packageField.setAccessible(true);
                    packageField.set(attributionSource, packageName);
                    Slog.d(TAG, "Fixed AttributionSource package name via field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    
                }
            }
            
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage());
        }
    }

    private static void fixNestedAttributionState(Object attributionSource, int uid, String packageName) {
        try {
            if (attributionSource == null || BRAttributionSource.getRealClass() == null) return;
            Object state = BRAttributionSource.get(attributionSource).mAttributionSourceState();
            if (state != null && BRAttributionSourceState.getRealClass() != null) {
                AttributionSourceStateContext stateContext = BRAttributionSourceState.get(state);
                // The provider process unparcels this state and verifies that its
                // uid matches Binder.getCallingUid().  Android 16 no longer keeps
                // a writable uid on the outer AttributionSource object, so only
                // changing the package (or looking for a setUid method) leaves the
                // virtual guest uid behind and every provider call is rejected.
                stateContext._set_uid(uid);
                stateContext._set_packageName(packageName);
            }
        } catch (Throwable ignored) {
            // Some vendor builds expose a different AttributionSourceState shape;
            // legacy field handling below remains the fallback for those builds.
        }
    }

    private static Object rebuildAttributionSource(Object original, int uid, String packageName) {
        if (original == null || android.os.Build.VERSION.SDK_INT < 31) return original;
        try {
            Class<?> sourceClass = Class.forName("android.content.AttributionSource");
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderClass.getConstructor(int.class).newInstance(uid);
            builderClass.getMethod("setPackageName", String.class).invoke(builder, packageName);

            try {
                Object tag = sourceClass.getMethod("getAttributionTag").invoke(original);
                builderClass.getMethod("setAttributionTag", String.class).invoke(builder, tag);
            } catch (Throwable ignored) {
            }
            try {
                Object token = sourceClass.getMethod("getToken").invoke(original);
                if (token instanceof android.os.IBinder) {
                    builderClass.getMethod("setToken", android.os.IBinder.class).invoke(builder, token);
                }
            } catch (Throwable ignored) {
            }
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable e) {
            Slog.w(TAG, "Could not rebuild AttributionSource; using rewritten state: "
                    + e.getClass().getSimpleName());
            return original;
        }
    }

    
    public static void fixAttributionSourceInBundle(Object bundle) {
        fixAttributionSourceInBundle(bundle, BlackBoxCore.getHostUid(), currentCallerPackage());
    }

    public static void fixAttributionSourceInBundle(Object bundle, int uid, String packageName) {
        try {
            if (bundle == null) return;
            
            
            java.lang.reflect.Method keySetMethod = bundle.getClass().getMethod("keySet");
            java.util.Set<String> keys = (java.util.Set<String>) keySetMethod.invoke(bundle);
            
            for (String key : keys) {
                try {
                    java.lang.reflect.Method getMethod = bundle.getClass().getMethod("get", String.class);
                    Object value = getMethod.invoke(bundle, key);
                    
                    if (value != null && value.getClass().getName().contains("AttributionSource")) {
                        fixAttributionSourceUid(value, uid, packageName);
                        Object replacement = rebuildAttributionSource(value, uid, packageName);
                        if (replacement instanceof android.os.Parcelable) {
                            ((android.os.Bundle) bundle).putParcelable(
                                    key, (android.os.Parcelable) replacement);
                        }
                        Slog.d(TAG, "Fixed AttributionSource UID in Bundle key: " + key);
                    }
                } catch (Exception e) {
                    
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource in Bundle: " + e.getMessage());
        }
    }

    
    public static Object createSafeAttributionSource() {
        try {
            
            Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
            
            
            Object attributionSource = null;
            
            try {
                
                java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                attributionSource = constructor.newInstance(BlackBoxCore.getHostUid(), currentCallerPackage());
            } catch (Exception e) {
                try {
                    
                    java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    attributionSource = constructor.newInstance();
                    
                    
                    fixAttributionSourceUid(attributionSource);
                } catch (Exception e2) {
                    Slog.w(TAG, "Could not create safe AttributionSource: " + e2.getMessage());
                    return null;
                }
            }
            
            return attributionSource;
        } catch (Exception e) {
            Slog.w(TAG, "Error creating safe AttributionSource: " + e.getMessage());
            return null;
        }
    }

    /** Return the real package making a provider call inside the virtual guest. */
    private static String currentCallerPackage() {
        return BlackBoxCore.getHostPkg();
    }

    
    public static boolean validateAttributionSource(Object attributionSource) {
        try {
            if (attributionSource == null) return false;
            
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    Object uidValue = uidField.get(attributionSource);
                    if (uidValue instanceof Integer) {
                        int uid = (Integer) uidValue;
                        if (uid > 0) {
                            Slog.d(TAG, "AttributionSource UID validation passed: " + uid);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    
                }
            }
            
            
            Slog.w(TAG, "AttributionSource validation failed, attempting to fix");
            fixAttributionSourceUid(attributionSource);
            return true;
            
        } catch (Exception e) {
            Slog.w(TAG, "Error validating AttributionSource: " + e.getMessage());
            return false;
        }
    }
}
