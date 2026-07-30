package top.niunaijun.blackboxa.cloud

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Small, privacy-safe failure queue for the BlackBox host.
 *
 * It intentionally does not record account e-mail, clone/user names, proxy addresses or
 * credentials. Android 11+ process-exit history lets the host report that a guest process
 * crashed/ANRed without identifying which account was running in that process.
 *
 * One deliberate exception: [reportFailure] carries the cloned app's package name for failures that
 * are specific to an app, because which app failed IS the diagnosis — a launch failure affecting
 * only Instagram has a completely different cause from one affecting every clone, and without the
 * name the report cannot be acted on. It still never says which account or proxy was involved.
 */
object CrashReporter {
    private const val PREFS = "privacy_safe_failure_queue"
    private const val QUEUE = "sealed_queue"
    private const val LAST_EXIT_TS = "last_exit_ts"
    private const val MAX_QUEUED = 30
    private const val THROTTLE_MS = 30 * 60 * 1000L   // one report per failing thing per 30 min
    private val lastReported = HashMap<String, Long>()
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed || !isHostProcess(ctx)) return
        synchronized(this) {
            if (installed) return
            installed = true
            collectHistoricalExits(ctx)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    val trace = StringWriter().also {
                        error.printStackTrace(PrintWriter(it))
                    }.toString()
                    enqueue(ctx, JSONObject()
                        .put("kind", "host_exception")
                        .put("thread", if (thread.name == "main") "main" else "background")
                        .put("type", error.javaClass.name)
                        .put("message", scrub(error.message.orEmpty()))
                        .put("stack", scrub(trace).take(24_000)))
                }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private fun collectHistoricalExits(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(LAST_EXIT_TS, 0L)
        var newest = lastSeen
        val activityManager = ctx.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, 20)
        }.getOrDefault(emptyList())
        exits.asReversed().forEach { exit ->
            newest = maxOf(newest, exit.timestamp)
            if (exit.timestamp <= lastSeen || !isActionableExit(exit.reason)) return@forEach
            enqueue(ctx, JSONObject()
                .put("kind", "process_exit")
                .put("process", if (exit.processName == ctx.packageName) "host" else "guest")
                .put("reason", exit.reason)
                .put("status", exit.status)
                .put("importance", exit.importance)
                .put("description", scrub(exit.description.orEmpty()).take(1_000))
                .put("event_ts", exit.timestamp))
        }
        if (newest > lastSeen) prefs.edit().putLong(LAST_EXIT_TS, newest).apply()
    }

    private fun isActionableExit(reason: Int): Boolean =
        reason == android.app.ApplicationExitInfo.REASON_ANR ||
            reason == android.app.ApplicationExitInfo.REASON_CRASH ||
            reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY

    /**
     * Record a failure that is NOT a crash.
     *
     * A clone that refuses to open throws nothing here — the launch fails, the error is logged, and
     * the user is left looking at a frozen logo. Crash reporting never saw it, which is why a
     * package-info transaction overflowing the binder buffer stayed invisible across every install
     * running Instagram until someone plugged a phone in. Counting outcomes is what makes the next
     * one show up as a number instead of a complaint.
     *
     * [key] scopes the throttle only and is never uploaded.
     */
    fun reportFailure(
        ctx: Context,
        kind: String,
        detail: String,
        key: String = kind,
        extras: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            // Without this a clone that fails to open on every attempt would fill the queue with the
            // same line and push out every other signal.
            val now = System.currentTimeMillis()
            synchronized(lastReported) {
                val previous = lastReported["$kind|$key"]
                if (previous != null && now - previous < THROTTLE_MS) return
                lastReported["$kind|$key"] = now
            }
            val report = JSONObject()
                .put("kind", kind)
                .put("detail", scrub(detail))
            // Free memory is the difference between a clone that starts and one that does not, so
            // carry it: it is what separates "broken build" from "phone was full" in the data.
            memoryFields(ctx).forEach { (k, v) -> report.put(k, v) }
            // Scrub string extras too. Callers pass harmless values today, but nothing stops a
            // future one passing an account label, and this is an upload path.
            extras.forEach { (k, v) ->
                report.put(k, if (v is String) scrub(v) else (v ?: JSONObject.NULL))
            }
            enqueue(ctx, report)
        }
    }

    private fun memoryFields(ctx: Context): Map<String, Any> = runCatching {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            ?: return@runCatching emptyMap<String, Any>()
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        mapOf(
            "ramMb" to (info.totalMem / (1024 * 1024)),
            "freeRamMb" to (info.availMem / (1024 * 1024)),
            "lowMem" to info.lowMemory
        )
    }.getOrDefault(emptyMap())

    private fun enqueue(ctx: Context, report: JSONObject) {
        report.put("ts", System.currentTimeMillis())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
            .put("version", appVersion(ctx))
            .put("variant", runCatching {
                top.niunaijun.blackboxa.BuildConfig.VARIANT_TAG
            }.getOrDefault("unknown"))
        val queue = readQueue(ctx)
        queue.put(report)
        val trimmed = JSONArray()
        for (i in maxOf(0, queue.length() - MAX_QUEUED) until queue.length()) {
            trimmed.put(queue.get(i))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(QUEUE, SessionCrypto.seal(ctx, trimmed.toString()))
            .commit()
    }

    private fun readQueue(ctx: Context): JSONArray {
        val sealed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QUEUE, null)
        return runCatching {
            JSONArray(SessionCrypto.open(ctx, sealed) ?: "[]")
        }.getOrDefault(JSONArray())
    }

    fun flushAsync(ctx: Context) {
        if (!isHostProcess(ctx)) return
        val app = ctx.applicationContext
        Thread({
            val reports = readQueue(app)
            if (reports.length() == 0 || !Supabase.isSignedIn(app)) return@Thread
            if (runCatching { Supabase.uploadCrashes(app, reports) }.getOrDefault(false)) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(QUEUE, SessionCrypto.seal(app, "[]"))
                    .apply()
            }
        }, "failure-upload").apply { isDaemon = true }.start()
    }

    /** Redacts secrets and user-identifying values before either local persistence or upload. */
    fun scrub(input: String): String {
        var text = input
        text = text.replace(Regex("(?i)sessionid-[A-Za-z0-9]+"), "sessionid-<redacted>")
        text = text.replace(Regex("(?i)package-\\d+[A-Za-z0-9+_\\-]*"), "package-<redacted>")
        text = text.replace(
            Regex("(?i)\\b(password|passwd|pwd|username|user|token|authorization|bearer)\\b\\s*[=:]\\s*\\S+"),
            "$1=<redacted>"
        )
        text = text.replace(
            Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+"),
            "<jwt>"
        )
        text = text.replace(
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
            "<email>"
        )
        text = text.replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "<ip>")
        text = text.replace(
            Regex("\\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:]+\\b"),
            "<ip6>"
        )
        return text
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionName}:${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()}"
    }.getOrDefault("unknown")

    private fun isHostProcess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 28 || Application.getProcessName() == ctx.packageName
}
