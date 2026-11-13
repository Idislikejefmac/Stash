package app.stash

import app.stash.speech.ImportResult
import app.stash.speech.KnownModels
import app.stash.speech.ModelImporter
import app.stash.speech.ModelLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Card 26d: imports a REAL pack zip made by tools/make_model_pack.py with the real KnownModels hashes, on the PC.
 * Skipped unless the environment variable STASH_REAL_PACK_ZIP names such a zip (141 MB is not kept in the repository).
 */
class RealPackImportTest {
    @Test fun aRealPackMadeByTheToolImportsAndIsFound() {
        val path = System.getenv("STASH_REAL_PACK_ZIP")
        assumeTrue("set STASH_REAL_PACK_ZIP to run", path != null && File(path).isFile)
        val root = Files.createTempDirectory("realpack").toFile()
        try {
            val r = File(path!!).inputStream().use { ModelImporter(File(root, "models")).import(it) }
            assertTrue(r.toString(), r is ImportResult.Installed)
            val found = ModelLocator(File(root, "models")).find()!!
            assertTrue(found.pack in KnownModels.ALL)
            assertEquals(found.pack.totalBytes, (r as ImportResult.Installed).bytes)
            println("REAL PACK ${found.pack.id} ${r.bytes} bytes in ${r.ms} ms")
        } finally {
            root.deleteRecursively()
        }
    }
}
