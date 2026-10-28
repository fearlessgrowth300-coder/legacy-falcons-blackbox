package top.niunaijun.blackboxa.profile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import top.niunaijun.blackbox.core.ManagedProfilePolicy

/** Talks to Android's managed-profile APIs. Failures return false or a message; they do not crash. */
class ManagedProfileController(private val context: Context) {
    private val manager = context.getSystemService(DevicePolicyManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val launcher = context.getSystemService(LauncherApps::class.java)

    fun myUserId(): Int = userId(Process.myUserHandle())

    fun myUid(): Int = Process.myUid()

    fun dataDir(): String = context.applicationInfo.dataDir ?: ""

    fun isProfileOwner(): Boolean = manager.isProfileOwnerApp(context.packageName)

    fun otherProfiles(): List<UserHandle> {
        val mine = Process.myUserHandle()
        return users.userProfiles.filter { it != mine }
    }

    fun blockReason(): String? {
        val allowed = try {
            manager.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        } catch (error: Throwable) {
            false
        }
        val resolved = try {
            context.packageManager.resolveActivity(
                provisionIntent(),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (error: Throwable) {
            false
        }
        return ManagedProfilePolicy.blockReason(
            allowed,
            isProfileOwner(),
            samsungContainerInstalled(),
            resolved
        )
    }

    fun provisionIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                BlackBoxDeviceAdminReceiver.component(context)
            )
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Blackbox uses this Android profile so the second account has its own Linux user, " +
                    "data directory, Android ID, keystore, and accounts."
            )
    }

    fun launchStatusIn(user: UserHandle): Boolean = launch(user, ProfileStatusActivity::class.java.name)

    fun launchInstallerIn(user: UserHandle): Boolean = launch(user, InstallInProfileActivity::class.java.name)

    fun launchPackage(user: UserHandle, packageName: String): Boolean {
        return try {
            val activities = launcher.getActivityList(packageName, user)
            if (activities.isEmpty()) return false
            launcher.startMainActivity(activities[0].componentName, user, null, null)
            true
        } catch (error: Throwable) {
            false
        }
    }

    fun installExisting(packageName: String): Boolean {
        if (!isProfileOwner() || packageName.isBlank()) return false
        if (Build.VERSION.SDK_INT < 28) return false
        val admin = BlackBoxDeviceAdminReceiver.component(context)
        return try {
            if (manager.installExistingPackage(admin, packageName)) return true
            manager.enableSystemApp(admin, packageName)
            true
        } catch (error: Throwable) {
            false
        }
    }

    private fun launch(user: UserHandle, className: String): Boolean {
        return try {
            val component = android.content.ComponentName(context.packageName, className)
            launcher.startMainActivity(component, user, null, null)
            true
        } catch (error: Throwable) {
            false
        }
    }

    private fun userId(user: UserHandle): Int {
        return try {
            val method = UserHandle::class.java.getDeclaredMethod("getIdentifier")
            method.isAccessible = true
            method.invoke(user) as Int
        } catch (error: Throwable) {
            -1
        }
    }

    fun userIdOf(user: UserHandle): Int = userId(user)

    private fun samsungContainerInstalled(): Boolean {
        val names = arrayOf(
            "com.samsung.knox.securefolder",
            "com.samsung.android.knox.containercore",
            "com.samsung.android.knox.containeragent"
        )
        return names.any { name ->
            try {
                context.packageManager.getPackageInfo(name, 0)
                true
            } catch (error: Throwable) {
                false
            }
        }
    }
}
