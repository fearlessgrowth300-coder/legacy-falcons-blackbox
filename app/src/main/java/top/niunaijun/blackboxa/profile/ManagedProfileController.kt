package top.niunaijun.blackboxa.profile

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import top.niunaijun.blackbox.core.ManagedProfilePolicy
import top.niunaijun.blackbox.core.ProfileStatusCopy
import top.niunaijun.blackboxa.view.main.WelcomeActivity

/** Talks to Android's managed-profile APIs. Failures return false or a message; they do not crash. */
class ManagedProfileController(private val context: Context) {
    private val manager = context.getSystemService(DevicePolicyManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val launcher = context.getSystemService(LauncherApps::class.java)

    fun myUserId(): Int = userId(Process.myUserHandle())

    fun myUid(): Int = Process.myUid()

    fun dataDir(): String = context.applicationInfo.dataDir ?: ""

    fun isProfileOwner(): Boolean = manager.isProfileOwnerApp(context.packageName)

    /**
     * Other Android users this app can see. Samsung One UI often hides the work
     * profile from {@code getUserProfiles()} for a normal app, so this also asks
     * CrossProfileApps, LauncherApps, and the hidden profile list, then the id
     * saved when provisioning finished.
     */
    fun otherProfiles(): List<UserHandle> {
        val mine = myUserId()
        val found = LinkedHashMap<Int, UserHandle>()
        fun add(handle: UserHandle?) {
            if (handle == null) return
            val id = userId(handle)
            if (id >= 0 && id != mine) found.putIfAbsent(id, handle)
        }
        try {
            users.userProfiles.forEach { add(it) }
        } catch (error: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                context.getSystemService(CrossProfileApps::class.java)
                    ?.targetUserProfiles
                    ?.forEach { add(it) }
            } catch (error: Throwable) {
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                launcher.profiles.forEach { add(it) }
            } catch (error: Throwable) {
            }
        }
        hiddenProfiles().forEach { add(it) }
        add(profileOwnerByUserId())
        val remembered = ManagedProfileStore.androidUserId(context)
        if (remembered >= 0 && remembered != mine && !found.containsKey(remembered)) {
            add(userHandleFor(remembered))
        }
        return found.values.toList()
    }

    fun blockReason(): String? {
        val allowed = provisioningAllowed()
        val resolved = try {
            context.packageManager.resolveActivity(
                provisionIntent(),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (error: Throwable) {
            false
        }
        val owned = isProfileOwner() || otherProfiles().isNotEmpty()
        return ManagedProfilePolicy.blockReason(
            allowed,
            isProfileOwner(),
            secureFolderInstalled(),
            resolved,
            owned
        )
    }

    fun provisioningAllowed(): Boolean {
        return try {
            manager.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        } catch (error: Throwable) {
            false
        }
    }

    fun setupActivityResolved(): Boolean {
        return try {
            context.packageManager.resolveActivity(
                provisionIntent(),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (error: Throwable) {
            false
        }
    }

    /**
     * Must be launched with a result contract. ManagedProvisioning reads
     * {@code getCallingPackage()} and shows "Can't set up device" when that is null.
     * The component-name extra is required. The deprecated package-name extra is omitted.
     * Skip-encryption is the flag Samsung One UI needs on an already-encrypted phone.
     * Offline is set on API 33+ so a role-holder download is not required to continue.
     */
    fun provisionIntent(): Intent {
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
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
        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true)
        }
        return intent
    }

    fun launchStatusIn(user: UserHandle): Boolean = launch(user, ProfileStatusActivity::class.java.name)

    fun launchInstallerIn(user: UserHandle): Boolean = launch(user, InstallInProfileActivity::class.java.name)

    enum class ProfileOpen {
        STARTED,
        NEED_CONSENT,
        FAILED
    }

    fun consentIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            context.getSystemService(CrossProfileApps::class.java)
                ?.createRequestInteractAcrossProfilesIntent()
        } catch (error: Throwable) {
            null
        }
    }

