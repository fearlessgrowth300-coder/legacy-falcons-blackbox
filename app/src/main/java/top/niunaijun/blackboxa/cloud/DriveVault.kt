package top.niunaijun.blackboxa.cloud

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** End-to-end encrypted Drive chunks; an interrupted snapshot never receives a completion marker. */
object DriveVault {
    private const val ROOT = "ShieldBox"
    private const val FORMAT = 1
    private const val CHUNK_SIZE = 8 * 1024 * 1024
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'B'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())

    data class RestoreResult(val restored: Boolean, val createdAt: Long = 0L)

    fun hasBackup(ctx: Context, appTag: String) = latestSnapshot(ctx, appTag) != null

    fun backup(ctx: Context, appTag: String, keep: Int = 1, sourceBytes: Long = 0L,
        writeZip: (ZipOutputStream) -> Unit) {
        val key = VaultKeyStore.load(ctx) ?: error("Backup encryption is not unlocked")
        val appDir = appDir(ctx, appTag)
        val snapshot = DriveFolderStore.findOrCreateDir(appDir, System.currentTimeMillis().toString())
        val sink = ChunkedEncryptedOutput(ctx, snapshot, appTag, key)
        try {
            ZipOutputStream(sink).use(writeZip)
            val manifest = JSONObject()
                .put("format", FORMAT).put("app", appTag)
                .put("createdAt", System.currentTimeMillis())
                .put("parts", sink.partCount).put("plainBytes", sink.plainBytes)
                .put("sourceBytes", sourceBytes)
                .put("sha256", sink.sha256)
            writeEncryptedBlob(ctx, snapshot, "complete.sbx", manifest.toString().toByteArray(),
                key, "manifest|$appTag")
            prune(appDir, keep)
        } catch (e: Exception) {
            runCatching { snapshot.delete() }
            throw e
        }
    }

    fun restore(ctx: Context, appTag: String, readZip: (ZipInputStream) -> Unit): RestoreResult {
        val key = VaultKeyStore.load(ctx) ?: error("Backup encryption is not unlocked")
        val snapshot = latestSnapshot(ctx, appTag) ?: return RestoreResult(false)
        val complete = snapshot.findFile("complete.sbx") ?: return RestoreResult(false)
        val manifest = JSONObject(String(readEncryptedBlob(ctx, complete, key, "manifest|$appTag")))
        require(manifest.optInt("format") == FORMAT && manifest.optString("app") == appTag) {
            "Unsupported or mismatched backup"
        }
        val source = ChunkedEncryptedInput(ctx, snapshot, appTag, key, manifest.getInt("parts"))
        ZipInputStream(source).use(readZip)
        // ZipInputStream stops when it encounters the first central-directory record.  Large
        // clone snapshots can have a central directory spanning more than one encrypted part,
        // so authenticate/drain the remainder before comparing the whole-stream digest.
        val drain = ByteArray(128 * 1024)
        while (source.read(drain) >= 0) { /* authenticate the ZIP directory tail */ }
        require(source.sha256 == manifest.getString("sha256")) { "Backup integrity check failed" }
        return RestoreResult(true, manifest.optLong("createdAt"))
    }

    /** Returns the uncompressed source size recorded inside the encrypted completion marker. */
    fun latestSourceBytes(ctx: Context, appTag: String): Long {
        val key = VaultKeyStore.load(ctx) ?: error("Backup encryption is not unlocked")
        val snapshot = latestSnapshot(ctx, appTag) ?: return 0L
        val complete = snapshot.findFile("complete.sbx") ?: return 0L
        val manifest = JSONObject(String(readEncryptedBlob(ctx, complete, key, "manifest|$appTag")))
        require(manifest.optInt("format") == FORMAT && manifest.optString("app") == appTag) {
            "Unsupported or mismatched backup"
        }
        return manifest.optLong("sourceBytes").takeIf { it > 0L }
            ?: manifest.optLong("plainBytes")
    }

    private fun appDir(ctx: Context, appTag: String): DocumentFile {
        val selected = DriveFolderStore.root(ctx) ?: error("Google Drive folder is not connected")
        val owner = VaultKeyStore.ownerHash(ctx) ?: error("Account encryption is not configured")
        return DriveFolderStore.findOrCreateDir(
            DriveFolderStore.findOrCreateDir(
                DriveFolderStore.findOrCreateDir(selected, ROOT), owner), appTag)
    }

    private fun latestSnapshot(ctx: Context, appTag: String): DocumentFile? = runCatching {
        appDir(ctx, appTag).listFiles()
            .filter { it.isDirectory && it.findFile("complete.sbx")?.isFile == true }
            .maxByOrNull { it.name?.toLongOrNull() ?: 0L }
    }.getOrNull()

    private fun prune(appDir: DocumentFile, keep: Int) {
        appDir.listFiles().filter { it.isDirectory }
            .sortedByDescending { it.name?.toLongOrNull() ?: 0L }
            .drop(keep).forEach { runCatching { it.delete() } }
    }

    private fun aad(appTag: String, index: Int) = "ShieldBox|$FORMAT|$appTag|$index".toByteArray()

    private fun createFile(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { runCatching { it.delete() } }
        return parent.createFile("application/octet-stream", name)
            ?: error("Could not create Drive file $name")
    }

    private fun writeEncryptedBlob(ctx: Context, parent: DocumentFile, name: String,
        plain: ByteArray, key: SecretKeySpec, aadText: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aadText.toByteArray())
        val file = createFile(parent, name)
        ctx.contentResolver.openOutputStream(file.uri, "w")!!.use { raw ->
            DataOutputStream(raw).use { out ->
                out.write(MAGIC); out.writeInt(FORMAT); out.writeInt(cipher.iv.size)
                out.write(cipher.iv); out.writeInt(plain.size); out.write(cipher.doFinal(plain))
            }
        }
    }

    private fun readEncryptedBlob(ctx: Context, file: DocumentFile, key: SecretKeySpec,
        aadText: String): ByteArray {
        val bytes = readDriveBytes(ctx, file)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            require(magic.contentEquals(MAGIC) && input.readInt() == FORMAT) { "Invalid backup file" }
            val iv = ByteArray(input.readInt()).also { input.readFully(it) }
            val plainLength = input.readInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aadText.toByteArray())
            return cipher.doFinal(input.readBytes()).also { require(it.size == plainLength) }
        }
    }

    /**
     * Google Drive's DocumentsProvider occasionally aborts a long download with a transient
     * stream/backend error.  Each encrypted part is independently authenticated, so retrying
     * only the failed part is safe and avoids throwing away a multi-gigabyte staged restore.
     */
    private fun readDriveBytes(ctx: Context, file: DocumentFile): ByteArray {
        var lastError: Exception? = null
        var delayMs = 1_000L
        repeat(DRIVE_READ_ATTEMPTS) { attempt ->
            try {
                return (ctx.contentResolver.openInputStream(file.uri)
                    ?: throw IOException("Google Drive returned no input stream"))
                    .use { it.readBytes() }
            } catch (error: Exception) {
                if (error is InterruptedException || error is SecurityException) throw error
                lastError = error
                if (attempt == DRIVE_READ_ATTEMPTS - 1) return@repeat
                Thread.sleep(delayMs)
                delayMs = minOf(delayMs * 2, DRIVE_READ_MAX_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Could not read encrypted Google Drive part")
    }

    private class ChunkedEncryptedOutput(private val ctx: Context, private val snapshot: DocumentFile,
        private val appTag: String, private val key: SecretKeySpec) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var used = 0
        private var index = 0
        private val digest = MessageDigest.getInstance("SHA-256")
        var plainBytes = 0L; private set
        val partCount get() = index
        val sha256 get() = digest.digest().joinToString("") { "%02x".format(it) }

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(source: ByteArray, offset: Int, length: Int) {
            digest.update(source, offset, length); plainBytes += length
            var at = offset; var left = length
            while (left > 0) {
                val take = minOf(left, buffer.size - used)
                System.arraycopy(source, at, buffer, used, take)
                used += take; at += take; left -= take
                if (used == buffer.size) flushPart()
            }
        }
        override fun close() { if (used > 0) flushPart() }

        private fun flushPart() {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad(appTag, index))
            val encrypted = cipher.doFinal(buffer, 0, used)
            val file = createFile(snapshot, "part-${index.toString().padStart(5, '0')}.sbx")
            ctx.contentResolver.openOutputStream(file.uri, "w")!!.use { raw ->
                DataOutputStream(raw).use { out ->
                    out.write(MAGIC); out.writeInt(FORMAT); out.writeInt(index)
                    out.writeInt(cipher.iv.size); out.write(cipher.iv); out.writeInt(used); out.write(encrypted)
                }
            }
            index++; used = 0
        }
    }

    private class ChunkedEncryptedInput(private val ctx: Context, snapshot: DocumentFile,
        private val appTag: String, private val key: SecretKeySpec, expectedParts: Int) : InputStream() {
        private val parts = snapshot.listFiles().filter {
            it.isFile && it.name?.matches(Regex("part-\\d{5}\\.sbx")) == true
        }.sortedBy { it.name }
        private var nextPart = 0
        private var current = ByteArrayInputStream(ByteArray(0))
        private val digest = MessageDigest.getInstance("SHA-256")
        val sha256 get() = digest.digest().joinToString("") { "%02x".format(it) }
        init { require(parts.size == expectedParts) { "Backup is missing one or more parts" } }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            while (current.available() == 0) {
                if (nextPart >= parts.size) return -1
                current = ByteArrayInputStream(decryptPart(parts[nextPart], nextPart++))
            }
            return current.read(target, offset, length)
        }
        private fun decryptPart(file: DocumentFile, expectedIndex: Int): ByteArray {
            val bytes = readDriveBytes(ctx, file)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                require(magic.contentEquals(MAGIC) && input.readInt() == FORMAT) { "Invalid backup part" }
                require(input.readInt() == expectedIndex) { "Backup parts are out of order" }
                val iv = ByteArray(input.readInt()).also { input.readFully(it) }
                val plainLength = input.readInt()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad(appTag, expectedIndex))
                return cipher.doFinal(input.readBytes()).also {
                    require(it.size == plainLength); digest.update(it)
                }
            }
        }
    }

    private const val DRIVE_READ_ATTEMPTS = 8
    private const val DRIVE_READ_MAX_DELAY_MS = 10_000L
}
