package top.niunaijun.blackboxa.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import top.niunaijun.blackbox.core.ProfileWorkApps
import top.niunaijun.blackboxa.view.main.WelcomeActivity

/**
 * Runs inside the managed profile, where this app is profile owner.
 * Naming and enabling the profile is not enough: a new profile has no launcher
 * apps until the owner enables them, so the Work tab stays empty.
 */
object ManagedProfileFinalizer {
    private const val TAG = "BlackBoxProfileOwner"

    fun finalizeIfOwner(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!manager.isProfileOwnerApp(context.packageName)) return false
        val admin = BlackBoxDeviceAdminReceiver.component(context)
        try {
            manager.setProfileName(admin, "Blackbox account B")
        } catch (error: Throwable) {
            Log.w(TAG, "profile name was not set: ${error.javaClass.simpleName}")
        }
        try {
            manager.setProfileEnabled(admin)
        } catch (error: Throwable) {
            Log.w(TAG, "profile was not enabled: ${error.javaClass.simpleName}")
        }
        try {
            manager.setApplicationHidden(admin, context.packageName, false)
        } catch (error: Throwable) {
            Log.w(TAG, "launcher visibility was not restored: ${error.javaClass.simpleName}")
        }
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, WelcomeActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (error: Throwable) {
            Log.w(TAG, "launcher activity was not enabled: ${error.javaClass.simpleName}")
        }
        for (packageName in ProfileWorkApps.systemPackages()) {
            enableSystemPackage(manager, admin, packageName)
        }
        try {
            manager.enableSystemApp(
                admin,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            )
        } catch (error: Throwable) {
            Log.w(TAG, "system launcher apps were not enabled: ${error.javaClass.simpleName}")
        }
        try {
            manager.installExistingPackage(admin, ProfileWorkApps.SHIELD_PROXY)
        } catch (error: Throwable) {
            Log.w(TAG, "ShieldProxy was not copied in: ${error.javaClass.simpleName}")
        }
        addShareFilters(manager, admin)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                manager.setCrossProfilePackages(admin, setOf(context.packageName))
            } catch (error: Throwable) {
                Log.w(TAG, "cross-profile package was not set: ${error.javaClass.simpleName}")
            }
        }
        try {
            context.getSystemService(android.os.UserManager::class.java)
                .requestQuietModeEnabled(false, Process.myUserHandle())
        } catch (error: Throwable) {
            Log.w(TAG, "quiet mode was not cleared: ${error.javaClass.simpleName}")
        }
        return true
    }

    /** Profile owner only. Removes this managed profile, not the personal user. */
    fun wipeIfOwner(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!manager.isProfileOwnerApp(context.packageName)) return false
        return try {
            manager.wipeData(0)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "profile was not removed", error)
            false
        }
    }

    private fun enableSystemPackage(manager: DevicePolicyManager, admin: ComponentName, packageName: String) {
        try {
            manager.enableSystemApp(admin, packageName)
        } catch (error: Throwable) {
            try {
                manager.installExistingPackage(admin, packageName)
            } catch (fallback: Throwable) {
                Log.w(TAG, "$packageName was not enabled: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun addShareFilters(manager: DevicePolicyManager, admin: ComponentName) {
        val flags = DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED or
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW).forEach { action ->
            try {
                val filter = IntentFilter(action)
                filter.addCategory(Intent.CATEGORY_DEFAULT)
                if (action == Intent.ACTION_SEND) filter.addDataType("*/*")
                if (action == Intent.ACTION_VIEW) {
                    filter.addDataScheme("https")
                    filter.addDataScheme("http")
                }
                manager.addCrossProfileIntentFilter(admin, filter, flags)
            } catch (error: Throwable) {
                Log.w(TAG, "cross-profile filter was not added: ${error.javaClass.simpleName}")
            }
        }
    }
}
