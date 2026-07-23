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

    private fun accountReady(ctx: android.content.Context): Boolean =
        VaultKeyStore.isReady(ctx) && Supabase.isSignedIn(ctx)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        if (uri.lastPathSegment == "routes") {
            val routes = MatrixCursor(arrayOf("userId", "packageName", "routeId"))
            val ctx = context ?: return routes
            if (!accountReady(ctx)) return routes
            try {
                for (u in BlackBoxCore.get().users) {
                    for (pkg in GuestProxy.configuredPackagesForUser(u.id)) {
                        routes.addRow(arrayOf(u.id, pkg, GuestProxy.routeIdFor(u.id, pkg).orEmpty()))
                    }
                }
            } catch (_: Exception) {
            }
            return routes
        }
        val c = MatrixCursor(arrayOf("userId", "packageName", "label"))
        val ctx = context ?: return c
        if (!accountReady(ctx)) return c
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
        if (ctx == null || !accountReady(ctx)) {
            res.putBoolean("ok", false)
            res.putString("err", "BlackBox account is locked")
            return res
        }
        try {
            when (method) {
                "canSetProxy" -> {
                    // Read-only first phase for ShieldProxy profile saves. It lets ShieldProxy
                    // validate every clone assignment before changing either app's stored state.
                    val e = extras!!
                    val userId = e.getInt("userId", -1)
                    val pkg = e.getString("pkg")
                    val type = e.getString("type", "http").trim().lowercase()
                    val server = e.getString("server", "").trim()
                    val port = e.getInt("port")
                    val username = e.getString("username", "")
                    val password = e.getString("password", "")
                    val countryIso = e.getString("countryIso", "")
                    val validNode = server.isNotBlank() && port in 1..65535 &&
                        type in setOf("http", "https", "socks", "socks5")
                    val knownUser = userId >= 0 && BlackBoxCore.get().users.any { it.id == userId }
                    val knownApp = pkg != null && knownUser && BlackBoxCore.get().isInstalled(pkg, userId)
                    val conflict = validNode && knownApp && GuestProxy.wouldConflictWithSharedGms(
                        userId, pkg!!, type, server, port, username, password, countryIso
                    )
                    val ok = validNode && knownApp && !conflict
                    res.putBoolean("ok", ok)
                    res.putString("state", when {
                        !validNode -> "INVALID_PROXY"
                        !knownUser -> "UNKNOWN_USER"
                        !knownApp -> "UNKNOWN_CLONE"
                        conflict -> "ROUTE_CONFLICT"
                        else -> "PREFLIGHT_READY"
                    })
                    if (!ok) {
                        res.putString("err", when {
                            !validNode -> "The selected proxy configuration is invalid"
                            !knownUser -> "The BlackBox user no longer exists"
                            !knownApp -> "The selected clone is not installed in that BlackBox user"
                            else -> "This BlackBox user already uses a different proxy while Google services are shared. Move this app to a separate BlackBox user or assign the same proxy."
                        })
                    }
                }
                "assignAndVerifyRoute" -> {
                    val e = extras!!
                    val userId = e.getInt("userId", -1)
                    val pkg = e.getString("pkg")
                    val expectedExitIp = e.getString("expectedExitIp")
                    val type = e.getString("type", "http").trim().lowercase()
                    val server = e.getString("server", "").trim()
                    val port = e.getInt("port")
                    val username = e.getString("username", "")
                    val password = e.getString("password", "")
                    val countryIso = e.getString("countryIso", "")
                    val city = e.getString("city", "")
                    val region = e.getString("region", "")
                    val latitude = if (e.containsKey("latitude")) e.getDouble("latitude") else null
                    val longitude = if (e.containsKey("longitude")) e.getDouble("longitude") else null
                    val timezoneId = e.getString("timezoneId", "")
                    // Repeat the profile editor's read-only checks at the atomic launch boundary.
                    // A restored profile can outlive a deleted or moved clone, so an earlier
                    // successful preflight must not authorize a later write for the wrong user.
                    val validNode = server.isNotBlank() && port in 1..65535 &&
                        type in setOf("http", "https", "socks", "socks5")
                    val knownUser = userId >= 0 && BlackBoxCore.get().users.any { it.id == userId }
                    val knownApp = pkg != null && knownUser && BlackBoxCore.get().isInstalled(pkg, userId)
                    val validExitIp = !expectedExitIp.isNullOrBlank()
                    val conflict = validNode && knownApp && GuestProxy.wouldConflictWithSharedGms(
                        userId, pkg, type, server, port, username, password, countryIso
                    )
                    val saved = validNode && knownApp && validExitIp && !conflict && GuestProxy.save(
                        userId, pkg, type, server, port, username, password, countryIso,
                        city, region, latitude, longitude, timezoneId
                    )
                    val gmsRoute = if (saved && GuestProxy.isSharedGmsActive(userId)) {
                        GuestProxy.syncGmsRouteForUser(userId)
                    } else null
                    res.putString("gmsRoute", gmsRoute?.name.orEmpty())
                    if (!validNode) {
                        res.putBoolean("ok", false)
                        res.putString("state", "INVALID_PROXY")
                        res.putString("err", "The selected proxy configuration is invalid")
                    } else if (!knownUser) {
                        res.putBoolean("ok", false)
                        res.putString("state", "UNKNOWN_USER")
                        res.putString("err", "The BlackBox user no longer exists")
                    } else if (!knownApp) {
                        res.putBoolean("ok", false)
                        res.putString("state", "UNKNOWN_CLONE")
                        res.putString("err", "The selected clone is not installed in that BlackBox user. Choose its current user before opening it.")
                    } else if (!validExitIp) {
                        res.putBoolean("ok", false)
                        res.putString("state", "EXPECTED_EXIT_MISSING")
                        res.putString("err", "The proxy exit IP could not be confirmed")
                    } else if (conflict) {
                        res.putBoolean("ok", false)
                        res.putString("state", "ROUTE_CONFLICT")
                        res.putString("err", "This BlackBox user already uses a different proxy while Google services are shared. Move this app to a separate BlackBox user or assign the same proxy.")
                    } else if (!saved) {
                        res.putBoolean("ok", false)
                        res.putString("state", "CONFIG_REJECTED")
                        res.putString("err", "A valid app route and expected exit IP are required")
                    } else {
                        val installedPkg = pkg!!
                        val confirmedExitIp = expectedExitIp!!
                        try { BlackBoxCore.get().stopPackage(installedPkg, userId) } catch (_: Throwable) {}
                        val expectedRouteId = GuestProxy.routeIdFor(userId, installedPkg)
                        val config = if (expectedRouteId != null) {
                            BlackBoxCore.getBActivityManager().initProcess(installedPkg, installedPkg, userId)
                        } else null
                        if (config == null || expectedRouteId == null) {
                            try { BlackBoxCore.get().stopPackage(installedPkg, userId) } catch (_: Throwable) {}
                            res.putBoolean("ok", false)
                            res.putString("state", "PREPARE_FAILED")
                            res.putString("err", "Unable to create the guarded guest process")
                        } else {
                            // initProcess returns once the guest slot is requested, but its
                            // BActivityThread binder can register a few moments later on Android
                            // 16. Keep the gate fail-closed while allowing that bounded startup
                            // window; every non-NOT_RUNNING result is returned immediately.
                            val probe = verifyProxyRouteAfterStartup(
                                installedPkg, userId, expectedRouteId, confirmedExitIp
                            )
                            res.putAll(probe)
                            res.putBoolean("configured", true)
                            res.putString("expectedRouteId", expectedRouteId)
                            if (!probe.getBoolean("ok")) {
                                try { BlackBoxCore.get().stopPackage(installedPkg, userId) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                "setProxy" -> {
                    val e = extras!!
                    val userId = e.getInt("userId", -1)
                    val pkg = e.getString("pkg")   // per-app proxy; null = legacy per-user
                    val type = e.getString("type", "http").trim().lowercase()
                    val server = e.getString("server", "").trim()
                    val port = e.getInt("port")
                    val username = e.getString("username", "")
                    val password = e.getString("password", "")
                    val countryIso = e.getString("countryIso", "")
                    val city = e.getString("city", "")
                    val region = e.getString("region", "")
                    val latitude = if (e.containsKey("latitude")) e.getDouble("latitude") else null
                    val longitude = if (e.containsKey("longitude")) e.getDouble("longitude") else null
                    val timezoneId = e.getString("timezoneId", "")
                    val validNode = server.isNotBlank() && port in 1..65535 &&
                        type in setOf("http", "https", "socks", "socks5")
                    val knownUser = userId >= 0 && BlackBoxCore.get().users.any { it.id == userId }
                    val knownApp = pkg == null || (knownUser && BlackBoxCore.get().isInstalled(pkg, userId))
                    val conflict = validNode && knownApp && pkg != null && GuestProxy.wouldConflictWithSharedGms(
                        userId, pkg, type, server, port, username, password, countryIso
                    )
                    val saved = validNode && knownUser && knownApp && !conflict && GuestProxy.save(
                        userId, pkg, type, server, port, username, password, countryIso,
                        city, region, latitude, longitude, timezoneId
                    )
                    val gmsRoute = if (saved && GuestProxy.isSharedGmsActive(userId)) {
                        GuestProxy.syncGmsRouteForUser(userId)
                    } else null
                    // The guest reads its proxy at process init, so a clone that's already running
                    // (or kept alive) would keep its OLD proxy / go direct. Force-stop it so the
                    // next launch applies the just-assigned proxy — this is why "the proxy wasn't
                    // injected" on a running clone.
                    if (saved && pkg != null) {
                        try { BlackBoxCore.get().stopPackage(pkg, userId) } catch (_: Throwable) {}
                    }
                    res.putBoolean("ok", saved)
                    res.putString("state", when {
                        !validNode -> "INVALID_PROXY"
                        !knownUser -> "UNKNOWN_USER"
                        !knownApp -> "UNKNOWN_CLONE"
                        conflict -> "ROUTE_CONFLICT"
                        !saved -> "CONFIG_REJECTED"
                        gmsRoute == GuestProxy.GmsRouteStatus.CONFLICT ||
                            gmsRoute == GuestProxy.GmsRouteStatus.INVALID -> "CONFIG_SAVED_GMS_BLOCKED"
                        else -> "CONFIG_SAVED"
                    })
                    res.putString("gmsRoute", gmsRoute?.name.orEmpty())
                    if (saved) res.putString("routeId", GuestProxy.routeIdFor(userId, pkg))
                    if (!saved) {
                        res.putString("err", when {
                            !validNode -> "The selected proxy configuration is invalid"
                            !knownUser -> "The BlackBox user no longer exists"
                            !knownApp -> "The selected clone is not installed in that BlackBox user"
                            conflict -> "This BlackBox user already uses a different proxy while Google services are shared. Move this app to a separate BlackBox user or assign the same proxy."
                            else -> "Proxy configuration was invalid or could not be committed"
                        })
                    }
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
                            p.gaid, p.buildId, p.incremental, p.fingerprint, p.widevineId,
                            p.kernelSeed
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
                "userSecurityState" -> {
                    // Signed, non-secret state used by ShieldProxy before it saves assignments.
                    // ShieldProxy must not guess from a local preference: GMS may have been
                    // installed directly in BlackBox or restored on another phone.
                    val userId = extras!!.getInt("userId", -1)
                    val knownUser = userId >= 0 && BlackBoxCore.get().users.any { it.id == userId }
                    res.putBoolean("ok", knownUser)
                    if (knownUser) {
                        res.putBoolean("gmsInstalled", BlackBoxCore.get().isInstallGms(userId))
                        res.putBoolean(
                            "keepAliveEnabled",
                            top.niunaijun.blackbox.core.GuestKeepAlive.isEnabled(userId)
                        )
                        res.putBoolean("sharedGmsActive", GuestProxy.isSharedGmsActive(userId))
                    } else {
                        res.putString("err", "Unknown BlackBox user")
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
                    val userId = e.getInt("userId")
                    val launched = BlackBoxCore.get().launchApk(e.getString("pkg"), userId)
                    res.putBoolean("ok", launched)
                    if (!launched) {
                        res.putString(
                            "err",
                            BlackBoxCore.get().getLaunchBlockReason(userId)
                                ?: "The clone could not be launched"
                        )
                    }
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
        repeat(30) { attempt ->
            last = BlackBoxCore.getBActivityManager()
                .verifyProxyRoute(pkg, userId, routeId, expectedExitIp)
            if (last.getString("state") != "NOT_RUNNING") return last
            // WhatsApp can tear down its empty warm-up process while Android is resolving its
            // registration/permission activities. Re-request the same guarded process during the
            // bounded gate instead of treating that transient teardown as a permanent failure.
            if (attempt == 7 || attempt == 15 || attempt == 23) {
                runCatching {
                    BlackBoxCore.getBActivityManager().initProcess(pkg, pkg, userId)
                }
            }
            if (attempt < 29) {
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
