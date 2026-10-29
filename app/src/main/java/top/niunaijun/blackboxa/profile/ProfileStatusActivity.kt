package top.niunaijun.blackboxa.profile

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.ProfileStatusCopy
import top.niunaijun.blackbox.core.ProfileWorkApps
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackboxa.R

/** Shows this process's Android user and Linux uid, and can open the other profile's copy. */
class ProfileStatusActivity : AppCompatActivity() {
    private var pendingUser: UserHandle? = null
    private var pendingTarget: Class<*> = top.niunaijun.blackboxa.view.main.WelcomeActivity::class.java
    private var pendingMain: Boolean = true

    private val acrossProfilesConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val user = pendingUser ?: return@registerForActivityResult
            deliver(user, pendingTarget, pendingMain)
        }

    private val provisioned = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ManagedProfileStore.capture(context, intent)
            ManagedProfileStore.commitPendingWorkspace(context)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(android.app.admin.DevicePolicyManager.ACTION_MANAGED_PROFILE_PROVISIONED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(provisioned, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(provisioned, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(provisioned)
        } catch (error: Throwable) {
        }
    }

    private fun render() {
        val controller = ManagedProfileController(this)
        val ink = readableTextColor(this)
        val background = ContextCompat.getColor(this, R.color.blackbox_background)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(background)
        }
        column.addView(text(
            "This side\n" +
                "Android user id: ${controller.myUserId()}\n" +
                "Linux uid: ${controller.myUid()}\n" +
                "Data directory: ${controller.dataDir()}\n" +
                "Profile owner here: ${controller.isProfileOwner()}",
            ink
        ))

        val others = controller.otherProfiles()
        val otherIds = others.map { controller.userIdOf(it) }.filter { it >= 0 }.toIntArray()
        val remembered = ManagedProfileStore.androidUserId(this)
        val managedId = ProfileStatusCopy.managedUserId(
            controller.isProfileOwner(),
            controller.myUserId(),
            otherIds,
            remembered
        )
        if (managedId >= 0 && managedId != controller.myUserId() && remembered < 0) {
            ManagedProfileStore.rememberAndroidUser(this, managedId)
        }
        val notice = ProfileStatusCopy.notice(
            controller.isProfileOwner(),
            controller.myUserId(),
            otherIds,
            remembered,
            controller.provisioningAllowed(),
            secureFolderInstalled(),
            controller.setupActivityResolved()
        )
        when (notice) {
            ProfileStatusCopy.Notice.ACTIVE -> {
                column.addView(text(
                    ProfileStatusCopy.activeMessage(managedId, controller.isProfileOwner()),
                    ink
                ))
                column.addView(text(
                    "If the Work tab is empty, open Blackbox in the work profile once. " +
                        "That enables Play Store, the browser, Files, and Settings there. " +
                        "Install ShieldProxy from Play Store or the installer. A VPN belongs to one Android user.",
                    ink
                ))
                if (controller.isProfileOwner()) {
                    column.addView(button("Install apps and ShieldProxy") {
                        startActivity(Intent(this, InstallInProfileActivity::class.java))
                    })
                    column.addView(button("Remove profile and set up again") {
                        confirmRemove(null)
                    })
                } else {
                    val user = others.firstOrNull { controller.userIdOf(it) == managedId }
                        ?: others.firstOrNull()
                    if (user != null) {
                        column.addView(button("Open Blackbox in the work profile") {
                            deliver(user, top.niunaijun.blackboxa.view.main.WelcomeActivity::class.java, true)
                        })
                        column.addView(button("Install or open ShieldProxy in the work profile") {
                            deliver(user, InstallInProfileActivity::class.java, false)
                        })
                        column.addView(button("Remove profile and set up again") {
                            confirmRemove(user)
                        })
                    } else {
                        column.addView(text(
                            "Android user $managedId is recorded, but this app cannot switch to it yet.",
                            ink
                        ))
                        column.addView(text(ProfileWorkApps.removeByHandMessage(), ink))
                    }
                }
            }
            ProfileStatusCopy.Notice.EMPTY -> column.addView(text(ProfileStatusCopy.emptyMessage(), ink))
            else -> {
                column.addView(text(ProfileStatusCopy.emptyMessage(), ink))
                val reason = controller.blockReason()
                if (reason != null) column.addView(text(reason, ink))
            }
        }

        val bound = WorkspaceModeStore.boundWorkspaceId(this)
        column.addView(text(
            "Workspaces\n" +
                "These are Blackbox virtual users stored in this Android user. " +
                "Only the one bound to the work profile is separate. The others stay hooked.",
            ink
        ))
        realWorkspaces().forEach { id ->
            column.addView(text(ProfileStatusCopy.workspaceLine(id, bound), ink))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(background)
            addView(column)
        })
    }

    private fun realWorkspaces(): List<Int> {
        return try {
            val ids = BlackBoxCore.get().users.map { it.id }.toIntArray()
            val real = ProfileStatusCopy.realWorkspaceIds(ids)
            val withDirs = real.filter { id ->
                try {
                    BEnvironment.getUserDir(id).isDirectory
                } catch (error: Throwable) {
                    false
                }
            }
            (if (withDirs.isEmpty()) real.toList() else withDirs).sorted()
        } catch (error: Throwable) {
            emptyList()
        }
    }

    private fun secureFolderInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(ProfileStatusCopy.SECURE_FOLDER_PACKAGE, 0)
            true
        } catch (error: Throwable) {
            false
        }
    }

    private fun text(value: String, color: Int) = TextView(this).apply {
        text = value
        setTextColor(color)
        setPadding(0, 24, 0, 24)
    }

    private fun button(label: String, action: () -> Unit) = android.widget.Button(this).apply {
        text = label
        setTextColor(ContextCompat.getColor(this@ProfileStatusActivity, R.color.blackbox_ink))
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this@ProfileStatusActivity, R.color.blackbox_text)
        )
        setOnClickListener { action() }
    }

    private fun deliver(user: UserHandle, target: Class<*>, preferMain: Boolean) {
        val controller = ManagedProfileController(this)
        when (controller.openComponent(user, this, target, preferMain)) {
            ManagedProfileController.ProfileOpen.STARTED -> toast(
                "Opening the work profile. After Blackbox loads there, check the Work tab for Play Store."
            )
            ManagedProfileController.ProfileOpen.NEED_CONSENT -> {
                val consent = controller.consentIntent()
                if (consent == null) {
                    toast(ProfileWorkApps.removeByHandMessage())
                    return
                }
                pendingUser = user
                pendingTarget = target
                pendingMain = preferMain
                acrossProfilesConsent.launch(consent)
            }
            ManagedProfileController.ProfileOpen.FAILED -> {
                if (target == InstallInProfileActivity::class.java) {
                    deliver(user, top.niunaijun.blackboxa.view.main.WelcomeActivity::class.java, true)
                } else {
                    toast(ProfileWorkApps.removeByHandMessage())
                }
            }
        }
    }

    private fun confirmRemove(user: UserHandle?) {
        AlertDialog.Builder(this)
            .setTitle("Remove the work profile?")
            .setMessage(
                "This deletes Android user ${user?.let { ManagedProfileController(this).userIdOf(it) } ?: "the work profile"} " +
                    "and the apps inside it. Blackbox on the personal side stays."
            )
            .setPositiveButton("Remove") { _, _ ->
                if (ManagedProfileController(this).isProfileOwner()) {
                    if (!ManagedProfileFinalizer.wipeIfOwner(this)) {
                        toast("Android did not remove the work profile.")
                    }
                } else if (user != null) {
                    when (ManagedProfileController(this).openComponent(
                        user, this, RemoveManagedProfileActivity::class.java, false
                    )) {
                        ManagedProfileController.ProfileOpen.STARTED ->
                            toast("Confirm the removal inside the work profile.")
                        ManagedProfileController.ProfileOpen.NEED_CONSENT -> {
                            pendingUser = user
                            pendingTarget = RemoveManagedProfileActivity::class.java
                            pendingMain = false
                            val consent = ManagedProfileController(this).consentIntent()
                            if (consent != null) acrossProfilesConsent.launch(consent)
                            else toast(ProfileWorkApps.removeByHandMessage())
                        }
                        ManagedProfileController.ProfileOpen.FAILED ->
                            toast(ProfileWorkApps.removeByHandMessage())
                    }
                } else {
                    toast(ProfileWorkApps.removeByHandMessage())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        fun readableTextColor(activity: Activity): Int {
            val themeText = themeTextColor(activity)
            val background = ContextCompat.getColor(activity, R.color.blackbox_background)
            val fallback = ContextCompat.getColor(activity, R.color.blackbox_text)
            return ProfileStatusCopy.chooseTextColor(themeText, background, fallback)
        }

        private fun themeTextColor(activity: Activity): Int {
            val value = TypedValue()
            val resolved = activity.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
            if (!resolved) return ContextCompat.getColor(activity, R.color.primary_text)
            return if (value.resourceId != 0) {
                ContextCompat.getColor(activity, value.resourceId)
            } else {
                value.data
            }
        }
    }
}
