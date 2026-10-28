package top.niunaijun.blackboxa.profile

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Profile owner for the separate Android user. It does not become device owner. */
class BlackBoxDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val admin = ComponentName(context, BlackBoxDeviceAdminReceiver::class.java)
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        try {
            manager.setProfileName(admin, "Blackbox account B")
        } catch (error: Throwable) {
            Log.w(TAG, "profile name was not set: ${error.javaClass.simpleName}")
        }
        try {
            manager.setProfileEnabled(admin)
        } catch (error: Throwable) {
            Log.e(TAG, "profile was not enabled", error)
            return
        }
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
    }
}
