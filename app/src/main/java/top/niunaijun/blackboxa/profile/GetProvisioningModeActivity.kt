package top.niunaijun.blackboxa.profile

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import top.niunaijun.blackbox.core.ProvisioningModeChoice

/**
 * Android 12+ requires this activity before a managed profile can be created.
 * ManagedProvisioning starts it with {@code startActivityForResult} and rejects
 * the DPC when it does not resolve.
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = intent?.getIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
        )
        val mode = ProvisioningModeChoice.choose(allowed)
        if (mode == ProvisioningModeChoice.NONE) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent()
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
        adminExtras(intent)?.let { extras ->
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, extras)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun adminExtras(source: Intent?): PersistableBundle? {
        return source?.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
    }
}
