package app.stash

import app.stash.security.AttachmentStore
import app.stash.security.AttachmentUnreadable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Random

/** The encrypted file store (docs/cards/16a section C), plain JVM: Tink's streaming AEAD with the row's raw 32-byte key. */
class AttachmentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir by lazy { tmp.newFolder("att") }
    private val store by lazy { AttachmentStore(dir) }
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val id = "0b6f3c7e-1111-4a2b-9c3d-000000000001"

    private fun bytes(n: Int, seed: Long = n.toLong()) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun roundTrip(n: Int) {
        val plain = bytes(n)
        val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(plain))
        assertEquals(n.toLong(), w.byteSize)
        assertEquals(sha(plain), w.sha256)
        val back = store.open(name, key, id).use { it.readBytes() }
        assertArrayEquals("size $n", plain, back)
        // the file on disk is not the plain bytes and starts with the header
        val raw = File(dir, name).readBytes()
        assertEquals("STSH", String(raw.copyOf(4), Charsets.US_ASCII)); assertEquals(1, raw[4].toInt())
        if (n >= 16) assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(plain.copyOf(16), Charsets.ISO_8859_1)))
    }

    @Test fun bytesReadBackIdenticalAtEverySize() {
        for (n in listOf(0, 1, 4096, 300 * 1024, 3 * 1024 * 1024)) roundTrip(n)
    }

    @Test fun keysAndNamesAreRandomAndTheRightSize() {
        val a = store.newKey(); val b = store.newKey()
        assertEquals(32, a.size); assertFalse(a.contentEquals(b))
        assertTrue(AttachmentStore.validName(store.newFileName()))
        assertFalse(store.newFileName() == store.newFileName())
    }

    private fun written(n: Int = 10_000): String {
        val name = store.newFileName()
        store.write(name, key, id, ByteArrayInputStream(bytes(n)))
        return name
    }

    /** Opening and reading to the end must fail, and only ever with [AttachmentUnreadable]. */
    private fun assertUnreadable(expected: AttachmentUnreadable.Reason?, f: () -> InputStream) {
        try {
            f().use { it.readBytes() }
            fail("expected AttachmentUnreadable")
        } catch (e: AttachmentUnreadable) {
            if (expected != null) assertEquals(expected, e.reason)
            assertEquals("attachment unreadable: ${e.reason.name}", e.message)
        } catch (e: Throwable) {
            fail("expected AttachmentUnreadable, got ${e.javaClass.name}")
        }
    }

    @Test fun aFlippedByteFails() {
        // one byte in the first segment, one in the last, and one in Tink's own header
        for (pos in listOf(5 + 40, 9_000, 7)) {
            val name = written()
            val f = File(dir, name)
            val raw = f.readBytes(); raw[pos] = (raw[pos].toInt() xor 0x01).toByte(); f.writeBytes(raw)
            assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(name, key, id) }
        }
    }

    @Test fun aWrongKeyFails() {
        val name = written()
        val other = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(name, other, id) }
        assertUnreadable(AttachmentUnreadable.Reason.BAD_KEY) { store.open(name, ByteArray(16), id) }
    }

    @Test fun theRightKeyWithAnotherAttachmentIdFails() {
        val name = written()
        assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(name, key, "0b6f3c7e-1111-4a2b-9c3d-000000000002") }
    }

    @Test fun aTruncatedFileFails() {
        for (cut in listOf(1, 16, 5_000)) {
            val name = written(10_000)
            val f = File(dir, name)
            f.writeBytes(f.readBytes().let { it.copyOf(it.size - cut) })
            assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(name, key, id) }
        }
        // cut down to the header only, and to nothing
        val name = written()
        File(dir, name).writeBytes(File(dir, name).readBytes().copyOf(5))
        assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(name, key, id) }
        File(dir, name).writeBytes(ByteArray(0))
        assertUnreadable(AttachmentUnreadable.Reason.NOT_A_STASH_FILE) { store.open(name, key, id) }
        // an empty plain file cut at the end fails too
        val empty = store.newFileName()
        store.write(empty, key, id, ByteArrayInputStream(ByteArray(0)))
        File(dir, empty).writeBytes(File(dir, empty).readBytes().let { it.copyOf(it.size - 1) })
        assertUnreadable(AttachmentUnreadable.Reason.CORRUPT) { store.open(empty, key, id) }
    }

    @Test fun anUnknownVersionByteFails() {
        val name = written()
        val f = File(dir, name)
        val raw = f.readBytes(); raw[4] = 2; f.writeBytes(raw)
        assertUnreadable(AttachmentUnreadable.Reason.UNKNOWN_VERSION) { store.open(name, key, id) }
        raw[4] = 1; raw[0] = 'X'.code.toByte(); f.writeBytes(raw)
        assertUnreadable(AttachmentUnreadable.Reason.NOT_A_STASH_FILE) { store.open(name, key, id) }
    }

    @Test fun aMissingFileFails() {
        assertUnreadable(AttachmentUnreadable.Reason.MISSING) { store.open(store.newFileName(), key, id) }
        assertUnreadable(AttachmentUnreadable.Reason.MISSING) { store.open("../escape", key, id) }
    }

    @Test fun aWriteWhoseInputThrowsHalfWayLeavesNeitherTheFileNorTheTmp() {
        val name = store.newFileName()
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100_000) 7 else throw IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= 100_000) throw IOException("gone")
                val k = minOf(len, 100_000 - n).coerceAtLeast(1)
                for (i in 0 until k) b[off + i] = 7
                n += k
                return k
            }
        }
        try {
            store.write(name, key, id, failing)
            fail("expected the write to throw")
        } catch (e: IOException) {
            assertEquals("gone", e.message)
        }
        assertFalse(File(dir, name).exists()); assertFalse(File(dir, "$name.tmp").exists())
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun anExistingFileIsNeverReplacedAndBadNamesAreRefused() {
        val name = written()
        val before = File(dir, name).readBytes()
        try { store.write(name, key, id, ByteArrayInputStream(bytes(5))); fail() } catch (e: IOException) { }
        assertArrayEquals(before, File(dir, name).readBytes())
        try { store.write("../x", key, id, ByteArrayInputStream(bytes(5))); fail() } catch (e: IllegalArgumentException) { }
        try { store.write(store.newFileName(), ByteArray(31), id, ByteArrayInputStream(bytes(5))); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun listTempsAgeAndDelete() {
        val a = written(); val b = written()
        File(dir, "leftover.tmp").writeBytes(ByteArray(3))
        assertEquals(setOf(a, b), store.list())
        assertEquals(setOf("leftover.tmp"), store.temps())
        val f = File(dir, a); f.setLastModified(1_000_000_000_000L)
        assertEquals(1_000L, store.ageMs(a, 1_000_000_001_000L))
        assertTrue(store.delete(a)); assertFalse(store.delete(a))
        assertTrue(store.deleteTemp("leftover.tmp"))
        assertEquals(setOf(b), store.list()); assertTrue(store.temps().isEmpty())
    }
}
