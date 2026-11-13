package app.stash.speech

import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import java.io.File

/*
 * Card 26d, section C: speech model packs. A pack is a folder of files that is put on the phone once (never
 * downloaded, not in the APK). Only the packs listed in [KnownModels] can be installed or loaded: that is the
 * integrity check and the licence check in one place (a non-English Moonshine file, which is under a
 * non-commercial licence, cannot be loaded by accident). Pure Kotlin: java.io only, no Android import.
 */

/** One file of a pack, with the size and sha256 it must have (docs/LICENSES.md, card 25 downloads). */
data class PackFile(val name: String, val bytes: Long, val sha256: String)

data class KnownPack(
    val id: String,
    /** As shown in Settings: "Speech model: Moonshine base, 141 MB". */
    val displayName: String,
    /** The licence line shown under it in Settings. */
    val licenceLine: String,
    val encoder: PackFile,
    val decoder: PackFile,
    val tokens: PackFile,
    /** The package's own LICENSE file: it travels in each pack too (D78). */
    val licence: PackFile,
    /**
     * Memory the model needs to load and write out a short note, measured in card 25 on the S25 Edge (peak PSS for
     * notes up to 8 s, less the app's idle figure), rounded up. A guide for choosing between installed packs, not a promise.
     */
    val workingSetBytes: Long,
) {
    val files: List<PackFile> get() = listOf(encoder, decoder, tokens, licence)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val megabytes: Long get() = (totalBytes + 500_000) / 1_000_000
}

object KnownModels {
    private const val MB = 1_000_000L

    /** Both Moonshine packages carry the same tokens.txt and the same LICENSE file (sha256 computed 2026-11-12). */
    private val TOKENS = PackFile("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049")
    private val LICENSE = PackFile("LICENSE", 13_344, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd")
    private const val MOONSHINE_LICENCE = "MIT licence, Moonshine AI (English model)"

    val BASE = KnownPack(
        id = "moonshine-base-en-2026-02-27",
        displayName = "Moonshine base",
        licenceLine = MOONSHINE_LICENCE,
        encoder = PackFile("encoder_model.ort", 31_326_816, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
        decoder = PackFile("decoder_model_merged.ort", 109_424_400, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
        tokens = TOKENS,
        licence = LICENSE,
        workingSetBytes = 330 * MB, // 351 MB peak, about 26 MB of it the idle app
    )

    val TINY = KnownPack(
        id = "moonshine-tiny-en-2026-02-27",
        displayName = "Moonshine tiny",
        licenceLine = MOONSHINE_LICENCE,
        encoder = PackFile("encoder_model.ort", 13_281_600, "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"),
        decoder = PackFile("decoder_model_merged.ort", 30_412_256, "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"),
        tokens = TOKENS,
        licence = LICENSE,
        workingSetBytes = 220 * MB, // 243 MB peak
    )

    /** In order of preference: base before tiny (D82). */
    val ALL: List<KnownPack> = listOf(BASE, TINY)

    fun byId(id: String): KnownPack? = ALL.firstOrNull { it.id == id }
}

enum class PackLocation { FILES, ASSETS }

/** A pack that is on the phone and verified. [path] gives what the engine opens: a file path, or an asset path. */
data class InstalledPack(val pack: KnownPack, val location: PackLocation, val base: String) {
    fun path(file: PackFile): String = "$base/${file.name}"
}

/**
 * The marker written into a pack's folder after every file was verified. Its presence (with the right id, and each
 * file at its verified size) is what makes a pack "installed"; hashes are checked once, at import, not at every load.
 */
object PackMarker {
    const val NAME = ".verified"

    fun text(pack: KnownPack): String = buildString {
        append("pack=").append(pack.id).append('\n')
        for (f in pack.files) append(f.name).append(' ').append(f.bytes).append(' ').append(f.sha256).append('\n')
    }

    fun matches(text: String, pack: KnownPack): Boolean = text.lineSequence().firstOrNull()?.trim() == "pack=${pack.id}"
}

/** Lists one folder of the APK's assets (null or empty: not there). The app passes `AssetManager.list`. */
fun interface AssetLister {
    fun list(path: String): List<String>?
}

/**
 * Finds installed packs: first `files/models/<pack id>/`, then APK assets `models/<pack id>/` (so bundling a model
 * stays a one-line build choice; nothing puts one there today). Base before tiny.
 */
class ModelLocator(
    private val root: File,
    private val assets: AssetLister? = null,
    private val known: List<KnownPack> = KnownModels.ALL,
) {
    /** Every installed pack, best first. */
    fun installed(): List<InstalledPack> = known.mapNotNull { inFiles(it) ?: inAssets(it) }

    /** The best installed pack, or null. */
    fun find(): InstalledPack? = installed().firstOrNull()

    /** The pack's place in the list of known packs (0 is the best); events carry it instead of a name. */
    fun rank(p: KnownPack): Int = known.indexOf(p)

    /** Packs that were verified once but whose files are now missing or the wrong size. */
    fun broken(): List<KnownPack> = known.filter { p ->
        val dir = File(root, p.id)
        File(dir, PackMarker.NAME).isFile && inFiles(p) == null
    }

    private fun inFiles(p: KnownPack): InstalledPack? {
        val dir = File(root, p.id)
        val marker = File(dir, PackMarker.NAME)
        if (!marker.isFile) return null
        val ok = runCatching { PackMarker.matches(marker.readText(), p) }.getOrDefault(false) &&
            p.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.bytes } }
        return if (ok) InstalledPack(p, PackLocation.FILES, dir.absolutePath) else null
    }

    private fun inAssets(p: KnownPack): InstalledPack? {
        val lister = assets ?: return null
        val dir = "$ASSET_DIR/${p.id}"
        val names = runCatching { lister.list(dir) }.getOrNull()?.toSet() ?: return null
        if (PackMarker.NAME !in names || p.files.any { it.name !in names }) return null
        return InstalledPack(p, PackLocation.ASSETS, dir)
    }

    companion object {
        /** Under the app's files folder; excluded from backup and device transfer (data_extraction_rules.xml). */
        const val DIR = "models"
        const val ASSET_DIR = "models"
    }
}

/**
 * Which installed pack to load, given the memory free right now. The best pack whose [KnownPack.workingSetBytes]
 * fits; base before tiny. Null when none fits: the caller says "not enough memory" and tries again later rather
 * than risk the process being killed. With [availableBytes] unknown, the best pack.
 */
object PackChoice {
    fun choose(installed: List<InstalledPack>, availableBytes: Long?): InstalledPack? {
        if (availableBytes == null) return installed.firstOrNull()
        return installed.firstOrNull { it.pack.workingSetBytes <= availableBytes }
    }

