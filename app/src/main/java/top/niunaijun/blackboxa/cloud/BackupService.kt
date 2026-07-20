package top.niunaijun.blackboxa.cloud

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.WelcomeActivity
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground, wake-locked execution for multi-gigabyte encrypted container backups. */
class BackupService : Service() {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            worker?.interrupt()
            return START_NOT_STICKY
        }
        val operation = intent?.action ?: return START_NOT_STICKY
        if (operation !in setOf(ACTION_BACKUP, ACTION_RESTORE) || !running.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }
        createChannel()
        val label = if (operation == ACTION_BACKUP) "Preparing encrypted backup" else "Preparing encrypted restore"
        startForeground(NOTIFICATION_ID, notification(label, 0L, true).build())
        saveState(operation, "running", label, 0L)
        worker = Thread {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:backup")
            var restartAfterRestore = false
            lock.acquire(6 * 60 * 60 * 1000L)
            try {
                if (operation == ACTION_BACKUP) {
                    var copied = 0L
                    CloudSync.push(this) { delta ->
                        checkCancellation()
                        copied += delta
                        updateProgress(operation, "Backing up", copied)
                    }
                    verifyUploadedBackup(operation)
                    finishJob(operation, true, "Encrypted BlackBox backup uploaded and verified")
                } else {
                    val restored = CloudSync.restore(this) { total ->
                        checkCancellation()
                        updateProgress(operation, "Restoring", total)
                    }
                    finishJob(operation, restored,
                        if (restored) "Encrypted BlackBox restore verified" else "No backup was found")
                    restartAfterRestore = restored
                }
            } catch (e: InterruptedException) {
                finishJob(operation, false, "Backup operation cancelled safely")
            } catch (e: Exception) {
                finishJob(operation, false, e.message?.take(160) ?: "Backup operation failed")
            } finally {
                if (lock.isHeld) lock.release()
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                if (restartAfterRestore) scheduleRestart()
            }
        }.apply { name = "BlackBoxBackup"; start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    /**
     * Drive's DocumentsProvider can return from close() before a newly uploaded directory and
     * its children are visible through a fresh listFiles()/openInputStream() query.  Treat that
     * short propagation window as "not ready" instead of declaring a multi-gigabyte backup
     * corrupt.  Authentication/integrity failures remain terminal and are never retried.
     */
    private fun verifyUploadedBackup(operation: String) {
        val deadline = SystemClock.elapsedRealtime() + DRIVE_VISIBILITY_TIMEOUT_MS
        var lastTransient: Exception? = null
        while (true) {
            checkCancellation()
            try {
                val visible = CloudSync.verifyLatest(this) { total ->
                    checkCancellation()
                    updateProgress(operation, "Verifying", total)
                }
                if (visible) return
            } catch (error: Exception) {
                if (!isTransientDriveVisibilityError(error)) throw error
                lastTransient = error
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw lastTransient ?: IllegalStateException(
                    "The uploaded backup did not become readable in Google Drive"
                )
            }
            updateProgress(operation, "Waiting for Google Drive", lastNotified)
            Thread.sleep(DRIVE_RETRY_DELAY_MS)
        }
    }

    private fun isTransientDriveVisibilityError(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        if (message.contains("integrity check failed") ||
            message.contains("invalid backup") ||
            message.contains("unsupported or mismatched") ||
            message.contains("out of order") ||
            message.contains("unsafe backup")) return false
        return error is java.io.IOException ||
            message.contains("missing one or more parts") ||
            message.contains("not found") ||
            message.contains("no such file")
    }

    private fun updateProgress(operation: String, phase: String, bytes: Long) {
        saveState(operation, "running", phase, bytes)
        if (bytes - lastNotified >= 64L * 1024 * 1024) {
            lastNotified = bytes
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, notification(phase, bytes, true).build())
        }
    }

    private fun finishJob(operation: String, ok: Boolean, message: String) {
        saveState(operation, if (ok) "success" else "failed", message, lastNotified, durable = true)
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            notification(message, 0L, false).setOngoing(false).build()
        )
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName)
            .putExtra("ok", ok).putExtra("message", message))
    }

    private fun notification(text: String, bytes: Long, active: Boolean): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(this, 9400, Intent(this, WelcomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (active) "BlackBox backup" else "BlackBox backup result")
            .setContentText(if (bytes > 0) "$text - ${bytes / 1024 / 1024} MB" else text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
        if (active) {
            val cancel = PendingIntent.getService(this, 9401,
                Intent(this, BackupService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setProgress(0, 0, true).addAction(0, "Cancel", cancel)
        }
        return builder
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Encrypted backup", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun saveState(operation: String, status: String, message: String, bytes: Long,
        durable: Boolean = false) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("operation", operation).putString("status", status)
            .putString("message", message).putLong("bytes", bytes)
            .putLong("updatedAt", System.currentTimeMillis())
        if (durable) editor.commit() else editor.apply()
    }

    private fun scheduleRestart() {
        val restart = Intent(this, WelcomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(this, 9402, restart,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java)?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2_500, pending)
        // stopSelf() is asynchronous. Killing immediately leaves an executing foreground-service
        // record in ActivityManager, which rejects the next backup until Android is rebooted.
        // Give the main thread time to complete onDestroy before replacing this process.
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1_000)
    }

    companion object {
        const val ACTION_FINISHED = "top.niunaijun.blackbox.BACKUP_FINISHED"
        private const val ACTION_BACKUP = "top.niunaijun.blackbox.BACKUP"
        private const val ACTION_RESTORE = "top.niunaijun.blackbox.RESTORE"
        private const val ACTION_CANCEL = "top.niunaijun.blackbox.CANCEL_BACKUP"
        private const val CHANNEL_ID = "blackbox_backup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val PREFS = "backup_job_state"
        private const val DRIVE_VISIBILITY_TIMEOUT_MS = 5 * 60 * 1000L
        private const val DRIVE_RETRY_DELAY_MS = 3_000L

        fun startBackup(ctx: Context) = start(ctx, ACTION_BACKUP)
        fun startRestore(ctx: Context) = start(ctx, ACTION_RESTORE)
        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, BackupService::class.java).setAction(ACTION_CANCEL))
        }
        private fun start(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, BackupService::class.java).setAction(action))
        }
    }
}
