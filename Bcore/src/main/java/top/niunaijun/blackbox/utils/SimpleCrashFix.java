package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.Application;

import top.niunaijun.blackbox.BlackBoxCore;


public class SimpleCrashFix {
    private static final String TAG = "SimpleCrashFix";
    private static boolean sIsInstalled = false;


    public static void installSimpleFix() {
        if (sIsInstalled) {
            Slog.d(TAG, "Simple crash fix already installed");
            return;
        }

        try {
            Slog.d(TAG, "Installing essential crash fix...");


            // Never swallow uncaught failures. Continuing with partially-created framework state
            // produces a blank guest window and eventually an ANR instead of a recoverable crash.

            // Main-thread crash SURVIVAL. An UncaughtExceptionHandler is too late — once the main
            // Looper's dispatch throws, the loop has already unwound and the app dies silently
            // ("crash back to launcher"). This guard runs Looper.loop() inside a try/while so a
            // throwing message is dropped and the loop CONTINUES — the app keeps running. Covers
            // the intermittent Instagram androidx-startup NPE (AndroidXAppInitializer) + the other
            // known swallowable crashes.
            //
            // The guard is deliberately NARROW, which is what makes a nested main Looper safe here:
            // isSwallowable() resumes the loop only for a known-recoverable guest-init failure, and
            // it explicitly refuses every "Unable to start/resume/pause/stop/destroy activity". A
            // damaged activity launch therefore still dies normally and is still reported through
            // ApplicationExitInfo — only the recoverable startup race is absorbed. Without this,
            // Instagram closes back to the launcher a second or two after opening while apps that
            // never touch androidx App Startup (TikTok, Fiverr) stay up.
            installMainLoopGuard();


            installContextWrapperHook();

            sIsInstalled = true;
            Slog.d(TAG, "Essential crash fix installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install essential crash fix: " + e.getMessage(), e);
        }
    }

