package top.niunaijun.blackboxa.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GuestProxy
import top.niunaijun.blackboxa.cloud.Supabase
import top.niunaijun.blackboxa.cloud.VaultKeyStore
import top.niunaijun.blackboxa.cloud.DriveFolderStore

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
        val ctx = context ?: return c
        if (!Supabase.isSignedIn(ctx) || !VaultKeyStore.isReady(ctx)) return c
        try {
            val core = BlackBoxCore.get()
            val pm = BlackBoxCore.getPackageManager()
            for (u in core.users) {
                val apps = core.getInstalledApplications(0, u.id) ?: continue
                for (a in apps) {
                    val fallback = try {
                        pm.getApplicationLabel(a).toString()
                    } catch (e: Exception) {
                        a.packageName
                    }
                    val label = top.niunaijun.blackboxa.util.CloneNameResolver.resolve(
                        u.id,
                        a.packageName,
                        fallback
                    )
                    c.addRow(arrayOf(u.id, a.packageName, label))
                }
            }
        } catch (_: Exception) {
        }
        return c
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val res = Bundle()
        val ctx = context
        if (method == "status") {
            val signedIn = ctx != null && Supabase.isSignedIn(ctx)
            val vaultReady = ctx != null && VaultKeyStore.isReady(ctx)
            res.putBoolean("ok", signedIn && vaultReady)
            res.putBoolean("signedIn", signedIn)
            res.putBoolean("vaultReady", vaultReady)
            res.putString("ownerHash", if (ctx != null && vaultReady) VaultKeyStore.ownerHash(ctx) else null)
            res.putBoolean("driveConnected", ctx != null && DriveFolderStore.isConnected(ctx))
            res.putString("app", "blackbox")
            res.putString("versionName", ctx?.let {
                runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.getOrNull()
            }.orEmpty())
            if (!signedIn || !vaultReady) res.putString("err", "BlackBox account is locked")
            return res
        }
        if (ctx == null || !Supabase.isSignedIn(ctx) || !VaultKeyStore.isReady(ctx)) {
            res.putBoolean("ok", false)
            res.putString("err", "BlackBox account is locked")
            return res
        }
        try {
            when (method) {
                "assignAndVerifyRoute" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    val expectedExitIp = e.getString("expectedExitIp")
                    val saved = GuestProxy.save(
                        userId,
                        pkg,
                        e.getString("type", "http"),
                        e.getString("server", ""),
                        e.getInt("port"),
                        e.getString("username", ""),
                        e.getString("password", "")
                    )
                    val gmsRoute = if (saved) GuestProxy.syncGmsRouteForUser(userId) else null
                    res.putString("gmsRoute", gmsRoute?.name.orEmpty())
                    if (!saved || pkg == null || expectedExitIp.isNullOrBlank()) {
                        res.putBoolean("ok", false)
                        res.putString("state", "CONFIG_REJECTED")
                        res.putString("err", "A valid app route and expected exit IP are required")
                    } else {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                        val expectedRouteId = GuestProxy.routeIdFor(userId, pkg)
                        val config = if (expectedRouteId != null) {
                            BlackBoxCore.getBActivityManager().initProcess(pkg, pkg, userId)
                        } else null
                        if (config == null || expectedRouteId == null) {
                            try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                            res.putBoolean("ok", false)
                            res.putString("state", "PREPARE_FAILED")
                            res.putString("err", "Unable to create the guarded guest process")
                        } else {
                            // initProcess returns once the guest slot is requested, but its
                            // BActivityThread binder can register a few moments later on Android
                            // 16. Keep the gate fail-closed while allowing that bounded startup
                            // window; every non-NOT_RUNNING result is returned immediately.
                            val probe = verifyProxyRouteAfterStartup(
                                pkg, userId, expectedRouteId, expectedExitIp
                            )
                            res.putAll(probe)
                            res.putBoolean("configured", true)
                            res.putString("expectedRouteId", expectedRouteId)
                            if (!probe.getBoolean("ok")) {
                                try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                "setProxy" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")   // per-app proxy; null = legacy per-user
                    val saved = GuestProxy.save(
                        userId,
                        pkg,
                        e.getString("type", "http"),
                        e.getString("server", ""),
                        e.getInt("port"),
                        e.getString("username", ""),
                        e.getString("password", "")
                    )
                    val gmsRoute = if (saved && pkg != null) GuestProxy.syncGmsRouteForUser(userId) else null
                    // The guest reads its proxy at process init, so a clone that's already running
                    // (or kept alive) would keep its OLD proxy / go direct. Force-stop it so the
                    // next launch applies the just-assigned proxy — this is why "the proxy wasn't
                    // injected" on a running clone.
                    if (saved && pkg != null) {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    }
                    res.putBoolean("ok", saved)
                    res.putString("state", when {
                        !saved -> "CONFIG_REJECTED"
                        gmsRoute == GuestProxy.GmsRouteStatus.CONFLICT ||
                            gmsRoute == GuestProxy.GmsRouteStatus.INVALID -> "CONFIG_SAVED_GMS_BLOCKED"
                        else -> "CONFIG_SAVED"
                    })
                    res.putString("gmsRoute", gmsRoute?.name.orEmpty())
                    if (saved) res.putString("routeId", GuestProxy.routeIdFor(userId, pkg))
                    if (!saved) res.putString("err", "Proxy configuration was invalid or could not be committed")
                }
                "getRouteState" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    val routeId = GuestProxy.routeIdFor(userId, pkg)
                    val running = if (pkg != null) {
                        try {
                            BlackBoxCore.getBActivityManager().isAppProcessRunning(pkg, userId)
                        } catch (_: Throwable) { false }
                    } else false
                    res.putBoolean("ok", routeId != null)
                    res.putBoolean("configured", routeId != null)
                    res.putBoolean("running", running)
                    res.putString("routeId", routeId)
                    res.putString("state", when {
                        routeId == null -> "CONFIG_MISSING"
                        running -> "RUNNING_UNVERIFIED"
                        else -> "CONFIG_READY"
                    })
                }
                "identityStatus" -> {
                    // Return only a one-way digest. ShieldProxy can prove two users are distinct
                    // without receiving any Android ID, GAID, IMEI, MAC or Widevine value.
                    val userId = extras!!.getInt("userId", -1)
                    if (userId < 0 || BlackBoxCore.get().users.none { it.id == userId }) {
                        res.putBoolean("ok", false)
                        res.putString("err", "Unknown BlackBox user")
                    } else {
                        val p = top.niunaijun.blackbox.core.DeviceProfile.forUser(userId)
                        val fields = listOf(
                            p.androidId, p.imei, p.imsi, p.serial, p.macWifi,
                            p.gaid, p.buildId, p.incremental, p.fingerprint, p.widevineId
                        )
                        val complete = fields.none { it.isNullOrBlank() }
                        val digest = if (complete) {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                        } else ""
                        res.putBoolean("ok", complete)
                        res.putBoolean("complete", complete)
                        res.putString("identityDigest", digest)
                        if (!complete) res.putString("err", "Incomplete identity profile")
                    }
                }
                "verifyRoute" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    val expected = GuestProxy.routeIdFor(userId, pkg)
                    if (pkg == null || expected == null) {
                        res.putBoolean("ok", false)
                        res.putString("state", "CONFIG_MISSING")
                        res.putString("err", "No valid app-specific proxy assignment")
                    } else {
                        val expectedExitIp = e.getString("expectedExitIp")
                        val probe = BlackBoxCore.getBActivityManager()
                            .verifyProxyRoute(pkg, userId, expected, expectedExitIp)
                        res.putAll(probe)
                        res.putBoolean("configured", true)
                        res.putBoolean("running", probe.getString("state") != "NOT_RUNNING")
                        res.putString("expectedRouteId", expected)
                    }
                }
                "prepareRoute" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    val expected = GuestProxy.routeIdFor(userId, pkg)
                    if (pkg == null || expected == null) {
                        res.putBoolean("ok", false)
                        res.putString("state", "CONFIG_MISSING")
                        res.putString("err", "No valid app-specific proxy assignment")
                    } else {
                        val config = BlackBoxCore.getBActivityManager().initProcess(pkg, pkg, userId)
                        res.putBoolean("ok", config != null)
                        res.putString("state", if (config != null) "ROUTE_PREPARED" else "PREPARE_FAILED")
                        res.putString("routeId", expected)
                        if (config == null) res.putString("err", "Unable to create the guarded guest process")
                    }
                }
                "clearProxy" -> {
                    val e = extras!!
                    val userId = e.getInt("userId")
                    val pkg = e.getString("pkg")
                    val cleared = GuestProxy.clear(userId, pkg)
                    val gmsRoute = if (cleared && pkg != null) GuestProxy.syncGmsRouteForUser(userId) else null
                    if (cleared && pkg != null) {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    }
                    res.putBoolean("ok", cleared)
                    res.putString("state", if (cleared) "CONFIG_CLEARED" else "CLEAR_FAILED")
                    res.putString("gmsRoute", gmsRoute?.name.orEmpty())
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
                    val running = try {
                        pkg != null && BlackBoxCore.getBActivityManager().isAppProcessRunning(pkg, userId)
                    } catch (_: Throwable) { false }
                    res.putBoolean("ok", true)
                    res.putBoolean("running", running)
                }
                "setKeepAlive" -> {
                    // Keep a clone receiving push in the background: installs GMS (FCM) + keeps the
                    // container daemon alive for this User.
                    val e = extras!!
                    val uid = e.getInt("userId")
                    val on = e.getBoolean("enabled", true)
                    val enabled = top.niunaijun.blackbox.core.GuestKeepAlive.setEnabled(uid, on)
                    res.putBoolean("ok", enabled)
                    res.putBoolean("gms", BlackBoxCore.get().isInstallGms(uid))
                    res.putString("gmsRoute", GuestProxy.syncGmsRouteForUser(uid).name)
                    if (!enabled) res.putString("err",
                        "Google notifications require one proxy per BlackBox user; move differently routed apps to separate users")
                }
                else -> res.putBoolean("ok", false)
            }
        } catch (ex: Exception) {
            res.putBoolean("ok", false)
            res.putString("err", ex.message)
        }
        return res
    }

    private fun verifyProxyRouteAfterStartup(
        pkg: String, userId: Int, routeId: String, expectedExitIp: String
    ): Bundle {
        var last = Bundle().apply {
            putBoolean("ok", false)
            putString("state", "NOT_RUNNING")
            putString("err", "The routed guest process did not become ready")
        }
        repeat(16) { attempt ->
            last = BlackBoxCore.getBActivityManager()
                .verifyProxyRoute(pkg, userId, routeId, expectedExitIp)
            if (last.getString("state") != "NOT_RUNNING") return last
            if (attempt < 15) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return last
                }
            }
        }
        return last
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