    /** Wake the work profile and start Blackbox there so finalization can run. */
    fun openBlackbox(user: UserHandle, source: Activity): ProfileOpen {
        return openComponent(user, source, WelcomeActivity::class.java, preferMain = true)
    }

    fun openComponent(user: UserHandle, source: Activity, target: Class<*>, preferMain: Boolean): ProfileOpen {
        wake(user)
        val cross = if (Build.VERSION.SDK_INT >= 28) {
            context.getSystemService(CrossProfileApps::class.java)
        } else {
            null
        }
        if (startAcrossProfiles(cross, user, source, target, preferMain)) return ProfileOpen.STARTED
        if (launch(user, target.name)) return ProfileOpen.STARTED
        if (preferMain && launchPackage(user, context.packageName)) return ProfileOpen.STARTED
        if (needsConsent(cross)) return ProfileOpen.NEED_CONSENT
        return ProfileOpen.FAILED
    }

    private fun wake(user: UserHandle) {
        try {
            users.requestQuietModeEnabled(false, user)
        } catch (error: Throwable) {
        }
    }

    private fun needsConsent(cross: CrossProfileApps?): Boolean {
        if (cross == null || Build.VERSION.SDK_INT < 30) return false
        return try {
            !cross.canInteractAcrossProfiles() && cross.canRequestInteractAcrossProfiles()
        } catch (error: Throwable) {
            false
        }
    }

    private fun startAcrossProfiles(
        cross: CrossProfileApps?,
        user: UserHandle,
        source: Activity,
        target: Class<*>,
        preferMain: Boolean
    ): Boolean {
        if (cross == null) return false
        val component = ComponentName(context, target)
        if (preferMain) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    cross.startMainActivity(component, user, source, null)
                    return true
                } catch (error: Throwable) {
                }
            }
            try {
                cross.startMainActivity(component, user)
                return true
            } catch (error: Throwable) {
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                cross.startActivity(Intent(context, target), user, source, null)
                return true
            } catch (error: Throwable) {
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                cross.startActivity(Intent(context, target), user, source)
                return true
            } catch (error: Throwable) {
            }
        }
        return false
    }

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

    private fun userHandleFor(userId: Int): UserHandle? {
        return try {
            val of = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            of.invoke(null, userId) as UserHandle
        } catch (error: Throwable) {
            try {
                val ctor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                ctor.isAccessible = true
                ctor.newInstance(userId) as UserHandle
            } catch (fallback: Throwable) {
                null
            }
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

    private fun secureFolderInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ProfileStatusCopy.SECURE_FOLDER_PACKAGE, 0)
            true
        } catch (error: Throwable) {
            false
        }
    }

    /** Hidden API. Used when list APIs omit a profile this app already owns. */
    private fun profileOwnerByUserId(): UserHandle? {
        val method = try {
            DevicePolicyManager::class.java.getMethod(
                "getProfileOwnerAsUser",
                Int::class.javaPrimitiveType
            )
        } catch (error: Throwable) {
            return null
        }
        val ours = BlackBoxDeviceAdminReceiver.component(context)
        for (id in 10..30) {
            try {
                val owner = method.invoke(manager, id) as? android.content.ComponentName ?: continue
                if (owner.packageName == ours.packageName && owner.className == ours.className) {
                    return userHandleFor(id)
                }
            } catch (error: Throwable) {
                return null
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun hiddenProfiles(): List<UserHandle> {
        return try {
            val method = UserManager::class.java.getMethod("getAllProfiles")
            val list = method.invoke(users) as? List<*> ?: return emptyList()
            list.mapNotNull { info ->
                if (info == null) return@mapNotNull null
                val id = info.javaClass.getField("id").getInt(info)
                if (id < 0) null else userHandleFor(id)
            }
        } catch (error: Throwable) {
            emptyList()
        }
    }
}