    /** What [choose] picked under a [PackPolicy]. [longNoteWithoutItsPack]: the note wanted the long-note pack, which is not installed. */
    data class Choice(val pack: InstalledPack, val longNoteWithoutItsPack: Boolean)

    /**
     * Card 26e: the same choice, with the installed packs put in the policy's order first, and (outcome M2) a note
     * longer than the policy's limit given the long-note pack first if it is installed. Memory still decides.
     */
    fun choose(installed: List<InstalledPack>, availableBytes: Long?, noteMs: Long?, policy: PackPolicy): Choice? {
        val ordered = policy.ordered(installed)
        val wanted = policy.longNotePack(noteMs)
        val forNote = wanted?.let { id -> ordered.firstOrNull { it.pack.id == id } }
        val list = if (forNote != null) listOf(forNote) + (ordered - forNote) else ordered
        val pick = choose(list, availableBytes) ?: return null
        return Choice(pick, longNoteWithoutItsPack = wanted != null && forNote == null)
    }
}

/**
 * Card 26e item 5: which pack the app prefers. [order] lists pack ids, best first (installed packs not named keep
 * their own order, after these). Outcome M2: a note longer than [longNoteFromMs] is written out with
 * [longNotePackId] when it is installed. The values in use are [DefaultPack.POLICY].
 */
data class PackPolicy(
    val order: List<String>,
    val longNoteFromMs: Long? = null,
    val longNotePackId: String? = null,
) {
    fun ordered(installed: List<InstalledPack>): List<InstalledPack> =
        installed.withIndex().sortedWith(compareBy({ order.indexOf(it.value.pack.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }

    /** The id of the pack a note of [noteMs] must use, or null when any pack will do. */
    fun longNotePack(noteMs: Long?): String? {
        val limit = longNoteFromMs ?: return null
        return if (noteMs != null && noteMs > limit) longNotePackId else null
    }
}

/** Where packs live, and removing one. "Remove" in Settings deletes the folder. */
class ModelPacks(val root: File, private val known: List<KnownPack> = KnownModels.ALL) {
    fun remove(id: String): Boolean {
        val pack = known.firstOrNull { it.id == id } ?: return false
        val dir = File(root, pack.id)
        if (!dir.exists()) return false
        val ok = dir.deleteRecursively()
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.REMOVED, n1 = known.indexOf(pack), n2 = if (ok) 1 else 0)
        return ok
    }
}
