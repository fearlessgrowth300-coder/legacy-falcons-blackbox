package top.niunaijun.blackboxa.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GuestProxy

/**
 * Bridge that lets ShieldProxy (a separate app) enumerate this container's clones and
 * assign a proxy to any one of them — so the user can manage per-clone proxies from
 * ShieldProxy instead of opening BlackBox and setting them one by one.
 *
 *  query(content://top.niunaijun.blackbox.bridge/apps) -> rows [userId, packageName, label]
 *  call("setProxy",  extras{userId,type,server,port,username,password})
 *  call("clearProxy", extras{userId})
 */
class BlackBoxBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val c = MatrixCursor(arrayOf("userId", "packageName", "label"))
        try {
            val core = BlackBoxCore.get()
            val pm = BlackBoxCore.getPackageManager()
            val prefs = top.niunaijun.blackboxa.app.AppManager.mRemarkSharedPreferences
            for (u in core.users) {
                val apps = core.getInstalledApplications(0, u.id) ?: continue
                for (a in apps) {
                    val custom = prefs.getString("cloneName_${u.id}_${a.packageName}", null)
                    val label = if (!custom.isNullOrBlank()) custom else try {
                        pm.getApplicationLabel(a).toString()
                    } catch (e: Exception) {
                        a.packageName
                    }
                    c.addRow(arrayOf(u.id, a.packageName, label))
                }
            }
        } catch (_: Exception) {
        }
        return c
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val res = Bundle()
        try {
            when (method) {
                "setProxy" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")   // per-app proxy; null = legacy per-user
                    GuestProxy.save(
                        userId,
                        pkg,
                        e.getString("type", "http"),
                        e.getString("server", ""),
                        e.getInt("port"),
                        e.getString("username", ""),
                        e.getString("password", "")
                    )
                    // The guest reads its proxy at process init, so a clone that's already running
                    // (or kept alive) would keep its OLD proxy / go direct. Force-stop it so the
                    // next launch applies the just-assigned proxy — this is why "the proxy wasn't
                    // injected" on a running clone.
                    if (pkg != null) {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    }
                    res.putBoolean("ok", true)
                }
                "clearProxy" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    GuestProxy.clear(userId, pkg)
                    if (pkg != null) {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    }
                    res.putBoolean("ok", true)
                }
                "installGms" -> {
                    val r = BlackBoxCore.get().installGms(extras!!.getInt("userId"))
                    res.putBoolean("ok", r.success)
                    res.putString("err", r.msg)
                }
                "installApp" -> {
                    // Clone an already-installed host app (by package name) into a User.
                    val e = extras!!
                    val r = BlackBoxCore.get().installPackageAsUser(e.getString("pkg"), e.getInt("userId"))
                    res.putBoolean("ok", r.success)
                    res.putString("err", r.msg)
                }
                "launchApp" -> {
                    // Launch a clone (so it picks up a freshly-assigned proxy). Lets ShieldProxy
                    // open all of a list's clones at once.
                    val e = extras!!
                    BlackBoxCore.get().launchApk(e.getString("pkg"), e.getInt("userId"))
                    res.putBoolean("ok", true)
                }
                "uninstallApp" -> {
                    val e = extras!!
                    BlackBoxCore.get().uninstallPackageAsUser(e.getString("pkg"), e.getInt("userId"))
                    res.putBoolean("ok", true)
                }
                "removeGms" -> {
                    BlackBoxCore.get().uninstallGms(extras!!.getInt("userId"))
                    res.putBoolean("ok", true)
                }
                "installApk" -> {
                    // Install an APK file (e.g. the OFFICIAL Instagram) directly into a User.
                    val e = extras!!
                    val r = BlackBoxCore.get().installPackageAsUser(java.io.File(e.getString("apkPath")!!), e.getInt("userId"))
                    res.putBoolean("ok", r.success)
                    res.putString("err", r.msg)
                }
                "stopApp" -> {
                    // Kill-switch: force-close a running clone. ShieldProxy's proxy guard calls this
                    // the instant the assigned proxy/session dies, so the app can NEVER keep running
                    // (and leaking to the real IP) once its proxy is gone. Fail-closed by design.
                    val e = extras!!
                    val pkg = e.getString("pkg")
                    val userId = e.getInt("userId")
                    try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    res.putBoolean("ok", true)
                }
                "isRunning" -> {
                    // Report whether a clone process is currently alive, so the guard only kills
                    // (and only nags the user) when the app is actually in use.
                    val e = extras!!
                    val pkg = e.getString("pkg")
                    val userId = e.getInt("userId")
                    val running = try { BlackBoxCore.isRunningApplication(pkg, userId) } catch (_: Throwable) { false }
                    res.putBoolean("ok", true)
                    res.putBoolean("running", running)
                }
                "setKeepAlive" -> {
                    // Keep a clone receiving push in the background: installs GMS (FCM) + keeps the
                    // container daemon alive for this User.
                    val e = extras!!
                    val uid = e.getInt("userId")
                    val on = e.getBoolean("enabled", true)
                    top.niunaijun.blackbox.core.GuestKeepAlive.setEnabled(uid, on)
                    res.putBoolean("ok", true)
                    res.putBoolean("gms", BlackBoxCore.get().isInstallGms(uid))
                }
                else -> res.putBoolean("ok", false)
            }
        } catch (ex: Exception) {
            res.putBoolean("ok", false)
            res.putString("err", ex.message)
        }
        return res
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
