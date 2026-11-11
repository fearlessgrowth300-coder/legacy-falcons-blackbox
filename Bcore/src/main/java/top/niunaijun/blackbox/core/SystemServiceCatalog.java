package top.niunaijun.blackbox.core;

/**
 * System services whose outgoing calls must present the host package.
 * Each name is a proxy installed by HookManager. Clipboard methods in
 * {@link #clipboardContained()} are answered inside the clone and must not
 * be forwarded.
 */
public final class SystemServiceCatalog {
    private SystemServiceCatalog() {}

    public static String[] forwardedProxies() {
        return new String[] {
                "IClipboardManagerProxy",
                "IAppOpsManagerProxy",
                "IInputMethodManagerProxy",
                "ITelephonyManagerProxy",
                "ITelephonyRegistryProxy",
                "ISubProxy",
                "IWifiManagerProxy",
                "ILocationManagerProxy",
                "IAudioServiceProxy",
                "IVibratorServiceProxy",
                "ISensorPrivacyManagerProxy",
                "IPermissionManagerProxy",
                "INotificationManagerProxy",
                "IStorageManagerProxy",
                "IAccountManagerProxy",
                "IMediaSessionManagerProxy",
                "IConnectivityManagerProxy",
                "IDisplayManagerProxy",
                "IAccessibilityManagerProxy",
                "IUserManagerProxy",
                "IAutofillManagerProxy",
                "ITextServicesManagerProxy",
                "IWindowManagerProxy",
                "IActivityTaskManagerProxy",
                "IPackageManagerProxy",
                "HostPackageServiceProxy"
        };
    }

    /** Clipboard operations answered from clone storage, never ClipboardService. */
    public static String[] clipboardContained() {
        return new String[] {
                "setPrimaryClip",
                "setPrimaryClipAsPackage",
                "getPrimaryClip",
                "getPrimaryClipDescription",
                "hasPrimaryClip",
                "hasClipboardText",
                "clearPrimaryClip",
                "getPrimaryClipSource",
                "addPrimaryClipChangedListener",
                "removePrimaryClipChangedListener",
                "areClipboardAccessNotificationsEnabledForUser",
                "setClipboardAccessNotificationsEnabledForUser"
        };
    }

    public static String[] callerPackageFields() {
        return new String[] {
                "packageName",
                "mPackageName",
                "opPackageName",
                "mOpPackageName",
                "callingPackage",
                "mCallingPackage"
        };
    }
}
