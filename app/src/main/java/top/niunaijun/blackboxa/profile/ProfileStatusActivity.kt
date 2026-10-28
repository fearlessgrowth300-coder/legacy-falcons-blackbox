package top.niunaijun.blackboxa.profile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.ManagedProfilePolicy

/** Shows this process's Android user and Linux uid, and can open the other profile's copy. */
class ProfileStatusActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ManagedProfileController(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text(
            "This side\n" +
                "Android user id: ${controller.myUserId()}\n" +
                "Linux uid: ${controller.myUid()}\n" +
                "Data directory: ${controller.dataDir()}\n" +
                "Profile owner here: ${controller.isProfileOwner()}"
        ))
        val others = controller.otherProfiles()
        if (others.isEmpty()) {
            column.addView(text(
                "No other Android profile is visible. A separate profile is a real Android user " +
                    "(usually user 10 or higher) with its own uid. The hooked workspaces in this " +
                    "app still share this uid."
            ))
            val reason = controller.blockReason()
            if (reason != null) column.addView(text(reason))
        } else {
            others.forEach { user ->
                val otherId = controller.userIdOf(user)
                column.addView(text(
                    "Other Android user id: $otherId\n" +
                        "Open that side to see its uid. It must differ from ${controller.myUid()}."
                ))
                column.addView(button("Open status on user $otherId") {
                    if (!controller.launchStatusIn(user)) {
                        toast("Could not open the other profile. Enable cross-profile apps for Blackbox in system settings.")
                    }
                })
                column.addView(button("Install an app into user $otherId") {
                    if (!controller.launchInstallerIn(user)) {
                        toast("Could not open the installer in the other profile.")
                    }
                })
            }
        }
        column.addView(text("Workspaces"))
        BlackBoxCore.get().users.sortedBy { it.id }.forEach { user ->
            val mode = WorkspaceModeStore.mode(this, user.id)
            column.addView(text(
                "Workspace ${user.id}: ${ManagedProfilePolicy.shortLabel(mode)}" +
                    if (mode == ManagedProfilePolicy.Mode.SEPARATE) {
                        "\nRecommended. Apps launch in the separate Android profile, not in this process."
                    } else {
                        "\nWeaker. Apps run in this process and share its Linux uid."
                    }
            ))
        }
        column.addView(text(
            "ShieldProxy's VPN belongs to one Android user. Install and run ShieldProxy inside " +
                "this profile as well, or this side's traffic leaves through the phone's own network."
        ))
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        setPadding(0, 24, 0, 24)
    }

    private fun button(label: String, action: () -> Unit) = android.widget.Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
