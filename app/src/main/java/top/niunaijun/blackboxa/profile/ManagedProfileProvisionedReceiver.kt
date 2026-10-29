package top.niunaijun.blackboxa.profile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sent to the main user when managed-profile setup finishes.
 * The user id in the intent is what user 0 can remember if profile queries are empty.
 */
class ManagedProfileProvisionedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ManagedProfileStore.capture(context, intent)
        ManagedProfileStore.commitPendingWorkspace(context)
    }
}
