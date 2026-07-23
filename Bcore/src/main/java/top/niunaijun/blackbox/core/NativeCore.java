package top.niunaijun.blackbox.core;


import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import top.niunaijun.blackbox.core.system.JarManager;
import top.niunaijun.blackbox.utils.Slog;


public class NativeCore {
    public static final String TAG = "NativeCore";

    /**
     * Binder.getCallingUid() is hooked natively. Never walk the Java stack from that callback:
     * Android 16 ART can be in the middle of resolving an oat method and recursively walking the
     * mixed native/managed stack crashes the whole guest process. The account-authenticator
     * dispatcher marks only the exact binder transaction that needs host-UID normalization.
     */
    private static final ThreadLocal<Integer> ACCOUNT_AUTH_CALL_DEPTH =
            new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return 0;
                }
            };

    public static void enterAccountAuthenticatorCall() {
        ACCOUNT_AUTH_CALL_DEPTH.set(ACCOUNT_AUTH_CALL_DEPTH.get() + 1);
    }

    public static void exitAccountAuthenticatorCall() {
        int depth = ACCOUNT_AUTH_CALL_DEPTH.get() - 1;
        if (depth <= 0) {
            ACCOUNT_AUTH_CALL_DEPTH.remove();
        } else {
            ACCOUNT_AUTH_CALL_DEPTH.set(depth);
        }
    }

    private static boolean isAccountAuthenticatorTransportCall() {
        return ACCOUNT_AUTH_CALL_DEPTH.get() > 0;
    }

    static {
        // Per-variant engine lib name (lib<name>.so) so the 4 variants don't all load an
        // identically-named "libblackbox.so". VariantConfig.tag is set in App.attachBaseContext,
        // which runs before this class is first referenced. Fall back to "blackbox".
        String lib = "blackbox";
        try {
            lib = VariantConfig.libName();
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary(lib);
        } catch (Throwable t) {
            // If a renamed lib isn't present for some reason, fall back to the default name.
            if (!"blackbox".equals(lib)) System.loadLibrary("blackbox");
            else throw t;
        }
    }

    public static native void init(int apiLevel);

    /**
     * Configure the per-clone native property spoof for THIS guest process.
     * keys[i] -> values[i] are ro.* properties returned by __system_property_get
     * (graphics/SoC keys are always passed through to the real value; see VirtualSpoof).
     */
    public static native void spoofDevice(String[] keys, String[] values);

    /**
     * Merge dynamic properties (for example SIM/operator country) into the current clone profile.
     * Unlike {@link #spoofDevice(String[], String[])}, this never clears the already-installed
     * Build/device properties for the clone.
     */
    public static native void updateDeviceProperties(String[] keys, String[] values);

    /**
     * Route all outbound TCP from THIS guest process through a proxy
     * (type 0=http CONNECT, 1=socks5). Pass host=null to disable.
     */
    /**
     * Enables the transparent proxy for this guest process.
     *
     * @return true only when the proxy endpoint resolved and every required native hook was
     * installed. A false result must be treated as fail-closed by the caller.
     */
    public static native boolean setProxy(int type, String host, int port, String user, String pass);

    public static native void disableProxy();

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();
    
    public static native boolean disableResourceLoading();


    @Keep
    public static int getCallingUid(int origCallingUid) {
        try {
            
            if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
                return origCallingUid;
            
            if (origCallingUid > Process.LAST_APPLICATION_UID)
                return origCallingUid;

            if (origCallingUid == BlackBoxCore.getHostUid()) {
                
                String appPackageName = BlackBoxCore.getAppPackageName();
                if (appPackageName != null && appPackageName.equals("com.google.android.gms")){
                    // Android's real AccountManager invokes an authenticator as a trusted system
                    // caller.  BlackBox's per-user AccountManager must stay outside system_server
                    // so accounts remain isolated, but that makes the shared host UID look like an
                    // untrusted third-party caller to modern GMS.  Normalize only calls currently
                    // executing through Android's account-authenticator Transport.  Do not treat
                    // arbitrary guest-to-GMS Binder calls as GMS itself.
                    if (isAccountAuthenticatorTransportCall()) {
                        return Process.myUid();
                    }
                }
                
                
                
                if (appPackageName != null && appPackageName.equals("com.google.android.webview")){
                    return Process.myUid();
                }
                
                
                try {
                    int callingBUid = BlackBoxCore.getCallingBUid();
                    if (callingBUid > 0 && callingBUid < Process.LAST_APPLICATION_UID) {
                        return callingBUid;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting calling BUid: " + e.getMessage());
                }
                // Do not infer privileged callers from class or method names. Apart from being
                // unsafe inside this native callback on Android 16, that let arbitrary guest code
                // with matching stack-frame names receive the system UID.
                return BlackBoxCore.getHostUid();
            }
            return origCallingUid;
        } catch (Exception e) {
            Log.e(TAG, "Error in getCallingUid: " + e.getMessage());
            
            return Process.myUid();
        }
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            File emptyJar = JarManager.getInstance().getEmptyJar();
            if (emptyJar == null) {
                Log.w(TAG, "Empty JAR not available, attempting sync initialization");
                JarManager.getInstance().initializeSync();
                emptyJar = JarManager.getInstance().getEmptyJar();
            }
            
            if (emptyJar == null || !emptyJar.exists()) {
                Log.e(TAG, "Empty JAR file not found or invalid");
                return new long[]{};
            }
            
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            Log.d(TAG, "Successfully loaded empty DEX with " + cookies.size() + " cookies");
            return longs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load empty DEX", e);
        }
        return new long[]{};
    }
    
    
    private static long[] createFallbackEmptyDex() {
        try {
            Slog.d(TAG, "Creating fallback empty DEX");
            
            
            
            byte[] emptyDexBytes = createMinimalDexBytes();
            
            
            File tempDexFile = File.createTempFile("fallback_empty", ".dex");
            tempDexFile.deleteOnExit();
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDexFile);
            fos.write(emptyDexBytes);
            fos.close();
            
            
            DexFile dexFile = new DexFile(tempDexFile);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            
            if (cookies != null && !cookies.isEmpty()) {
                long[] longs = new long[cookies.size()];
                for (int i = 0; i < cookies.size(); i++) {
                    longs[i] = cookies.get(i);
                }
                
                Slog.d(TAG, "Successfully created fallback empty DEX with " + cookies.size() + " cookies");
                return longs;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error creating fallback empty DEX: " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Returning empty DEX array as last resort");
        return new long[]{};
    }
    
    
    private static byte[] createMinimalDexBytes() {
        
        
        
        
        
        byte[] dexHeader = {
            'd', 'e', 'x', '\n',  
            0x30, 0x33, 0x35, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x70, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00   
        };
        
        return dexHeader;
    }
}
