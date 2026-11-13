package app.stash.speech

import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/*
 * Card 26d item 9: puts a speech model pack on the phone from a zip made by `tools/make_model_pack.py`.
 * The zip holds `<pack id>/<file>` entries, stored (not compressed), for exactly the files [KnownModels] lists.
 * No network: the zip comes from the system file picker or (debug builds) from a file copied in with adb.
 * Pure Kotlin: java.io and java.util.zip only.
 */

sealed interface ImportResult {
    data class Installed(val pack: KnownPack, val bytes: Long, val ms: Long) : ImportResult
    /** [file] names the pack file that was wrong or missing, when there is one. Never the name of an unknown entry. */
    data class Failed(val reason: ImportFailure, val file: String? = null) : ImportResult
}

/** [code] is what events carry (n1). */
enum class ImportFailure(val code: Int) {
    NO_SPACE(1),
    NOT_A_ZIP(2),
    /** The zip holds no file of any known pack. */
    NOT_A_PACK(3),
    /** An entry whose name is not exactly one of a known pack's files (including any path such as `../x`). */
    UNKNOWN_ENTRY(4),
    /** Files of two different packs in one zip. */
    MIXED_PACKS(5),
    DUPLICATE_ENTRY(6),
    /** A file whose size or sha256 differs from the one verified for that pack. */
    WRONG_FILE(7),
    MISSING_FILE(8),
    IO_ERROR(9),
}

class ModelImporter(
    private val root: File,
    private val known: List<KnownPack> = KnownModels.ALL,
    /** Free bytes where [root] is. The app passes `File.usableSpace`. */
    private val freeBytes: () -> Long = { root.apply { mkdirs() }.usableSpace },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Card 26f: called after a pack is installed, so notes waiting for a model are written out. Never fails the import. */
    private val onInstalled: () -> Unit = {},
) {
    /** Exact entry name to its pack and file. Anything not in this map is refused, so no name can reach outside [root]. */
    private val allowed: Map<String, Pair<KnownPack, PackFile>> =
        known.flatMap { p -> p.files.map { f -> "${p.id}/${f.name}" to (p to f) } }.toMap()
    private val allowedDirs: Map<String, KnownPack> = known.associateBy { "${it.id}/" }

    /** Reads the whole zip; installs the pack only if every file matches. [progress] gets (bytes written, pack size). */
    fun import(zip: InputStream, progress: (Long, Long) -> Unit = { _, _ -> }): ImportResult {
        val r = importChecked(zip, progress)
        when (r) {
            is ImportResult.Installed -> Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.STORED, n1 = r.bytes, n2 = known.indexOf(r.pack), ms = r.ms)
            is ImportResult.Failed -> Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.REFUSED, n1 = r.reason.code)
        }
        if (r is ImportResult.Installed) runCatching { onInstalled() }
        return r
    }

    private fun importChecked(zip: InputStream, progress: (Long, Long) -> Unit): ImportResult {
        val t0 = nowMs()
        root.mkdirs()
        cleanLeftovers()
        if (freeBytes() < NEEDED_FREE_BYTES) return ImportResult.Failed(ImportFailure.NO_SPACE)

        var pack: KnownPack? = null
        var tmp: File? = null
        val seen = HashSet<String>()
        var written = 0L
        try {
            ZipInputStream(zip.buffered()).use { z ->
                var anyEntry = false
                while (true) {
                    val e = z.nextEntry ?: break
                    anyEntry = true
                    val name = e.name
                    val dirPack = allowedDirs[name]
                    if (dirPack != null) {
                        if (pack != null && pack != dirPack) return fail(tmp, ImportFailure.MIXED_PACKS)
                        continue
                    }
                    val (p, f) = allowed[name] ?: return fail(tmp, ImportFailure.UNKNOWN_ENTRY)
                    if (pack == null) {
                        pack = p
                        tmp = File(root, p.id + TMP).also { it.deleteRecursively(); it.mkdirs() }
                    } else if (pack != p) {
                        return fail(tmp, ImportFailure.MIXED_PACKS)
                    }
                    if (!seen.add(f.name)) return fail(tmp, ImportFailure.DUPLICATE_ENTRY, f.name)
                    val ok = copyChecked(z, File(tmp!!, f.name), f) { n ->
                        written += n
                        progress(written, p.totalBytes)
                    }
                    if (!ok) return fail(tmp, ImportFailure.WRONG_FILE, f.name)
                }
                if (!anyEntry) return fail(tmp, ImportFailure.NOT_A_ZIP)
            }
        } catch (e: ZipException) {
            return fail(tmp, if (pack == null) ImportFailure.NOT_A_ZIP else ImportFailure.IO_ERROR)
        } catch (e: IOException) {
            return fail(tmp, ImportFailure.IO_ERROR)
        }
        val p = pack ?: return fail(tmp, ImportFailure.NOT_A_PACK)
        p.files.firstOrNull { it.name !in seen }?.let { return fail(tmp, ImportFailure.MISSING_FILE, it.name) }
        return if (install(p, tmp!!)) ImportResult.Installed(p, written, nowMs() - t0) else fail(tmp, ImportFailure.IO_ERROR)
    }

    /** Streams one entry to [out] while hashing; false (and nothing kept) if it is longer, shorter, or hashes differently. */
    private fun copyChecked(input: InputStream, out: File, expected: PackFile, counted: (Long) -> Unit): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(1 shl 16)
        var sinceReport = 0L
        out.outputStream().use { o ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > expected.bytes) return false
                md.update(buf, 0, n)
                o.write(buf, 0, n)
                sinceReport += n
                if (sinceReport >= REPORT_EVERY) { counted(sinceReport); sinceReport = 0 }
            }
            o.fd.sync()
        }
        if (sinceReport > 0) counted(sinceReport)
        return total == expected.bytes && hex(md.digest()) == expected.sha256
    }

    /** Swaps the verified folder in (replacing an older copy of the same pack), then writes the marker. */
    private fun install(p: KnownPack, tmp: File): Boolean {
        val final = File(root, p.id)
        val old = File(root, p.id + OLD)
        old.deleteRecursively()
        if (final.exists() && !final.renameTo(old)) return false
        if (!tmp.renameTo(final)) {
            old.renameTo(final)
            return false
        }
        return try {
            val m = File(final, PackMarker.NAME + TMP)
            m.writeText(PackMarker.text(p))
            if (!m.renameTo(File(final, PackMarker.NAME))) error("marker")
            old.deleteRecursively()
            true
        } catch (e: Exception) {
            final.deleteRecursively()
            old.deleteRecursively()
            false
        }
    }

    private fun fail(tmp: File?, reason: ImportFailure, file: String? = null): ImportResult.Failed {
        tmp?.deleteRecursively()
        return ImportResult.Failed(reason, file)
    }

    /** A crash during an import can leave `<id>.tmp` or `<id>.old` behind; neither is ever loaded. */
    private fun cleanLeftovers() {
        root.listFiles()?.filter { it.name.endsWith(TMP) || it.name.endsWith(OLD) }?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** Card 26d item 9: "Needs 300 MB free; says so if not." */
        const val NEEDED_FREE_BYTES = 300L * 1_000_000
        private const val REPORT_EVERY = 1L shl 20
        const val TMP = ".tmp"
        const val OLD = ".old"

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}

