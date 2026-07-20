package top.niunaijun.blackboxa.view.auth

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.cloud.DriveFolderStore
import top.niunaijun.blackboxa.cloud.Supabase
import top.niunaijun.blackboxa.cloud.VaultKeyStore

class AccountSettingsActivity : AppCompatActivity() {
    private lateinit var connectionStatus: TextView
    private lateinit var connectionButton: Button

    companion object {
        const val EXTRA_ACTION = "account_action"
        const val ACTION_BACKUP = "backup"
        const val ACTION_RESTORE = "restore"
        const val ACTION_DRIVE = "drive"
        const val ACTION_LOGOUT = "logout"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Supabase.isSignedIn(this) || !VaultKeyStore.isReady(this)) {
            startActivity(Intent(this, AuthActivity::class.java)); finish(); return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(TextView(this).apply {
            text = "Settings / Account & Backup"; textSize = 25f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Signed-in email\n${Supabase.email(this@AccountSettingsActivity) ?: "Unknown"}\n\n" +
                "Account type\nEmail code (passwordless)\n\nGoogle Drive\n" +
                if (DriveFolderStore.isConnected(this@AccountSettingsActivity)) "Connected" else "Not connected"
            textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, gap, 0, gap)
        })
        root.addView(TextView(this).apply {
            text = "ShieldProxy connection"
            textSize = 19f; setTextColor(Color.WHITE); setPadding(0, gap, 0, 0)
        })
        connectionStatus = TextView(this).apply {
            text = "Checking signed connection..."
            textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, gap, 0, 0)
        }
        root.addView(connectionStatus)
        connectionButton = actionButton("Connect / verify ShieldProxy") { verifyShieldConnection() }
        root.addView(connectionButton)
        root.addView(actionButton("Select / change Google Drive folder") { emit(ACTION_DRIVE) })
        root.addView(actionButton("Back up all users and clones") { emit(ACTION_BACKUP) })
        root.addView(actionButton("Restore all users and clones") { emit(ACTION_RESTORE) })
        root.addView(TextView(this).apply {
            text = "Sign-in\nPasswordless — a 6-digit code is emailed to you each time you log in. " +
                "Nothing to remember or reset."
            textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, pad, 0, gap)
        })
        root.addView(actionButton("Log out") { emit(ACTION_LOGOUT) })
        root.addView(actionButton("Back") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
        verifyShieldConnection()
    }

    private fun verifyShieldConnection() {
        connectionButton.isEnabled = false
        connectionStatus.setTextColor(Color.LTGRAY)
        connectionStatus.text = "Checking signed connection..."
        Thread {
            var version = ""
            val message = try {
                val result = contentResolver.call(
                    Uri.parse("content://com.privacyshield.proxy.bridge"), "status", null, null)
                val ready = result?.getBoolean("ok") == true
                val remoteOwner = result?.getString("ownerHash").orEmpty()
                val localOwner = VaultKeyStore.ownerHash(this).orEmpty()
                version = result?.getString("versionName").orEmpty()
                when {
                    result == null -> "ShieldProxy is not reachable."
                    !ready -> "ShieldProxy is locked. Sign in there, then verify again."
                    localOwner.isBlank() || remoteOwner != localOwner ->
                        "The apps are signed in to different accounts."
                    else -> null
                }
            } catch (_: SecurityException) {
                "ShieldProxy is not signed by the trusted key."
            } catch (_: Exception) {
                "ShieldProxy is not installed or not reachable."
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                connectionButton.isEnabled = true
                if (message == null) {
                    connectionStatus.setTextColor(Color.parseColor("#69F0AE"))
                    val suffix = version.takeIf { it.isNotBlank() }?.let { " v$it" }.orEmpty()
                    connectionStatus.text = "Connected successfully to ShieldProxy$suffix\n" +
                        "Trusted signature • Same account • Ready"
                } else {
                    connectionStatus.setTextColor(Color.parseColor("#FF8A80"))
                    connectionStatus.text = "Not connected\n$message"
                }
            }
        }.start()
    }

    private fun actionButton(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setOnClickListener(action)
        val gap = (6 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap }
        gravity = Gravity.CENTER
    }

    private fun emit(action: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish()
    }
}
