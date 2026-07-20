package top.niunaijun.blackboxa.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import top.niunaijun.blackbox.core.GuestKeepAlive
import top.niunaijun.blackbox.core.system.DaemonService

/** Restores the container daemon and opted-in GMS watchdog after a normal device reboot. */
class KeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { GuestKeepAlive.start() }
        runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, DaemonService::class.java))
        }
    }
}
