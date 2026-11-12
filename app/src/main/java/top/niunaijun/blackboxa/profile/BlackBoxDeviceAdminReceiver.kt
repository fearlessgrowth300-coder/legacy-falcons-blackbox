package top.niunaijun.blackboxa.profile

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Profile owner for the separate Android user. It does not become device owner. */
class BlackBoxDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        enableProfile(context)
        try {
            context.startActivity(Intent(context, ProfileStatusActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Throwable) {
            Log.w(TAG, "status screen did not open: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "BlackBoxProfileOwner"
        fun component(context: Context) = ComponentName(context, BlackBoxDeviceAdminReceiver::class.java)

        /** Name the profile, enable it, and turn on the launcher apps. Safe to repeat. */
        fun enableProfile(context: Context) {
            ManagedProfileFinalizer.finalizeIfOwner(context)
        }
    }
}
