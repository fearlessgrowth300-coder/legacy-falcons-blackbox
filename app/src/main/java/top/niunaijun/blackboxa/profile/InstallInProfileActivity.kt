package top.niunaijun.blackboxa.profile

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Runs inside the managed profile. The profile owner copies an already-installed
 * package into this Android user with installExistingPackage.
 */
class InstallInProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ManagedProfileController(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val ink = ProfileStatusActivity.readableTextColor(this)
        val background = androidx.core.content.ContextCompat.getColor(this, top.niunaijun.blackboxa.R.color.blackbox_background)
        column.setBackgroundColor(background)
        if (!controller.isProfileOwner()) {
            column.addView(TextView(this).apply {
                setTextColor(ink)
                text = "This installer only works inside the separate Android profile, where " +
                    "Blackbox is the profile owner. From the main profile, use Profile separation " +
                    "and open the installer on the other user."
            })
            setContentView(ScrollView(this).apply {
                setBackgroundColor(background)
                addView(column)
            })
            return
        }
        column.addView(TextView(this).apply {
            setTextColor(ink)
            text = "Android user ${controller.myUserId()}, uid ${controller.myUid()}. " +
                "Each app you install here gets this user's Linux identity, not the main profile's. " +
                "ShieldProxy must also be installed here; a VPN in the main profile does not cover this user."
        })
        listOf(
            "com.google.android.gms" to "Google Play services",
            "com.privacyshield.proxy" to "ShieldProxy"
        ).forEach { (packageName, label) ->
            column.addView(Button(this).apply {
                text = "Enable $label in this profile"
                setOnClickListener {
                    val ok = controller.installExisting(packageName)
                    Toast.makeText(
                        this@InstallInProfileActivity,
                        if (ok) "$label is enabled here" else "$label is not on this phone or Android refused",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
        val packages = packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        if (packages.isEmpty()) {
            column.addView(TextView(this).apply {
                setTextColor(ink)
                text = "No user apps are visible to copy."
            })
        }
        packages.take(80).forEach { info ->
            val label = packageManager.getApplicationLabel(info).toString()
            column.addView(Button(this).apply {
                text = "Install $label"
                setOnClickListener {
                    val ok = controller.installExisting(info.packageName)
                    Toast.makeText(
                        this@InstallInProfileActivity,
                        if (ok) "$label is in this profile" else "Android did not copy $label",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(background)
            addView(column)
        })
    }
}
