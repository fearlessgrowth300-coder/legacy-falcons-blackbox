package top.niunaijun.blackboxa.profile

import android.app.Activity
import android.os.Bundle

/**
 * Android 8+ delivers this inside the new profile at the same time as
 * ACTION_PROFILE_PROVISIONING_COMPLETE. It has to return so provisioning can finish,
 * and it is the reliable place to enable Play Store and the other launcher apps.
 */
class ProvisioningSuccessfulActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManagedProfileFinalizer.finalizeIfOwner(this)
        setResult(RESULT_OK)
        finish()
    }
}
