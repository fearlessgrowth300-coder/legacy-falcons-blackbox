package top.niunaijun.blackboxa.cloud

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.util.zip.ZipEntry

/** Full encrypted container backup. Guest caches and crash logs are intentionally excluded. */
object CloudSync {
    private const val APP_TAG = "blackbox"
    private val EXCLUDED_DIRS = setOf("cache", "code_cache", "crash_logs", "proc")
    private val EXCLUDED_PREFS = setOf(
        "sb_session.xml", "drive_vault_key.xml", "drive_backup_folder.xml",
        "backup_job_state.xml"
    )
    data class RunningClone(val userId: Int, val packageName: String)

    fun hasBackup(ctx: Context) = DriveVault.hasBackup(ctx, APP_TAG)

    @Synchronized
    fun push(ctx: Context, progress: (Long) -> Unit = {}) {
        require(Supabase.isSignedIn(ctx) && DriveFolderStore.isConnected(ctx)) {
            "Account or Google Drive is not connected"
        }
        val root = File(ctx.dataDir, "blackbox")
        val prefs = File(ctx.dataDir, "shared_prefs")
        val sourceBytes = sourceSize(root) + prefs.listFiles().orEmpty()
            .filter { it.isFile && it.name !in EXCLUDED_PREFS }.sumOf { it.length() }
        val running = stopGuestsForSnapshot()
        try {
            Thread.sleep(1_000)
            DriveVault.backup(ctx, APP_TAG, keep = 1, sourceBytes = sourceBytes) { zip ->
                zip.setLevel(1)
                if (root.isDirectory) addTree(zip, root, ctx.dataDir, progress, hashSetOf())
                prefs.listFiles()?.filter { it.isFile && it.name !in EXCLUDED_PREFS }?.forEach {
                    addFile(zip, it, ctx.dataDir, progress)
                }
            }
        } finally {
            restartPreparedGuests(running)
        }
    }

    /**
     * Decrypt to a staging tree first, verify every authenticated chunk and ZIP CRC, then swap.
     * Returns true when a restart is required to activate the restored container.
     */
    @Synchronized
    fun restore(ctx: Context, progress: (Long) -> Unit = {}): Boolean {
        require(Supabase.isSignedIn(ctx) && DriveFolderStore.isConnected(ctx)) {
            "Account or Google Drive is not connected"
        }
        val sourceBytes = DriveVault.latestSourceBytes(ctx, APP_TAG)
        val reserve = maxOf(512L * 1024 * 1024, sourceBytes / 5)
        require(StatFs(ctx.dataDir.absolutePath).availableBytes > sourceBytes + reserve) {
            "Not enough free storage. Free at least ${(sourceBytes + reserve) / (1024 * 1024)} MB and retry"
        }
        val running = stopGuestsForSnapshot()
        val stagedRoot = File(ctx.dataDir, "blackbox.restore")
        val stagedPrefs = File(ctx.dataDir, "shared_prefs.restore")
        var activated = false
        try {
            Thread.sleep(1_000)
            deleteTree(stagedRoot); deleteTree(stagedPrefs)
            stagedRoot.mkdirs(); stagedPrefs.mkdirs()
            var restoredBytes = 0L
            val result = try {
                DriveVault.restore(ctx, APP_TAG) { zip ->
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries < 1_000_000) { "Backup contains too many files" }
                        val clean = entry.name.replace('\\', '/')
                        val destination = when {
                            clean.startsWith("blackbox/") -> File(stagedRoot, clean.removePrefix("blackbox/"))
                            clean.startsWith("shared_prefs/") -> File(stagedPrefs, clean.removePrefix("shared_prefs/"))
                            else -> error("Backup contains an unexpected path")
                        }
                        val allowedRoot = if (clean.startsWith("blackbox/")) stagedRoot else stagedPrefs
                        require(destination.canonicalPath.startsWith(allowedRoot.canonicalPath + File.separator)) {
                            "Unsafe backup path"
                        }
                        if (entry.isDirectory) destination.mkdirs() else {
                            destination.parentFile?.mkdirs()
                            destination.outputStream().buffered().use { out ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                    restoredBytes += count
                                    require(restoredBytes <= 20L * 1024 * 1024 * 1024) { "Backup is too large" }
                                    progress(restoredBytes)
                                }
                            }
                            destination.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                deleteTree(stagedRoot); deleteTree(stagedPrefs)
                throw e
            }
            if (!result.restored) {
                deleteTree(stagedRoot); deleteTree(stagedPrefs)
                return false
            }
            activateStagedRestore(ctx, stagedRoot, stagedPrefs)
            activated = true
            return true
        } finally {
            if (!activated) restartPreparedGuests(running)
        }
    }

