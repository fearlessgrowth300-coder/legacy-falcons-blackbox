package top.niunaijun.blackboxa.profile

import android.app.Activity
import android.os.Bundle

/**
 * Android 12+ starts this inside the new profile and waits for {@code RESULT_OK}.
 * Returning without a result aborts provisioning with the IT-admin dialog.
 */
class AdminPolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlackBoxDeviceAdminReceiver.enableProfile(this)
        setResult(RESULT_OK)
        finish()
    }
}