/** What Settings says about an import. Plain words; a file name only when it is one of the pack's own. */
object ImportWords {
    fun message(r: ImportResult): String = when (r) {
        is ImportResult.Installed -> "Speech model added: ${r.pack.displayName}, ${r.pack.megabytes} MB."
        is ImportResult.Failed -> when (r.reason) {
            ImportFailure.NO_SPACE -> "Not added: the phone needs 300 MB free to add the speech model."
            ImportFailure.NOT_A_ZIP -> "Not added: that file is not a speech model pack."
            ImportFailure.NOT_A_PACK -> "Not added: that file holds no speech model Stash knows."
            ImportFailure.UNKNOWN_ENTRY -> "Not added: that file holds something that is not part of a speech model."
            ImportFailure.MIXED_PACKS -> "Not added: that file mixes two speech models."
            ImportFailure.DUPLICATE_ENTRY -> "Not added: the file ${r.file} is in it twice."
            ImportFailure.WRONG_FILE -> "Not added: the file ${r.file} is not the one Stash expects. Nothing was changed."
            ImportFailure.MISSING_FILE -> "Not added: the file ${r.file} is missing from it."
            ImportFailure.IO_ERROR -> "Not added: the file could not be read or written. Nothing was changed."
        }
    }

    /** "Speech model: Moonshine base, 141 MB". */
    fun installedLine(p: KnownPack): String = "Speech model: ${p.displayName}, ${p.megabytes} MB"
}