    /** Keep the main thread alive across recoverable message-dispatch crashes. */
    private static void installMainLoopGuard() {
        try {
            // postAtFrontOfQueue so the guard's nested loop takes over BEFORE any already-queued
            // app message (e.g. Instagram's androidx A02 init) can run unguarded.
            new Handler(Looper.getMainLooper()).postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    Slog.d(TAG, "main-loop guard ACTIVE (nested loop)");
                    while (true) {
                        try {
                            Looper.loop();
                            return; // loop only returns on quit() — let the thread end normally
                        } catch (Throwable e) {
                            boolean sw = isSwallowable(e);
                            // Always retain the complete local stack trace. Logging only the outer
                            // RuntimeException hid the real guest startup failure and left a
                            // windowless proxy activity behind, which later surfaced as an ANR.
                            Slog.w(TAG, "main-loop guard caught (swallow=" + sw + ")", e);
                            if (sw) {
                                // drop the bad message, resume the loop → app survives
                            } else {
                                throw e; // genuinely fatal: let it die properly
                            }
                        }
                    }
                }
            });
            Slog.d(TAG, "Main-loop guard installed");
        } catch (Throwable e) {
            Slog.e(TAG, "Failed to install main-loop guard: " + e.getMessage());
        }
    }

    /** True if this crash is one we can safely swallow to keep the guest running.
     *  Walks the whole cause chain (the guest wraps the real cause, and StackTraceFilter can strip
     *  app frames off the outer throwable) and also matches by message text so obfuscated
     *  app-init crashes (e.g. Instagram's androidx-startup "INSTANCE_FIELD must not be null") are
     *  recognized even when the stack trace no longer carries the com.instagram frames. */
    private static boolean isSwallowable(Throwable t) {
        // Swallowing a failed Activity launch leaves Android's proxy Activity resumed without a
        // window.  The user sees the previous screen, input stops working, and the host later ANRs.
        // A launch failure must surface normally unless its concrete inner cause is fixed.
        for (Throwable c = t; c != null; c = c.getCause()) {
            String message = c.getMessage();
            if (message != null && (message.startsWith("Unable to start activity")
                    || message.startsWith("Unable to resume activity")
                    || message.startsWith("Unable to pause activity")
                    || message.startsWith("Unable to stop activity")
                    || message.startsWith("Unable to destroy activity"))) {
                return false;
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (isNullContextCrash(c) || isGooglePlayServicesCrash(c) || isWebViewCrash(c)
                    || isAttributionSourceCrash(c) || isSocialMediaAppCrash(c)) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("INSTANCE_FIELD")
                    || m.contains("androidx.startup")
                    || m.contains("InitializationProvider")
                    || m.contains("AppInitializer"))) {
                return true;
            }
        }
        return false;
    }
    
    
    private static void installGlobalExceptionHandler() {
        try {
            
            Thread.UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
            
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    
                    if (isNullContextCrash(throwable)) {
                        Slog.w(TAG, "Caught null context crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/NullContext): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isGooglePlayServicesCrash(throwable)) {
                        Slog.w(TAG, "Caught Google Play Services crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/GMS): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isWebViewCrash(throwable)) {
                        Slog.w(TAG, "Caught WebView crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/WebView): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isAttributionSourceCrash(throwable)) {
                        Slog.w(TAG, "Caught AttributionSource crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/Attribution): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isSocialMediaAppCrash(throwable)) {
                        Slog.w(TAG, "Caught social media app crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/SocialMedia): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    Slog.e(TAG, "Fatal crash detected, attempting to report before death...");
                    try {
                         BlackBoxCore.get().sendLogs("FATAL CRASH (Uncaught): " + throwable.getMessage(), false);
                    } catch (Throwable e) {
                         Slog.e(TAG, "Failed to report fatal crash: " + e.getMessage());
                    }

                    
                    if (currentHandler != null) {
                        currentHandler.uncaughtException(thread, throwable);
                    }
                }
            });
            
            Slog.d(TAG, "Global exception handler installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install global exception handler: " + e.getMessage(), e);
        }
    }
    
    
    private static void installContextWrapperHook() {
        try {
            
            ContextWrapperHook.installHook();
            Slog.d(TAG, "Context wrapper hook installed");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install context wrapper hook: " + e.getMessage(), e);
        }
    }
    
    
    private static boolean isNullContextCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Context") || 
                   message.contains("context") ||
                   message.contains("getResources") ||
                   message.contains("getPackageManager") ||
                   message.contains("getClassLoader");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                
                if (className.contains("Context") || 
                    className.contains("ContextWrapper") ||
                    methodName.contains("getResources") ||
                    methodName.contains("getPackageManager") ||
                    methodName.contains("getClassLoader")) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    
    private static boolean isGooglePlayServicesCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Google Play Services") ||
                   message.contains("GooglePlayServicesUtil") ||
                   message.contains("GoogleApiAvailability") ||
                   message.contains("com.google.android.gms");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("com.google.android.gms") ||
                    className.contains("GooglePlayServicesUtil") ||
                    className.contains("GoogleApiAvailability")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isWebViewCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("WebView") ||
                   message.contains("webview") ||
                   message.contains("WebViewDatabase") ||
                   message.contains("WebSettings") ||
                   message.contains("data directory");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if (className.contains("WebView") ||
                    className.contains("WebViewDatabase") ||
                    className.contains("WebSettings") ||
                    methodName.contains("webView") ||
                    methodName.contains("WebView")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isAttributionSourceCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("AttributionSource") ||
                   message.contains("attribution") ||
                   message.contains("Calling uid") ||
                   message.contains("source uid") ||
                   message.contains("UID mismatch");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if (className.contains("AttributionSource") ||
                    className.contains("ContentProvider") ||
                    methodName.contains("enforceCallingUid") ||
                    methodName.contains("enforceCallingUidAndPid")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isSocialMediaAppCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Facebook") ||
                   message.contains("Instagram") ||
                   message.contains("WhatsApp") ||
                   message.contains("Telegram") ||
                   message.contains("Twitter") ||
                   message.contains("TikTok") ||
                   message.contains("Snapchat") ||
                   message.contains("YouTube") ||
                   message.contains("LinkedIn");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("com.facebook") ||
                    className.contains("com.instagram") ||
                    className.contains("com.whatsapp") ||
                    className.contains("org.telegram") ||
                    className.contains("com.twitter") ||
                    className.contains("com.zhiliaoapp.musically") ||
                    className.contains("com.snapchat") ||
                    className.contains("com.google.android.youtube") ||
                    className.contains("com.linkedin")) {
                    return true;
                }
            }
        }
        
        return false;
    }
}
