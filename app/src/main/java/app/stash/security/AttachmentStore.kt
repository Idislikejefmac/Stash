package app.stash.security

import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * A file that cannot be read back: missing, not ours, an unknown version, or failing authentication (wrong key,
 * a changed or missing byte, or a file moved onto another attachment's row). The message is the reason's name and
 * nothing else: never any of the file's content.
 */
class AttachmentUnreadable(val reason: Reason) : IOException("attachment unreadable: ${reason.name}") {
    /** [code] is what goes into `Dbg.event` (numbers only). */
    enum class Reason(val code: Int) {
        MISSING(1), NOT_A_STASH_FILE(2), UNKNOWN_VERSION(3), CORRUPT(4), NO_ROW(5), BAD_KEY(6),
        /** Card 26c: a recording asked for through the picture door, or a picture through the recording door. */
        WRONG_KIND(7),
    }
}

/**
 * The encrypted file store for attachments (docs/cards/16a section C). It moves bytes and knows nothing about the
 * database or about pictures. Every file is encrypted under its own random 32-byte key; nothing decrypted is ever
 * written to disk: callers pass and receive streams.
 *
 * File format, version 1: `STSH`, one version byte `0x01`, then Tink's streaming AEAD (AES-256-GCM with HKDF-SHA-256,
 * 4,096-byte ciphertext segments) keyed by the row's `file_key`. The associated data is the five header bytes and the
 * attachment id (UTF-8), so a file copied or renamed onto another row does not open.
 *
 * `open` so a test can make [delete] fail.
 */
open class AttachmentStore(private val dir: File) {
    data class Written(val byteSize: Long, val sha256: String)

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun newFileName(): String = UUID.randomUUID().toString()

    /**
     * Encrypt [plain] into `<fileName>.tmp`, sync it, then rename it to [fileName]. On any failure the `.tmp` is
     * deleted and the exception rethrown. Refuses to replace an existing file. Does not close [plain].
     */
    open fun write(fileName: String, key: ByteArray, attachmentId: String, plain: InputStream): Written {
        checkName(fileName)
        checkKey(key)
        dir.mkdirs()
        val target = File(dir, fileName)
        if (target.exists()) throw IOException("attachment file exists")
        val tmp = File(dir, fileName + TMP)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(tmp).use { fos ->
                fos.write(HEADER)
                // Tink's stream closes what it wraps on close(); keep the file open so it can be synced afterwards.
                aead(key).newEncryptingStream(KeepOpen(fos), aad(attachmentId)).use { enc ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = plain.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buf, 0, n)
                        enc.write(buf, 0, n)
                        size += n
                    }
                }
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("attachment rename failed")
            return Written(size, hex(digest.digest()))
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * A decrypting stream over [fileName]. Throws [AttachmentUnreadable] here when the file is missing, not ours, of
     * an unknown version, or fails on its first segment; a failure further in is thrown from `read` as the same type.
     */
    open fun open(fileName: String, key: ByteArray, attachmentId: String): InputStream {
        if (!validName(fileName)) throw AttachmentUnreadable(AttachmentUnreadable.Reason.MISSING)
        if (key.size != KEY_BYTES) throw AttachmentUnreadable(AttachmentUnreadable.Reason.BAD_KEY)
        val file = File(dir, fileName)
        if (!file.isFile) throw AttachmentUnreadable(AttachmentUnreadable.Reason.MISSING)
        val fis = try {
            FileInputStream(file)
        } catch (e: IOException) {
            throw AttachmentUnreadable(AttachmentUnreadable.Reason.MISSING)
        }
        try {
            val head = fis.readNBytes(HEADER.size)
            if (head.size < HEADER.size || !head.copyOf(MAGIC.size).contentEquals(MAGIC)) {
                throw AttachmentUnreadable(AttachmentUnreadable.Reason.NOT_A_STASH_FILE)
            }
            if (head[MAGIC.size] != VERSION_1) throw AttachmentUnreadable(AttachmentUnreadable.Reason.UNKNOWN_VERSION)
            val decrypting = Guarded(aead(key).newDecryptingStream(fis, aad(attachmentId)))
            // Read one byte now, so a wrong key or a damaged first segment fails at open, not later in a screen.
            val stream = PushbackInputStream(decrypting, 1)
            val first = stream.read()
            if (first >= 0) stream.unread(first)
            return stream
        } catch (e: AttachmentUnreadable) {
            fis.close()
            throw e
        } catch (e: Exception) {
            fis.close()
            throw AttachmentUnreadable(AttachmentUnreadable.Reason.CORRUPT)
        }
    }

    open fun delete(fileName: String): Boolean = validName(fileName) && File(dir, fileName).delete()

    /** Finished files (no `.tmp`). */
    fun list(): Set<String> = names().filter { !it.endsWith(TMP) }.toSet()

    /** Unfinished writes: `<name>.tmp`. Only the sweep looks at these. */
    fun temps(): Set<String> = names().filter { it.endsWith(TMP) }.toSet()

    /** How long ago [fileName] (finished or `.tmp`) was last written, against [now]. */
    fun ageMs(fileName: String, now: Long = System.currentTimeMillis()): Long = now - File(dir, fileName).lastModified()

    /** Delete a leftover `<name>.tmp` (the sweep). */
    fun deleteTemp(tmpName: String): Boolean = tmpName.endsWith(TMP) && validName(tmpName.removeSuffix(TMP)) && File(dir, tmpName).delete()

    private fun names(): List<String> = dir.list()?.toList().orEmpty()

    private fun aead(key: ByteArray): StreamingAead =
        try {
            AesGcmHkdfStreaming(key, "HmacSha256", KEY_BYTES, SEGMENT_BYTES, 0)
        } catch (e: java.security.GeneralSecurityException) {
            throw AttachmentUnreadable(AttachmentUnreadable.Reason.BAD_KEY)
        }

    private fun aad(attachmentId: String): ByteArray = HEADER + attachmentId.toByteArray(Charsets.UTF_8)

    private fun checkName(name: String) = require(validName(name)) { "bad attachment file name" }

    private fun checkKey(key: ByteArray) = require(key.size == KEY_BYTES) { "attachment key must be $KEY_BYTES bytes" }

    /** Turns any failure of the decrypting stream into [AttachmentUnreadable], with no content in the message. */
    private class Guarded(inner: InputStream) : FilterInputStream(inner) {
        override fun read(): Int = guard { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { `in`.read(b, off, len) }
        override fun skip(n: Long): Long = guard { super.skip(n) }

        private inline fun <T> guard(f: () -> T): T = try {
            f()
        } catch (e: AttachmentUnreadable) {
            throw e
        } catch (e: Exception) {
            throw AttachmentUnreadable(AttachmentUnreadable.Reason.CORRUPT)
        }
    }

    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    companion object {
        /** The folder under `filesDir`. Excluded from cloud backup and device transfer (res/xml/data_extraction_rules.xml). */
        const val DIR = "att"
        const val FORMAT_VERSION = 1
        const val KEY_BYTES = 32
        const val SEGMENT_BYTES = 4096
        private const val TMP = ".tmp"
        private const val BUFFER = 64 * 1024
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte())
        private const val VERSION_1: Byte = 0x01
        private val HEADER = MAGIC + VERSION_1
        private val NAME = Regex("[A-Za-z0-9-]{1,64}")
        private val random = SecureRandom()

        fun validName(name: String): Boolean = NAME.matches(name)

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
