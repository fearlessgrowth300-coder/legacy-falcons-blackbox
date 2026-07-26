package top.niunaijun.blackbox.fake.frameworks;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.entity.pm.InstalledPackage;
import top.niunaijun.blackbox.utils.TransactionThrottler;


public class BPackageManager extends BlackManager<IBPackageManagerService> {
    private static final String INSTAGRAM_PACKAGE = "com.instagram.android";
    private static final String INSTAGRAM_MISSING_LAUNCHER = "com.instagram.android.InternalLauncher";
    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.android.activity.MainTabActivity";
    private static final String INSTAGRAM_COLD_START_ACTIVITY = "com.instagram.modal.ModalActivity";
    private static final BPackageManager sPackageManager = new BPackageManager();
    private final TransactionThrottler transactionThrottler = new TransactionThrottler();
    public static BPackageManager get() {
        return sPackageManager;
    }
    
    
    public void resetTransactionThrottler() {
        transactionThrottler.reset();
        Log.d(TAG, "Transaction throttler reset");
    }
    
    
    
    public void forceReinitialize() {
        Log.d(TAG, "Force reinitializing PackageManager service");
        clearServiceCache();
        resetTransactionThrottler();
        
        
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                Log.d(TAG, "Successfully reinitialized PackageManager service");
            } else {
                Log.w(TAG, "Failed to reinitialize PackageManager service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during service reinitialization", e);
        }
    }

    
    public IBPackageManagerService getServiceWithFallback() {
        IBPackageManagerService service = getService();
        if (service == null) {
            Log.w(TAG, "PackageManager service is null, attempting reinitialization");
            forceReinitialize();
            service = getService();
        }
        return service;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.PACKAGE_MANAGER;
    }

    public Intent getLaunchIntentForPackage(String packageName, int userId) {
        // Never resolve a virtual launch through Android's host PackageManager. If the virtual
        // package service is unavailable, returning a physical app intent can open the real-phone
        // app outside BlackBox and bypass the clone's route and identity boundary. The virtual
        // queries below already return no result while the service is unhealthy, which is the
        // required fail-closed behavior.
        
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve,
                0,
                intentToResolve.resolveTypeIfNeeded(BlackBoxCore.getContext().getContentResolver()),
                userId);

        
        if (ris == null || ris.size() <= 0) {
            
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = queryIntentActivities(intentToResolve,
                    0,
                    intentToResolve.resolveTypeIfNeeded(BlackBoxCore.getContext().getContentResolver()),
                    userId);
        }
        if (ris == null || ris.size() <= 0) {
            // Feature-split launch aliases can be present in the merged virtual package while
            // older IntentResolver code still misses their split-owned intent filter. Use the
            // host PackageManager only to discover the component name, then require that exact
            // component to resolve in the virtual package before constructing a new virtual
            // intent. Never return or start the host intent itself.
            Intent installedLauncher =
                    BlackBoxCore.getPackageManager().getLaunchIntentForPackage(packageName);
            ComponentName installedComponent =
                    installedLauncher == null ? null : installedLauncher.getComponent();
            ActivityInfo virtualActivity = installedComponent == null
                    ? null : getActivityInfo(installedComponent, 0, userId);
            if (virtualActivity != null
                    && packageName.equals(virtualActivity.packageName)) {
                Intent virtualLaunch = new Intent(Intent.ACTION_MAIN);
                virtualLaunch.addCategory(Intent.CATEGORY_LAUNCHER);
                virtualLaunch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                virtualLaunch.setComponent(new ComponentName(
                        virtualActivity.packageName, virtualActivity.name));
                Log.i(TAG, "Resolved split launcher through verified virtual component: "
                        + installedComponent.flattenToShortString());
                return virtualLaunch;
            }
            Log.w(TAG, "No virtual launcher component for " + packageName);
            return null;
        }
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityInfo launchActivity = ris.get(0).activityInfo;
        String launchActivityName = launchActivity.name;

        // Some Instagram packages advertise InternalLauncher first even though it is not a safe
        // cold virtual root. Prefer the real MainTabActivity from the resolved launcher set. The
        // old ModalActivity fallback now finishes immediately in current Instagram builds.
        if (INSTAGRAM_PACKAGE.equals(packageName)
                && INSTAGRAM_MISSING_LAUNCHER.equals(launchActivityName)) {
            ResolveInfo main = null;
            for (ResolveInfo candidate : ris) {
                if (candidate.activityInfo != null
                        && INSTAGRAM_MAIN_ACTIVITY.equals(candidate.activityInfo.name)) {
                    main = candidate;
                    break;
                }
            }
            if (main != null) {
                launchActivity = main.activityInfo;
                launchActivityName = launchActivity.name;
                Log.w(TAG, "Replacing Instagram InternalLauncher with MainTabActivity");
            } else {
                Log.w(TAG, "MainTabActivity unavailable; using legacy Instagram cold-start activity");
                launchActivityName = INSTAGRAM_COLD_START_ACTIVITY;
            }
        }
        intent.setClassName(launchActivity.packageName, launchActivityName);
        return intent;
    }
    
    
    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        
        if (transactionThrottler.shouldThrottle()) {
            Log.w(TAG, "Throttling resolveService due to recent failures");
            return null;
        }
        
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                ResolveInfo result = service.resolveService(intent, flags, resolvedType, userId);
                
                transactionThrottler.reset();
                return result;
            } else {
                Log.w(TAG, "PackageManager service is null, returning null for resolveService");
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during resolveService, clearing service and retrying", e);
            transactionThrottler.recordFailure();
            
            clearServiceCache();
            
            try {
                IBPackageManagerService service = getService();
                if (service != null) {
                    ResolveInfo result = service.resolveService(intent, flags, resolvedType, userId);
                    transactionThrottler.reset(); 
                    return result;
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for resolveService", retryException);
                transactionThrottler.recordFailure();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in resolveService", e);
            transactionThrottler.recordFailure();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in resolveService", e);
            transactionThrottler.recordFailure();
        }
        return null;
    }

    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        
        if (transactionThrottler.shouldThrottle()) {
            Log.w(TAG, "Throttling resolveActivity due to recent failures");
            return null;
        }
        
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                ResolveInfo result = service.resolveActivity(intent, flags, resolvedType, userId);
                
                transactionThrottler.reset();
                return result;
            } else {
                Log.w(TAG, "PackageManager service is null, returning null for resolveActivity");
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during resolveActivity, clearing service and retrying", e);
            transactionThrottler.recordFailure();
            
            clearServiceCache();
            
            try {
                IBPackageManagerService service = getService();
                if (service != null) {
                    ResolveInfo result = service.resolveActivity(intent, flags, resolvedType, userId);
                    transactionThrottler.reset(); 
                    return result;
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for resolveActivity", retryException);
                transactionThrottler.recordFailure();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in resolveActivity", e);
            transactionThrottler.recordFailure();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in resolveActivity", e);
            transactionThrottler.recordFailure();
        }
        return null;
    }

    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        
        if (transactionThrottler.shouldThrottle()) {
            Log.w(TAG, "Throttling resolveContentProvider due to recent failures");
            return null;
        }
        
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                ProviderInfo result = service.resolveContentProvider(authority, flags, userId);
                
                transactionThrottler.reset();
                return result;
            } else {
                Log.w(TAG, "PackageManager service is null, returning null for resolveContentProvider");
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during resolveContentProvider, clearing service and retrying", e);
            transactionThrottler.recordFailure();
            
            clearServiceCache();
            
            try {
                IBPackageManagerService service = getService();
                if (service != null) {
                    ProviderInfo result = service.resolveContentProvider(authority, flags, userId);
                    transactionThrottler.reset(); 
                    return result;
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for resolveContentProvider", retryException);
                transactionThrottler.recordFailure();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in resolveContentProvider", e);
            transactionThrottler.recordFailure();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in resolveContentProvider", e);
            transactionThrottler.recordFailure();
        }
        return null;
    }

    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        try {
            return getService().resolveIntent(intent, resolvedType, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        try {
            IBPackageManagerService service = getServiceWithFallback();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getApplicationInfo; failing closed");
                return null;
            }
            return service.getApplicationInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getApplicationInfo for " + packageName, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getApplicationInfo for " + packageName, e);
            return null;
        }
    }

    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        try {
            IBPackageManagerService service = getServiceWithFallback();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getPackageInfo; failing closed");
                return null;
            }
            return service.getPackageInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getPackageInfo for " + packageName, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getPackageInfo for " + packageName, e);
            return null;
        }
    }

    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getServiceInfo, returning null");
                return null;
            }
            return service.getServiceInfo(component, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getServiceInfo for " + component, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getServiceInfo for " + component, e);
            return null;
        }
    }

    public ActivityInfo getReceiverInfo(ComponentName componentName, int flags, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getReceiverInfo, returning null");
                return null;
            }
            return service.getReceiverInfo(componentName, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getReceiverInfo for " + componentName, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getReceiverInfo for " + componentName, e);
            return null;
        }
    }

    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getActivityInfo, returning null");
                return null;
            }
            return service.getActivityInfo(component, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getActivityInfo for " + component, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getActivityInfo for " + component, e);
            return null;
        }
    }

    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service == null) {
                Log.w(TAG, "PackageManager service is null for getProviderInfo, returning null");
                return null;
            }
            return service.getProviderInfo(component, flags, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getProviderInfo for " + component, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getProviderInfo for " + component, e);
            return null;
        }
    }

    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId) {
        
        if (transactionThrottler.shouldThrottle()) {
            Log.w(TAG, "Throttling queryIntentActivities due to recent failures");
            return Collections.emptyList();
        }
        
        
        if (transactionThrottler.getFailureCount() >= 2) {
            Log.w(TAG, "Too many failures, returning empty list for queryIntentActivities");
            return Collections.emptyList();
        }
        
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                List<ResolveInfo> result = service.queryIntentActivities(intent, flags, resolvedType, userId);
                
                transactionThrottler.reset();
                return result;
            } else {
                Log.w(TAG, "PackageManager service is null, returning empty list for queryIntentActivities");
                return Collections.emptyList();
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during queryIntentActivities, clearing cache and retrying", e);
            transactionThrottler.recordFailure();
            clearServiceCache(); 
            
            
            if (transactionThrottler.getFailureCount() < 3) {
                try {
                    
                    IBPackageManagerService service = getService();
                    if (service != null) {
                        List<ResolveInfo> result = service.queryIntentActivities(intent, flags, resolvedType, userId);
                        transactionThrottler.reset(); 
                        return result;
                    }
                } catch (Exception retryException) {
                    Log.e(TAG, "Retry failed for queryIntentActivities", retryException);
                    transactionThrottler.recordFailure();
                }
            } else {
                Log.w(TAG, "Skipping retry due to too many failures");
            }
            return Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in queryIntentActivities", e);
            transactionThrottler.recordFailure();
            crash(e);
        }
        return Collections.emptyList();
    }

    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                return service.queryBroadcastReceivers(intent, flags, resolvedType, userId);
            } else {
                Log.w(TAG, "PackageManager service is null, returning empty list for queryBroadcastReceivers");
                return Collections.emptyList();
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during queryBroadcastReceivers, clearing cache and retrying", e);
            clearServiceCache(); 
            try {
                
                IBPackageManagerService service = getService();
                if (service != null) {
                    return service.queryBroadcastReceivers(intent, flags, resolvedType, userId);
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for queryBroadcastReceivers", retryException);
            }
            return Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in queryBroadcastReceivers", e);
            crash(e);
        }
        return Collections.emptyList();
    }

    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId) {
        try {
            IBPackageManagerService service = getService();
            if (service != null) {
                return service.queryContentProviders(processName, uid, flags, userId);
            } else {
                Log.w(TAG, "PackageManager service is null, returning empty list for queryContentProviders");
                return Collections.emptyList();
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during queryContentProviders, clearing cache and retrying", e);
            clearServiceCache(); 
            try {
                
                IBPackageManagerService service = getService();
                if (service != null) {
                    return service.queryContentProviders(processName, uid, flags, userId);
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for queryContentProviders", retryException);
            }
            return Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in queryContentProviders", e);
            crash(e);
        }
        return Collections.emptyList();
    }

    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        try {
            
            if (file != null && !file.isEmpty()) {
                try {
                    
                    PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(file, 0);
                    if (packageInfo != null) {
                        String packageName = packageInfo.packageName;
                        String hostPackageName = BlackBoxCore.getHostPkg();
                        if (packageName.equals(hostPackageName)) {
                            Log.w(TAG, "Attempt to install BlackBox app detected and blocked: " + packageName);
                            return new InstallResult().installError("Cannot clone BlackBox app from within BlackBox. This would create infinite recursion and is not allowed for security reasons.");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not verify package info for: " + file, e);
                }
            }
            
            return getService().installPackageAsUser(file, option, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        try {
            return getService().getInstalledApplications(flags, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        try {
            return getService().getInstalledPackages(flags, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public void clearPackage(String packageName, int userId) {
        try {
            getService().clearPackage(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void stopPackage(String packageName, int userId) {
        try {
            getService().stopPackage(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
        try {
            getService().uninstallPackageAsUser(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void uninstallPackage(String packageName) {
        try {
            getService().uninstallPackage(packageName);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isInstalled(String packageName, int userId) {
        // Virtual membership is per BlackBox user. A host PackageManager fallback only proves
        // that the shared APK exists on the real phone; it cannot prove this user owns a clone.
        // Always ask the virtual service and return false if that authoritative check is down.
        try {
            IBPackageManagerService service = getServiceWithFallback();
            if (service != null) {
                boolean result = service.isInstalled(packageName, userId);
                transactionThrottler.reset(); 
                return result;
            } else {
                Log.w(TAG, "PackageManager service is null, returning false for isInstalled check");
            }
        } catch (android.os.DeadObjectException e) {
            Log.w(TAG, "PackageManager service died during isInstalled check, clearing service and retrying", e);
            transactionThrottler.recordFailure();
            
            clearServiceCache();
            
            try {
                IBPackageManagerService service = getService();
                if (service != null) {
                    boolean result = service.isInstalled(packageName, userId);
                    transactionThrottler.reset(); 
                    return result;
                }
            } catch (Exception retryException) {
                Log.e(TAG, "Retry failed for isInstalled check", retryException);
                transactionThrottler.recordFailure();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in isInstalled check", e);
            transactionThrottler.recordFailure();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in isInstalled check", e);
            transactionThrottler.recordFailure();
        }
        return false;
    }

    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        try {
            return getService().getInstalledPackagesAsUser(userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public String[] getPackagesForUid(int uid) {
        try {
            return getService().getPackagesForUid(uid, BActivityThread.getUserId());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    private void crash(Throwable e) {
        e.printStackTrace();
    }
}

