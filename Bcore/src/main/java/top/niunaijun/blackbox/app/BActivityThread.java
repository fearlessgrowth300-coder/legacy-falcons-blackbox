package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.app.LoadedApk;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.core.CrashHandler;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.VirtualRuntime;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.am.ReceiverData;

import top.niunaijun.blackbox.fake.delegate.AppInstrumentation;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;

import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.service.IAppOpsManagerProxy;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.SafeContextWrapper;
import top.niunaijun.blackbox.utils.GlobalContextWrapper;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ApplicationCreationCompat;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.utils.compat.StrictModeCompat;
import top.niunaijun.blackbox.core.system.JarManager;


public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = BlackBoxCore.get().getHandler();
    private static final Object mConfigLock = new Object();
    private volatile boolean mRuntimeIsolationReady;
    private volatile Throwable mRuntimeIsolationError;
    private volatile java.util.concurrent.CountDownLatch mRuntimeIsolationLatch =
            new java.util.concurrent.CountDownLatch(0);

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? BlackBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null) {
                if (!this.mAppConfig.packageName.equals(appConfig.packageName)) {
                    throw new RuntimeException("reject init process: " + appConfig.processName
                            + ", this process is : " + this.mAppConfig.processName);
                }
                return;
            }
            this.mAppConfig = appConfig;
            // Apply this clone's unique, persistent device identity as early as
            // possible (before the guest app loads / references Build.*).
            final top.niunaijun.blackbox.core.DeviceProfile profile;
            try {
                profile = top.niunaijun.blackbox.core.DeviceProfile.forUser(appConfig.userId);
                profile.prepareEarly();
                if (!top.niunaijun.blackbox.core.IOCore.get()
                        .enableKernelIdentityRedirects()) {
                    throw new SecurityException("Per-clone kernel identity redirect unavailable");
                }
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.Slog.w("DeviceProfile", "apply failed: " + t.getMessage());
                throw new RuntimeException("Refusing to start guest without complete identity isolation", t);
            }
            // Route this guest's traffic through the proxy assigned to its User.
            try {
                top.niunaijun.blackbox.core.GuestProxy.ApplyStatus proxyStatus =
                        top.niunaijun.blackbox.core.GuestProxy.apply(appConfig.userId, appConfig.packageName);
                if (proxyStatus != top.niunaijun.blackbox.core.GuestProxy.ApplyStatus.READY
                        && proxyStatus != top.niunaijun.blackbox.core.GuestProxy.ApplyStatus.NOT_CONFIGURED) {
                    throw new SecurityException("Assigned proxy is not ready: " + proxyStatus);
                }
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.Slog.w("GuestProxy", "apply failed: " + t.getMessage());
                throw new RuntimeException("Refusing to start guest with a broken proxy assignment", t);
            }

            // Pine's first native initialization can exceed Android 16's ContentProvider startup
            // deadline. Return the process binder promptly, but keep guest Application creation
            // blocked on this latch so no app code can run before identity and keystore isolation.
            mRuntimeIsolationReady = false;
            mRuntimeIsolationError = null;
            final java.util.concurrent.CountDownLatch isolationLatch =
                    new java.util.concurrent.CountDownLatch(1);
            mRuntimeIsolationLatch = isolationLatch;
            Thread isolationWorker = new Thread(() -> {
                try {
                    boolean identityReady = profile.installRuntimeHooks();
                    // Install after GuestProxy has read BlackBox's host key, but before any guest
                    // Application class can touch its own login-encryption aliases.
                    boolean keystoreReady =
                            top.niunaijun.blackbox.core.KeystoreIsolation.installForCurrentProcess();
                    if (!identityReady || !keystoreReady) {
                        throw new SecurityException("Incomplete runtime identity isolation");
                    }
                    mRuntimeIsolationReady = true;
                    top.niunaijun.blackbox.utils.Slog.d(
                            TAG, "Runtime isolation ready for " + appConfig.packageName);
                } catch (Throwable error) {
                    mRuntimeIsolationError = error;
                    top.niunaijun.blackbox.utils.Slog.e(
                            TAG, "Runtime isolation failed for " + appConfig.packageName, error);
                } finally {
                    isolationLatch.countDown();
                }
            }, "GuestRuntimeIsolation");
            isolationWorker.setDaemon(true);
            isolationWorker.start();
            IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mConfigLock) {
                            try {
                                iBinder.linkToDeath(this, 0);
                            } catch (RemoteException ignored) {
                            }
                            mAppConfig = null;
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        if (!BActivityThread.currentActivityThread().isInit() || mBoundApplication == null
                || mBoundApplication.info == null) {
            Slog.w(TAG, "Skipping stale service because its virtual application is unavailable: "
                    + serviceInfo.packageName + "/" + serviceInfo.name);
            return null;
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        Service service;
        try {
            service = (Service) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (ClassNotFoundException e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services class not found, skipping: " + serviceInfo.name);
                return null;
            }
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services service creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "Service creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage());
            return null;
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        if (!BActivityThread.currentActivityThread().isInit() || mBoundApplication == null
                || mBoundApplication.info == null) {
            Slog.w(TAG, "Skipping stale job service because its virtual application is unavailable: "
                    + serviceInfo.packageName + "/" + serviceInfo.name);
            return null;
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        JobService service;
        Class<?> jobClass;
        try {
            jobClass = classLoader.loadClass(serviceInfo.name);
        } catch (ClassNotFoundException e) {

            if (serviceInfo.name.contains("google.android.gms") ||
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService class not found, skipping: " + serviceInfo.name);
                return null;
            }
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }
        // Some apps schedule an androidx JobIntentService / plain Service as a "job" — e.g.
        // WhatsApp's com.whatsapp.infra.push.RegistrationIntentService (a JobIntentService, which
        // extends Service, NOT android.app.job.JobService). Casting it to JobService throws a
        // ClassCastException on EVERY dispatch, spams the log, and leaves the job dangling so the
        // app keeps retrying push registration (a source of ANRs). Detect it and skip gracefully:
        // the dispatcher then completes the job (onStartJob=false) with no crash.
        if (!JobService.class.isAssignableFrom(jobClass)) {
            Slog.w(TAG, "Scheduled component is not a JobService (likely a JobIntentService), skipping: " + serviceInfo.name);
            return null;
        }
        try {
            service = (JobService) jobClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    BActivityThread.currentActivityThread().getActivityThread(),
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "JobService creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage());
            return null;
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            BlackBoxCore.get().getHandler().post(() -> {
                
                Object bindData = createBindApplicationData(packageName, processName);
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            
            Object bindData = createBindApplicationData(packageName, processName);
            handleBindApplication(packageName, processName);
        }
    }
    
    
    private Object createBindApplicationData(String packageName, String processName) {
        try {
            
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, getUserId());
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            
            
            
            return new Object() {
                public ApplicationInfo getInfo() { return applicationInfo; }
                public List<ProviderInfo> getProviders() { 
                    return packageInfo.providers != null ? Arrays.asList(packageInfo.providers) : new ArrayList<>();
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Error creating bind application data", e);
            
            return new Object() {
                public ApplicationInfo getInfo() { return null; }
                public List<ProviderInfo> getProviders() { return new ArrayList<>(); }
            };
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            if (!mRuntimeIsolationLatch.await(45, java.util.concurrent.TimeUnit.SECONDS)
                    || !mRuntimeIsolationReady) {
                Throwable error = mRuntimeIsolationError;
                throw new SecurityException("Runtime isolation was not ready"
                        + (error == null ? "" : ": " + error.getClass().getSimpleName()), error);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SecurityException("Interrupted while preparing runtime isolation", interrupted);
        }
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }

        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            Slog.w(TAG, "Ignoring stale bind request for missing virtual package: " + packageName
                    + " user=" + BActivityThread.getUserId());
            return;
        }
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));

        Object boundApplication = BRActivityThread.get(BlackBoxCore.mainThread()).mBoundApplication();

        Context packageContext = createPackageContext(applicationInfo);
        IAppOpsManagerProxy.bindToGuestContext(packageContext);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);

        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(getUserId() + ":" + packageName + ":" + processName);
        }

        VirtualRuntime.setupRuntime(processName, applicationInfo);

        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }

        NativeCore.init(Build.VERSION.SDK_INT);
        assert packageContext != null;
        IOCore.get().enableRedirect(packageContext);

        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;

        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);

        mBoundApplication = bindData;

        
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);
            // Android 13+ keeps a process-wide Application cache in the internal
            // factory. The legacy entry point deliberately skips that cache;
            // a later activity with another LoadedApk can then run onCreate twice.
            application = (Application) ApplicationCreationCompat.create(
                    loadedApk, Instrumentation.class, Build.VERSION.SDK_INT >= 33);

            if (application == null) {
                Slog.e(TAG, "makeApplication application Error! All attempts failed");
                throw new RuntimeException("Unable to create application - all creation methods failed");
            }
            
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
            ContextCompat.fix((Context) BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext());
            ContextCompat.fix(mInitialApplication);
            installProviders(mInitialApplication, bindData.processName, bindData.providers);

            onBeforeApplicationOnCreate(packageName, processName, application);
            AppInstrumentation.get().callApplicationOnCreate(application);
            onAfterApplicationOnCreate(packageName, processName, application);

            HookManager.get().checkEnv(HCallbackProxy.class);
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in handleBindApplication", e);
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }
    
    
    /** Test-only opt-in, applied after the guest Activity configures its WebViews. */
    public static void enableActivityWebViewDiagnostics() {
        String packageName = getAppPackageName();
        if (top.niunaijun.blackbox.BuildConfig.DEBUG
                && packageName != null
                && packageName.equals(getAppProcessName())
                && packageName.equals(top.niunaijun.blackbox.BuildConfig.DIAGNOSTIC_WEBVIEW_PACKAGE)) {
            WebView.setWebContentsDebuggingEnabled(true);
            Slog.i(TAG, "Activity WebView request diagnostics enabled for " + packageName);
        }
    }

    private void initializeJarEnvironment() {
        try {
            Slog.d(TAG, "Initializing JAR environment for DEX loading");
            
            
            JarManager jarManager = JarManager.getInstance();
            if (!jarManager.isReady()) {
                Slog.d(TAG, "JarManager not ready, initializing synchronously");
                jarManager.initializeSync();
            }
            
            
            File emptyJar = jarManager.getEmptyJar();
            if (emptyJar == null || !emptyJar.exists()) {
                Slog.w(TAG, "Empty JAR not available, attempting to recreate");
                jarManager.clearCache();
                jarManager.initializeSync();
                emptyJar = jarManager.getEmptyJar();
            }
            
            if (emptyJar != null && emptyJar.exists()) {
                Slog.d(TAG, "Empty JAR verified: " + emptyJar.getAbsolutePath());
            } else {
                Slog.w(TAG, "Empty JAR still not available after retry");
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error initializing JAR environment", e);
        }
    }
    
    
    private Application createApplicationWithFallback(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            Application application = createApplication(appInfo);
            if (application != null) {
                Slog.d(TAG, "Application created successfully: " + appInfo.className);
                return application;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to create application normally: " + e.getMessage());
        }
        
        try {
            
            Slog.d(TAG, "Attempting fallback application creation");
            ClassLoader classLoader = getClassLoader(appInfo);
            if (classLoader == null) {
                Slog.w(TAG, "ClassLoader is null, using system class loader");
                classLoader = ClassLoader.getSystemClassLoader();
            }
            
            Class<?> appClass = classLoader.loadClass(appInfo.className);
            Application application = (Application) appClass.newInstance();
            
            
            ensureApplicationBaseContext(application, appInfo);
            
            Slog.d(TAG, "Fallback application creation successful");
            return application;
            
        } catch (Exception e) {
            Slog.e(TAG, "Fallback application creation failed: " + e.getMessage());
            
            
            try {
                Slog.d(TAG, "Creating minimal application wrapper");
                Application wrapper = new Application() {
                    @Override
                    public void onCreate() {
                        super.onCreate();
                        Slog.d(TAG, "Minimal application wrapper onCreate called");
                    }
                };
                
                
                ensureApplicationBaseContext(wrapper, appInfo);
                
                return wrapper;
            } catch (Exception wrapperException) {
                Slog.e(TAG, "Failed to create minimal application wrapper", wrapperException);
                return null;
            }
        }
    }
    
    
    private void installContentProvidersWithFallback(Application application, Object data) {
        try {
            List<android.content.pm.ProviderInfo> providers = getProviderInfoList(data);
            if (providers == null || providers.isEmpty()) {
                Slog.d(TAG, "No content providers to install");
                return;
            }
            
            Slog.d(TAG, "Installing " + providers.size() + " content providers");
            
            for (android.content.pm.ProviderInfo providerInfo : providers) {
                try {
                    installContentProvider(application, providerInfo);
                    Slog.d(TAG, "Successfully installed provider: " + providerInfo.name);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to install provider " + providerInfo.name + ": " + e.getMessage());
                    
                }
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error installing content providers", e);
        }
    }
    
    
    private android.content.pm.ApplicationInfo getApplicationInfo(Object data) {
        try {
            
            if (data != null) {
                try {
                    
                    Method getInfoMethod = data.getClass().getMethod("getInfo");
                    ApplicationInfo appInfo = (ApplicationInfo) getInfoMethod.invoke(data);
                    if (appInfo != null) {
                        return appInfo;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error getting info from custom data object: " + e.getMessage());
                }
            }
            
            
            String packageName = BlackBoxCore.getAppPackageName();
            if (packageName != null) {
                PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, getUserId());
                return packageInfo.applicationInfo;
            }
            
            return null;
        } catch (Exception e) {
            Slog.e(TAG, "Error getting application info", e);
            return null;
        }
    }
    
    
    private ClassLoader getClassLoader(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            String sourceDir = appInfo.sourceDir;
            if (sourceDir != null) {
                StringBuilder dexPath = new StringBuilder(sourceDir);
                if (appInfo.splitSourceDirs != null) {
                    for (String splitSourceDir : appInfo.splitSourceDirs) {
                        if (!TextUtils.isEmpty(splitSourceDir)) {
                            dexPath.append(File.pathSeparator).append(splitSourceDir);
                        }
                    }
                }
                return new dalvik.system.PathClassLoader(
                        dexPath.toString(), ClassLoader.getSystemClassLoader());
            }
            
            
            return ClassLoader.getSystemClassLoader();
        } catch (Exception e) {
            Slog.w(TAG, "Error getting class loader: " + e.getMessage());
            return ClassLoader.getSystemClassLoader();
        }
    }

    
    private Application createApplication(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            ClassLoader classLoader = getClassLoader(appInfo);
            Class<?> appClass = classLoader.loadClass(appInfo.className);
            Application application = (Application) appClass.newInstance();
            
            
            ensureApplicationBaseContext(application, appInfo);
            
            return application;
        } catch (Exception e) {
            Slog.e(TAG, "Error creating application: " + e.getMessage());
            return null;
        }
    }
    
    
    private void ensureApplicationBaseContext(Application application, android.content.pm.ApplicationInfo appInfo) {
        try {
            
            if (application.getBaseContext() != null) {
                Slog.d(TAG, "Application already has base context: " + appInfo.className);
                return;
            }
            
            
            Context packageContext = createPackageContext(appInfo);
            if (packageContext == null) {
                Slog.w(TAG, "Could not create package context for application: " + appInfo.className + ", using fallback");
                
                packageContext = createFallbackContext(appInfo.packageName);
            }
            
            
            if (packageContext == null) {
                Slog.e(TAG, "Failed to create any context for application: " + appInfo.className);
                return;
            }
            
            
            try {
                Method attachBaseContext = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
                attachBaseContext.setAccessible(true);
                attachBaseContext.invoke(application, packageContext);
                Slog.d(TAG, "Successfully attached base context to application: " + appInfo.className);
            } catch (Exception e) {
                Slog.w(TAG, "Could not attach base context to application: " + e.getMessage());
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error ensuring application base context: " + e.getMessage());
        }
    }
    
    
    private Context createFallbackContext(String packageName) {
        try {
            Context baseContext = BlackBoxCore.getContext();
            if (baseContext == null) {
                Slog.e(TAG, "BlackBoxCore.getContext() is null, cannot create fallback context");
                return null;
            }
            
            
            return new ContextWrapper(baseContext) {
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public android.content.pm.PackageManager getPackageManager() {
                    try {
                        return baseContext.getPackageManager();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting package manager from base context: " + e.getMessage());
                        return null;
                    }
                }
                
                @Override
                public android.content.res.Resources getResources() {
                    try {
                        return baseContext.getResources();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting resources from base context: " + e.getMessage());
                        try {
                            return android.content.res.Resources.getSystem();
                        } catch (Exception e2) {
                            Slog.e(TAG, "Error getting system resources: " + e2.getMessage());
                            return null;
                        }
                    }
                }
                
                @Override
                public ClassLoader getClassLoader() {
                    try {
                        return baseContext.getClassLoader();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting class loader from base context: " + e.getMessage());
                        try {
                            return ClassLoader.getSystemClassLoader();
                        } catch (Exception e2) {
                            Slog.e(TAG, "Error getting system class loader: " + e2.getMessage());
                            return null;
                        }
                    }
                }
                
                @Override
                public Context getApplicationContext() {
                    try {
                        return baseContext.getApplicationContext();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting application context from base context: " + e.getMessage());
                        return this;
                    }
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create fallback context for " + packageName + ": " + e.getMessage());
            return null;
        }
    }
    
    
    private List<android.content.pm.ProviderInfo> getProviderInfoList(Object data) {
        try {
            
            if (data != null) {
                try {
                    
                    Method getProvidersMethod = data.getClass().getMethod("getProviders");
                    List<ProviderInfo> providers = (List<ProviderInfo>) getProvidersMethod.invoke(data);
                    if (providers != null) {
                        return providers;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error getting providers from custom data object: " + e.getMessage());
                }
            }
            
            
            return new ArrayList<>();
        } catch (Exception e) {
            Slog.e(TAG, "Error getting provider info list", e);
            return new ArrayList<>();
        }
    }
    
    
    private void installContentProvider(Application application, android.content.pm.ProviderInfo providerInfo) {
        try {
            
            if (application == null) {
                Slog.w(TAG, "Application is null, cannot install content provider: " + providerInfo.name);
                return;
            }
            
            
            ClassLoader classLoader = application.getClassLoader();
            if (classLoader == null) {
                Slog.w(TAG, "Application class loader is null, using system class loader for: " + providerInfo.name);
                classLoader = ClassLoader.getSystemClassLoader();
            }
            
            
            android.content.ContentProvider provider = (android.content.ContentProvider) classLoader
                .loadClass(providerInfo.name).newInstance();
            
            
            provider.attachInfo(application, providerInfo);
            
            
            
            Slog.d(TAG, "Content provider installed: " + providerInfo.name);
            
        } catch (Exception e) {
            Slog.e(TAG, "Error installing content provider " + providerInfo.name, e);
        }
    }
    
    
    private void setApplication(Application application) {
        try {
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(application);
            Slog.d(TAG, "Application set in ActivityThread successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Error setting application in ActivityThread", e);
        }
    }

    private void handleSecurityException(SecurityException se, String packageName, String processName, Context packageContext) {
        Slog.w(TAG, "Handling SecurityException for " + packageName);
        
        
        try {
                            Application basicApp = createMinimalApplication(packageContext, packageName);
            if (basicApp != null) {
                mInitialApplication = basicApp;
                BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
                ContextCompat.fix(mInitialApplication);
                
                
                Slog.w(TAG, "Created basic application, skipping problematic operations");
                return;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create basic application after SecurityException: " + e.getMessage());
        }
        
        
        throw new RuntimeException("Unable to handle SecurityException", se);
    }

    private void installProvidersWithErrorHandling(Context context, String processName, List<ProviderInfo> providers) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : providers) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (SecurityException se) {
                    Slog.w(TAG, "SecurityException installing provider " + providerInfo.name + ": " + se.getMessage());
                    
                } catch (Throwable t) {
                    Slog.w(TAG, "Error installing provider " + providerInfo.name + ": " + t.getMessage());
                    
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            try {
                ContentProviderDelegate.init();
            } catch (Exception e) {
                Slog.w(TAG, "Error initializing ContentProviderDelegate: " + e.getMessage());
            }
        }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    
    private static Context createMinimalPackageContext(ApplicationInfo info) {
        try {
            
            Context baseContext = BlackBoxCore.getContext();
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, 0);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with minimal flags for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with minimal flags for " + info.packageName + ": " + e.getMessage());
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with ignore security for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with ignore security for " + info.packageName + ": " + e.getMessage());
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_INCLUDE_CODE);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with include code for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with include code for " + info.packageName + ": " + e.getMessage());
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create minimal package context for " + info.packageName + ": " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Using base context as fallback for " + info.packageName);
        return createWrappedBaseContext(info.packageName);
    }

    
    private static Context createWrappedBaseContext(String packageName) {
        try {
            Context baseContext = BlackBoxCore.getContext();
            
            
            return new ContextWrapper(baseContext) {
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public PackageManager getPackageManager() {
                    return baseContext.getPackageManager();
                }
                
                @Override
                public Resources getResources() {
                    return baseContext.getResources();
                }
                
                @Override
                public ClassLoader getClassLoader() {
                    return baseContext.getClassLoader();
                }
                
                @Override
                public Context getApplicationContext() {
                    return baseContext.getApplicationContext();
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create wrapped base context for " + packageName + ": " + e.getMessage());
            
            return BlackBoxCore.getContext();
        }
    }

    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (isUnsupportedPlayStoreProvider(providerInfo)) {
                        Slog.w(TAG, "Skipping incompatible virtual Play provider: " + providerInfo.name);
                        continue;
                    }
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    /**
     * These two recent Finsky providers cast process-global dependency objects that Android's
     * virtual package loader cannot safely share. They are optional Security Hub / peer-to-peer
     * cache features, not Play sign-in, installation, billing or GMS notification providers.
     */
    private static boolean isUnsupportedPlayStoreProvider(ProviderInfo info) {
        if (info == null || !"com.android.vending".equals(info.packageName)) return false;
        return "com.google.android.finsky.securityhub.SecurityHubContentProvider".equals(info.name)
                || "com.google.android.finsky.setup.p2p.CachedPackageContentProvider".equals(info.name);
    }

    public Object getPackageInfo() {
        return mBoundApplication.info;
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }



    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(BlackBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) throws RemoteException {

    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (!isInit()) {
            bindApplication(BActivityThread.getAppConfig().packageName, BActivityThread.getAppConfig().processName);
        }
        String[] split = providerInfo.authority.split(";");
        for (String auth : split) {
            ContentProviderClient contentProviderClient = BlackBoxCore.getContext()
                    .getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(contentProviderClient).mContentProvider();
            if (iInterface == null)
                continue;
            return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
            if (activities.isEmpty())
                return;
            Object clientRecord = activities.get(token);
            if (clientRecord == null)
                return;
            Activity activity = getActivityByToken(token);

            while (activity.getParent() != null) {
                activity = activity.getParent();
            }

            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                newIntent = BRReferrerIntent.get()._new(intent, BlackBoxCore.getHostPkg());
            } else {
                newIntent = intent;
            }
            Object mainThread = BlackBoxCore.mainThread();
            if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent)
                );
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent),
                        true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(ReceiverData data) throws RemoteException {
        if (!isInit()) {
            bindApplication();
        }
        mH.post(() -> {
            BroadcastReceiver mReceiver = null;
            Intent intent = data.intent;
            ActivityInfo activityInfo = data.activityInfo;
            BroadcastReceiver.PendingResult pendingResult = data.data.build();

            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader classLoader = baseContext.getClassLoader();
                intent.setExtrasClassLoader(classLoader);

                mReceiver = (BroadcastReceiver) classLoader.loadClass(activityInfo.name).newInstance();
                BRBroadcastReceiver.get(mReceiver).setPendingResult(pendingResult);
                mReceiver.onReceive(baseContext, intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(mReceiver).getPendingResult();
                if (finish != null) {
                    finish.finish();
                }
                BlackBoxCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                Slog.e(TAG,
                        "Error receiving broadcast " + intent
                                + " in " + mReceiver);
            }
        });
    }

    @Override
    public Bundle verifyProxyRoute(String expectedRouteId, String expectedExitIp) {
        Bundle out = new Bundle();
        if (!mRuntimeIsolationReady) {
            Throwable isolationError = mRuntimeIsolationError;
            out.putBoolean("ok", false);
            out.putString("state",
                    isolationError == null ? "ISOLATION_STARTING" : "ISOLATION_FAILED");
            out.putString("err", isolationError == null
                    ? "Runtime isolation is still starting"
                    : "Runtime isolation failed: " + isolationError.getClass().getSimpleName());
            return out;
        }
        String applied = top.niunaijun.blackbox.core.GuestProxy.CURRENT_ROUTE_ID;
        out.putString("routeId", applied);
        out.putInt("pid", android.os.Process.myPid());
        out.putString("processName", getAppProcessName());
        if (applied == null || expectedRouteId == null || !applied.equals(expectedRouteId)) {
            out.putBoolean("ok", false);
            out.putString("state", "ROUTE_MISMATCH");
            out.putString("err", "The running guest does not have the expected route");
            return out;
        }

        long started = SystemClock.elapsedRealtime();
        try {
            String ip = null;
            Throwable lastProbeError = null;
            // An exit-check vendor can be temporarily unavailable even while the assigned route is
            // carrying app traffic. Probe independent HTTPS endpoints inside this exact guest
            // process; every attempt still passes through the same fail-closed proxy hook.
            String[] endpoints = {
                    "https://checkip.amazonaws.com/",
                    "https://api.ipify.org/?format=text"
            };
            for (String endpoint : endpoints) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Connection", "close");
                    connection.setRequestProperty("User-Agent", "BlackBoxRouteProbe/1");
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String candidate = reader.readLine();
                        candidate = candidate == null ? "" : candidate.trim();
                        if (!candidate.matches("^[0-9a-fA-F:.]{3,64}$")) {
                            throw new java.io.IOException("Invalid exit IP response");
                        }
                        ip = candidate;
                        break;
                    }
                } catch (Throwable error) {
                    lastProbeError = error;
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            if (ip == null) {
                throw new java.io.IOException("All in-clone exit checks failed", lastProbeError);
            }
            out.putString("exitIp", ip);
            boolean exitChanged = expectedExitIp != null && !expectedExitIp.trim().isEmpty()
                    && !ip.equalsIgnoreCase(expectedExitIp.trim());
            out.putBoolean("exitChanged", exitChanged);
            boolean leakGuardsReady = verifyLeakGuards(out);
            boolean geoGuardReady = verifyGeoConsistency(out);
            if (!leakGuardsReady) {
                out.putBoolean("ok", false);
                out.putString("state", "LEAK_GUARD_FAILED");
                out.putString("err", "Isolation guard failed: "
                        + out.getString("leakFailures", "unknown"));
            } else if (!geoGuardReady) {
                out.putBoolean("ok", false);
                out.putString("state", "GEO_GUARD_FAILED");
                out.putString("err", "Geo guard failed: " + out.getString("geoFailures", "unknown"));
            } else {
                // Mobile/residential pools may rotate the public exit while retaining the exact
                // authenticated proxy session. Route identity + in-guest exit + leak guards are
                // the security proof; a changed public IP is telemetry, not a direct-leak signal.
                out.putBoolean("ok", true);
                out.putString("state", "EXIT_VERIFIED");
            }
        } catch (Throwable error) {
            out.putBoolean("ok", false);
            out.putString("state", "EXIT_CHECK_FAILED");
            out.putString("err", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            out.putLong("latencyMs", SystemClock.elapsedRealtime() - started);
        }
        return out;
    }

    private boolean verifyGeoConsistency(Bundle out) {
        boolean ready = verifyGeoConsistencyOnce(out);
        if (!ready && top.niunaijun.blackbox.core.GuestProxy.refreshGeoConsistency(
                getUserId(), getAppPackageName())) {
            out.putBoolean("geoRefreshed", true);
            ready = verifyGeoConsistencyOnce(out);
        }
        return ready;
    }

    private boolean verifyGeoConsistencyOnce(Bundle out) {
        String expectedCountry = top.niunaijun.blackbox.core.GuestProxy.CURRENT_COUNTRY_ISO;
        String expectedTimezone = top.niunaijun.blackbox.core.GuestProxy.CURRENT_TIMEZONE_ID;
        Double expectedLatitude = top.niunaijun.blackbox.core.GuestProxy.CURRENT_LATITUDE;
        Double expectedLongitude = top.niunaijun.blackbox.core.GuestProxy.CURRENT_LONGITUDE;
        boolean simReady = true;
        boolean localeReady = true;
        boolean timezoneReady = true;
        boolean locationReady = true;

        if (expectedCountry != null && !expectedCountry.isEmpty()) {
            top.niunaijun.blackbox.core.DeviceProfile profile =
                    top.niunaijun.blackbox.core.DeviceProfile.CURRENT;
            simReady = profile != null && profile.simSpoofed
                    && expectedCountry.equalsIgnoreCase(profile.simCountryIso);
            localeReady = expectedCountry.equalsIgnoreCase(
                    java.util.Locale.getDefault().getCountry());
        }
        if (expectedTimezone != null && !expectedTimezone.isEmpty()) {
            timezoneReady = expectedTimezone.equals(java.util.TimeZone.getDefault().getID());
        }
        if (expectedLatitude != null && expectedLongitude != null) {
            try {
                top.niunaijun.blackbox.entity.location.BLocation location =
                        top.niunaijun.blackbox.fake.frameworks.BLocationManager.get().getLocation(
                                getUserId(), getAppPackageName());
                locationReady = location != null
                        && Math.abs(location.getLatitude() - expectedLatitude) < 0.000001
                        && Math.abs(location.getLongitude() - expectedLongitude) < 0.000001;
            } catch (Throwable ignored) {
                locationReady = false;
            }
        }

        out.putString("countryIso", expectedCountry == null ? "" : expectedCountry);
        out.putString("timezoneId", expectedTimezone == null ? "" : expectedTimezone);
        if (expectedLatitude != null && expectedLongitude != null) {
            out.putDouble("latitude", expectedLatitude);
            out.putDouble("longitude", expectedLongitude);
        }
        out.putBoolean("simGuard", simReady);
        out.putBoolean("localeGuard", localeReady);
        out.putBoolean("timezoneGuard", timezoneReady);
        out.putBoolean("locationGuard", locationReady);
        boolean ready = simReady && localeReady && timezoneReady && locationReady;
        StringBuilder failures = new StringBuilder();
        if (!simReady) failures.append("SIM");
        if (!localeReady) {
            if (failures.length() > 0) failures.append(',');
            failures.append("locale");
        }
        if (!timezoneReady) {
            if (failures.length() > 0) failures.append(',');
            failures.append("timezone");
        }
        if (!locationReady) {
            if (failures.length() > 0) failures.append(',');
            failures.append("location");
        }
        out.putString("geoFailures", failures.toString());
        out.putBoolean("geoGuard", ready);
        return ready;
    }

    private boolean verifyLeakGuards(Bundle out) {
        boolean dnsGuard = false;
        boolean udpGuard = false;
        boolean kernelGuard = false;
        boolean sensorGuard = false;
        try {
            String probe = "route-" + android.os.Process.myPid() + "-"
                    + SystemClock.elapsedRealtime() + ".invalid";
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(probe);
            if (addresses != null && addresses.length > 0) {
                byte[] raw = addresses[0].getAddress();
                dnsGuard = raw.length == 4 && (raw[0] & 0xff) == 198
                        && ((raw[1] & 0xff) == 18 || (raw[1] & 0xff) == 19);
                if (dnsGuard) out.putString("dnsMode", "PROXY_REMOTE");
            }
        } catch (Throwable ignored) {
        }

        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket();
            byte[] one = new byte[]{0};
            java.net.DatagramPacket packet = new java.net.DatagramPacket(
                    one, one.length,
                    java.net.InetAddress.getByAddress(new byte[]{1, 1, 1, 1}), 3478);
            socket.send(packet);
        } catch (Throwable expectedBlock) {
            udpGuard = true;
        } finally {
            if (socket != null) socket.close();
        }
        try {
            top.niunaijun.blackbox.core.DeviceProfile profile =
                    top.niunaijun.blackbox.core.DeviceProfile.CURRENT;
            String bootPath = "/proc/sys/kernel/random/boot_id";
            String expected = profile == null ? "" : profile.virtualKernelBootId();
            String observed = readFirstLine(bootPath);
            String redirectedPath =
                    top.niunaijun.blackbox.core.IOCore.get().redirectPath(bootPath);
            String redirectedObserved = readFirstLine(redirectedPath);
            boolean redirectRegistered = !bootPath.equals(redirectedPath);
            kernelGuard = profile != null && redirectRegistered
                    && observed.equalsIgnoreCase(expected);
            sensorGuard = profile != null && profile.sensorIsolationActive
                    && top.niunaijun.blackbox.core.SensorSignalIsolation.isActive();
            out.putBoolean("kernelRedirectRegistered", redirectRegistered);
            out.putBoolean("kernelRedirectFileMatch",
                    redirectedObserved.equalsIgnoreCase(expected));
            out.putString("kernelObservedHash", shortDigest(observed));
            out.putString("kernelExpectedHash", shortDigest(expected));
            out.putString("kernelRedirectHash", shortDigest(redirectedObserved));
            top.niunaijun.blackbox.utils.Slog.d(TAG,
                    "kernel guard registered=" + redirectRegistered
                            + " observed=" + shortDigest(observed)
                            + " expected=" + shortDigest(expected)
                            + " target=" + shortDigest(redirectedObserved));
        } catch (Throwable ignored) {
        }
        out.putBoolean("dnsGuard", dnsGuard);
        out.putBoolean("udpGuard", udpGuard);
        out.putBoolean("kernelGuard", kernelGuard);
        out.putBoolean("sensorGuard", sensorGuard);
        StringBuilder failures = new StringBuilder();
        if (!dnsGuard) failures.append("dns");
        if (!udpGuard) {
            if (failures.length() > 0) failures.append(',');
            failures.append("udp");
        }
        if (!kernelGuard) {
            if (failures.length() > 0) failures.append(',');
            failures.append("kernel");
        }
        if (!sensorGuard) {
            if (failures.length() > 0) failures.append(',');
            failures.append("sensor");
        }
        out.putString("leakFailures", failures.toString());
        return dnsGuard && udpGuard && kernelGuard && sensorGuard;
    }

    private static String readFirstLine(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append(String.format(java.util.Locale.US, "%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> iBinderObjectMap =
                BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(iBinderObjectMap.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeCreateApplication(packageName, processName, context, BActivityThread.getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    
    public static void ensureActivityContext(Activity activity) {
        if (activity == null) {
            return;
        }
        
        try {
            
            Context currentContext = activity.getBaseContext();
            if (currentContext != null) {
                Slog.d(TAG, "Activity already has context: " + activity.getClass().getName());
                return;
            }
            
            Slog.w(TAG, "Activity has null context, ensuring valid context: " + activity.getClass().getName());
            
            
            Context validContext = null;
            try {
                validContext = getApplication();
                if (validContext == null) {
                    validContext = BlackBoxCore.getContext();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Could not get application context: " + e.getMessage());
                validContext = BlackBoxCore.getContext();
            }
            
            if (validContext != null) {
                
                try {
                    Context packageContext = validContext.createPackageContext(
                        activity.getPackageName(),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
                    );
                    
                    
                    java.lang.reflect.Method attachBaseContext = Activity.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attachBaseContext.setAccessible(true);
                    attachBaseContext.invoke(activity, packageContext);
                    Slog.d(TAG, "Successfully attached package context to activity: " + activity.getClass().getName());
                } catch (Exception e) {
                    Slog.w(TAG, "Could not attach base context to activity: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error ensuring activity context: " + e.getMessage());
        }
    }

    
    public static void hookActivityThread() {
        try {
            
            Object activityThread = BlackBoxCore.mainThread();
            if (activityThread != null) {
                
                Instrumentation instrumentation = BRActivityThread.get(activityThread).mInstrumentation();
                if (instrumentation != null) {
                    Slog.d(TAG, "Found ActivityThread instrumentation, ensuring it's our AppInstrumentation");
                    
                    
                    if (!(instrumentation instanceof AppInstrumentation)) {
                        Slog.w(TAG, "ActivityThread instrumentation is not our AppInstrumentation, attempting to replace");
                        
                        
                        try {
                            AppInstrumentation appInstrumentation = AppInstrumentation.get();
                            appInstrumentation.injectHook();
                            Slog.d(TAG, "Successfully replaced ActivityThread instrumentation with AppInstrumentation");
                        } catch (Exception e) {
                            Slog.w(TAG, "Could not replace ActivityThread instrumentation: " + e.getMessage());
                        }
                    } else {
                        Slog.d(TAG, "ActivityThread instrumentation is already our AppInstrumentation");
                    }
                } else {
                    Slog.w(TAG, "ActivityThread instrumentation is null");
                }
            } else {
                Slog.w(TAG, "ActivityThread is null");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error hooking ActivityThread: " + e.getMessage());
        }
    }

    
    private Application createMinimalApplication(Context packageContext, String packageName) {
        try {
            Slog.d(TAG, "Creating minimal application for " + packageName);
            
            
            Application app = new Application() {
                @Override
                public void onCreate() {
                    super.onCreate();
                    Slog.d(TAG, "Minimal application onCreate called for " + packageName);
                }
                
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public Context getApplicationContext() {
                    return this;
                }
            };
            
            
            if (packageContext != null) {
                try {
                    Method attachBaseContext = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attachBaseContext.setAccessible(true);
                    attachBaseContext.invoke(app, packageContext);
                    Slog.d(TAG, "Successfully attached base context to minimal application for " + packageName);
                } catch (Exception e) {
                    Slog.w(TAG, "Could not attach base context to minimal application: " + e.getMessage());
                }
            } else {
                Slog.w(TAG, "Package context is null, cannot attach base context to minimal application");
            }
            
            Slog.d(TAG, "Minimal application created successfully for " + packageName);
            return app;
        } catch (Exception e) {
            Slog.e(TAG, "Error creating minimal application for " + packageName, e);
            return null;
        }
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}
