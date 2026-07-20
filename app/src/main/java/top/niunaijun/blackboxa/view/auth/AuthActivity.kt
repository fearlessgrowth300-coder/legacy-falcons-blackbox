package top.niunaijun.blackboxa.view.auth

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.cloud.Supabase
import top.niunaijun.blackboxa.cloud.VaultKeyStore
import top.niunaijun.blackboxa.cloud.OtpGuard
import top.niunaijun.blackboxa.view.main.WelcomeActivity

/**
 * Passwordless sign-in gate: enter your email → get a 6-digit code → enter it → you're in.
 * The same flow creates the account on first use. One account covers BlackBox and ShieldProxy.
 */
class AuthActivity : AppCompatActivity() {

    private var codeSent = false
    private lateinit var subtitle: TextView
    private lateinit var emailField: EditText
    private lateinit var codeField: EditText
    private lateinit var primary: Button
    private lateinit var resend: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val otpGuard = OtpGuard()
    private var sentEmail: String? = null
    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(TextView(this).apply {
            text = "BlackBox"; textSize = 28f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        })
        subtitle = TextView(this).apply {
            text = "Log in or sign up with your email"; textSize = 17f
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 8, 0, pad)
        }
        root.addView(subtitle)
        emailField = EditText(this).apply {
            hint = "Email"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        root.addView(emailField)
        codeField = EditText(this).apply {
            hint = "6-digit code"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER; visibility = View.GONE
        }
        root.addView(codeField)
        primary = Button(this).apply {
            text = "Send code"; isAllCaps = false
            setOnClickListener { onPrimary() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad }
        }
        root.addView(primary)
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER; topMargin = pad }
        }
        root.addView(progress)
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6B6B")); gravity = Gravity.CENTER; setPadding(0, 16, 0, 0)
        }
        root.addView(status)
        resend = TextView(this).apply {
            text = "Resend code"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0); visibility = View.GONE
            setOnClickListener { sendCode() }
        }
        root.addView(resend)

        setContentView(ScrollView(this).apply { addView(root) })
        migrateExistingSession()
    }

    private fun migrateExistingSession() {
        if (!Supabase.hasStoredSession(this)) return
        // Already unlocked on this device → straight in, no network (works offline). The session
        // never expires; only an explicit Log out ends it.
        if (VaultKeyStore.isReady(this)) { openMain(); return }
        // Stored session but the local key isn't set up yet: fetch it. NEVER log out on failure.
        setBusy(true)
        status.setTextColor(Color.LTGRAY)
        status.text = "Preparing your account..."
        Thread {
            try {
                val email = Supabase.email(this) ?: error("Account email is missing")
                VaultKeyStore.provision(this, email, Supabase.getOrCreateBackupKey(this))
                runOnUiThread { openMain() }
            } catch (_: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.setTextColor(Color.parseColor("#FFB74D"))
                    status.text = "Couldn't reach the server. Check your connection, then tap Continue."
                    primary.text = "Continue"
                    primary.setOnClickListener { migrateExistingSession() }
                }
            }
        }.start()
    }

    private fun onPrimary() = if (!codeSent) sendCode() else verifyCode()

    private fun sendCode() {
        val email = emailField.text.toString().trim()
        if (email.isBlank() || !email.contains("@")) { showError("Enter a valid email."); return }
        if (!otpGuard.canSend()) {
            showError("Please wait ${otpGuard.resendSeconds()} seconds before requesting another code.")
            return
        }
        setBusy(true)
        Thread {
            try {
                Supabase.requestEmailCode(this, email)
                runOnUiThread {
                    setBusy(false)
                    otpGuard.markSent()
                    codeSent = true
                    sentEmail = email
                    emailField.isEnabled = false
                    codeField.visibility = View.VISIBLE
                    codeField.requestFocus()
                    primary.text = "Verify & continue"
                    resend.visibility = View.VISIBLE
                    startResendCooldown()
                    status.setTextColor(Color.parseColor("#69F0AE"))
                    status.text = "We emailed a 6-digit code to $email. Enter it above."
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); showError(e.message ?: "Could not send the code.") }
            }
        }.start()
    }

    private fun verifyCode() {
        val email = sentEmail ?: emailField.text.toString().trim()
        val code = codeField.text.toString().trim()
        if (!otpGuard.validCode(code)) { showError("Enter exactly 6 digits."); return }
        if (otpGuard.isLocked()) { showError("Too many failed attempts. Request a new code."); return }
        setBusy(true)
        Thread {
            try {
                Supabase.verifyEmailCode(this, email, code)
                VaultKeyStore.provision(this, email, Supabase.getOrCreateBackupKey(this))
                runOnUiThread { openMain() }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    val left = otpGuard.recordVerifyFailure()
                    if (left == 0) {
                        codeSent = false
                        sentEmail = null
                        emailField.isEnabled = true
                        codeField.visibility = View.GONE
                        primary.text = "Send new code"
                        showError("Too many failed attempts. Wait for the resend timer, then request a new code.")
                    } else showError("${e.message ?: "Could not verify the code."} $left attempt(s) left.")
                }
            }
        }.start()
    }

    private fun showError(m: String) {
        status.setTextColor(Color.parseColor("#FF6B6B")); status.text = m
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        primary.isEnabled = !busy
        resend.isEnabled = !busy && otpGuard.canSend()
    }

    private fun startResendCooldown() {
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(OtpGuard.RESEND_COOLDOWN_MS, 1_000L) {
            override fun onTick(ms: Long) {
                resend.isEnabled = false
                resend.text = "Resend code in ${(ms + 999L) / 1_000L}s"
            }
            override fun onFinish() { resend.text = "Resend code"; resend.isEnabled = true }
        }.start()
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }

    private fun openMain() {
        startActivity(Intent(this, WelcomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