    /** Fully decrypts and reads every ZIP entry without changing the current container. */
    @Synchronized
    fun verifyLatest(ctx: Context, progress: (Long) -> Unit = {}): Boolean {
        var checked = 0L
        return DriveVault.restore(ctx, APP_TAG) { zip ->
            val buffer = ByteArray(128 * 1024)
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries < 1_000_000) { "Backup contains too many files" }
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    checked += count
                    require(checked <= 20L * 1024 * 1024 * 1024) { "Backup is too large" }
                    progress(checked)
                }
                zip.closeEntry()
            }
        }.restored
    }

    fun cleanupPreviousRestore(ctx: Context) {
        recoverInterruptedRestore(ctx)
        deleteTree(File(ctx.dataDir, "blackbox.before-restore"))
        deleteTree(File(ctx.dataDir, "shared_prefs.restore"))
    }

    /** Must run before BlackBoxCore opens its data tree. Rolls back an interrupted directory swap. */
    @Synchronized
    fun recoverInterruptedRestore(ctx: Context) {
        val marker = File(ctx.dataDir, "blackbox.restore-in-progress")
        if (!marker.isFile) return
        val state = marker.readLines()
        val rootExisted = state.firstOrNull { it.startsWith("ROOT=") }
            ?.substringAfter('=') == "1"
        val active = File(ctx.dataDir, "blackbox")
        val previous = File(ctx.dataDir, "blackbox.before-restore")
        if (previous.exists()) {
            deleteTree(active)
            if (!previous.renameTo(active)) error("Could not recover container data")
        } else if (!rootExisted) {
            deleteTree(active)
        }
        val prefs = File(ctx.dataDir, "shared_prefs").apply { mkdirs() }
        state.filter { it.startsWith("P=") }.forEach { row ->
            val value = row.substringAfter("P=")
            val name = value.substringBeforeLast('|')
            val existed = value.substringAfterLast('|') == "1"
            if (name.contains('/') || name.contains('\\')) return@forEach
            val target = File(prefs, name)
            val old = File(prefs, "$name.before-restore")
            if (old.exists()) {
                target.delete()
                if (!old.renameTo(target)) error("Could not recover $name")
            } else if (!existed) {
                target.delete()
            }
        }
        marker.delete()
        deleteTree(File(ctx.dataDir, "blackbox.restore"))
        deleteTree(File(ctx.dataDir, "shared_prefs.restore"))
    }

    private fun activateStagedRestore(ctx: Context, stagedRoot: File, stagedPrefs: File) {
        val active = File(ctx.dataDir, "blackbox")
        val previous = File(ctx.dataDir, "blackbox.before-restore")
        val marker = File(ctx.dataDir, "blackbox.restore-in-progress")
        deleteTree(previous)
        val prefs = File(ctx.dataDir, "shared_prefs").apply { mkdirs() }
        val stagedPreferenceFiles = stagedPrefs.listFiles().orEmpty().filter { it.isFile }
        stagedPreferenceFiles.forEach { source ->
            val stale = File(prefs, source.name + ".before-restore")
            if (stale.exists() && !stale.delete()) error("Could not clear stale restore state")
        }
        val state = buildList {
            add("ROOT=${if (active.exists()) 1 else 0}")
            stagedPreferenceFiles.forEach { source ->
                add("P=${source.name}|${if (File(prefs, source.name).exists()) 1 else 0}")
            }
        }
        java.io.FileOutputStream(marker).use { output ->
            output.write(state.joinToString("\n").toByteArray())
            output.fd.sync()
        }
        try {
            if (active.exists() && !active.renameTo(previous)) error("Could not preserve current container")
            if (!stagedRoot.renameTo(active)) error("Could not activate restored container")
            stagedPreferenceFiles.forEach { source ->
                val target = File(prefs, source.name)
                val old = File(prefs, source.name + ".before-restore")
                old.delete()
                if (target.exists() && !target.renameTo(old)) error("Could not preserve ${source.name}")
                if (!source.renameTo(target)) error("Could not restore ${source.name}")
            }
            marker.delete()
            deleteTree(previous)
            stagedPreferenceFiles.forEach { File(prefs, it.name + ".before-restore").delete() }
            deleteTree(stagedPrefs)
        } catch (error: Throwable) {
            recoverInterruptedRestore(ctx)
            throw error
        }
    }

    private fun stopGuestsForSnapshot(): List<RunningClone> {
        val core = BlackBoxCore.get()
        val running = arrayListOf<RunningClone>()
        for (user in core.users) {
            for (app in core.getInstalledApplications(0, user.id).orEmpty()) {
                val pkg = app.packageName ?: continue
                if (BlackBoxCore.isRunningApplication(pkg, user.id)) running += RunningClone(user.id, pkg)
                runCatching { core.stopPackage(pkg, user.id) }
            }
        }
        return running
    }

    private fun restartPreparedGuests(clones: List<RunningClone>) {
        for (clone in clones) runCatching {
            BlackBoxCore.getBActivityManager().initProcess(
                clone.packageName, clone.packageName, clone.userId
            )
        }
    }

    /** Delete only entries beneath app-private storage, including guest-created read-only dirs. */
    private fun deleteTree(file: File) {
        if (!file.exists()) return
        val isDirectory = try {
            OsConstants.S_ISDIR(Os.lstat(file.absolutePath).st_mode)
        } catch (_: Exception) {
            file.isDirectory
        }
        if (isDirectory) {
            file.setReadable(true, true)
            file.setExecutable(true, true)
            file.setWritable(true, true)
            val children = file.listFiles()
                ?: error("Could not inspect old restore data")
            children.forEach(::deleteTree)
        }
        file.setWritable(true, true)
        if (!file.delete() && file.exists()) error("Could not clear old restore data")
    }

    private fun addTree(zip: java.util.zip.ZipOutputStream, file: File, base: File,
        progress: (Long) -> Unit, visited: HashSet<String>) {
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(base.canonicalPath + File.separator)) { "Unsafe source path" }
        if (canonical.isDirectory) {
            if (canonical.name in EXCLUDED_DIRS) return
            if (!visited.add(canonical.path)) return
            canonical.listFiles()?.sortedBy { it.name }?.forEach {
                addTree(zip, it, base, progress, visited)
            }
        } else if (canonical.isFile) addFile(zip, canonical, base, progress)
    }

    private fun sourceSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isDirectory) {
            if (file.name in EXCLUDED_DIRS) return 0L
            return file.listFiles().orEmpty().sumOf(::sourceSize)
        }
        return if (file.isFile) file.length() else 0L
    }

    private fun addFile(zip: java.util.zip.ZipOutputStream, file: File, base: File,
        progress: (Long) -> Unit) {
        val relative = file.relativeTo(base).path.replace(File.separatorChar, '/')
        val entry = ZipEntry(relative).apply { time = file.lastModified() }
        zip.putNextEntry(entry)
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                zip.write(buffer, 0, count)
                total += count
                progress(count.toLong())
            }
        }
        zip.closeEntry()
    }
}
