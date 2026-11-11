package top.niunaijun.blackbox.core;

/**
 * Package name sent to the phone's system services. The process uid is the
 * host app, so a guest package in a calling-package argument is rejected.
 * Lookups that ask about a package by name keep the original string.
 */
public final class SystemCallerIdentity {
    private SystemCallerIdentity() {}

    public static int rewrite(Object[] args, String guestPackage, String hostPackage) {
        if (args == null || guestPackage == null || guestPackage.isEmpty()
                || hostPackage == null || guestPackage.equals(hostPackage)) {
            return 0;
        }
        int replaced = 0;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (guestPackage.equals(arg)) {
                args[i] = hostPackage;
                replaced++;
            } else if (arg instanceof String[]) {
                String[] values = (String[]) arg;
                for (int j = 0; j < values.length; j++) {
                    if (guestPackage.equals(values[j])) {
                        values[j] = hostPackage;
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    /** True when the argument is the package being queried, not the caller. */
    public static boolean keepsTargetPackage(String methodName) {
        if (methodName == null) return false;
        switch (methodName) {
            case "getPackageInfo":
            case "getPackageInfoVersioned":
            case "getPackageUid":
            case "getPackageGids":
            case "getApplicationInfo":
            case "getActivityInfo":
            case "getServiceInfo":
            case "getProviderInfo":
            case "getReceiverInfo":
            case "getComponentEnabledSetting":
            case "getInstallerPackageName":
            case "getInstallSourceInfo":
            case "resolveContentProvider":
            case "getPackagesForUid":
            case "getNameForUid":
            case "checkSignatures":
            case "hasSigningCertificate":
            case "getDrawable":
            case "queryIntentActivities":
            case "queryIntentServices":
            case "queryIntentReceivers":
            case "queryIntentContentProviders":
            case "resolveIntent":
            case "resolveService":
            case "getInstalledPackages":
            case "getInstalledApplications":
            case "queryContentProviders":
            case "getSharedLibraries":
            case "setComponentEnabledSetting":
            case "setComponentEnabledSettings":
            case "getContentProvider":
            case "getContentProviderExternal":
            case "checkPermission":
            case "shouldShowRequestPermissionRationale":
                return true;
            default:
                return false;
        }
    }
}
