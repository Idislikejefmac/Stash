package app.stash

import app.stash.TestSupport.rows
import app.stash.capture.DirectSink
import app.stash.capture.Inbound
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Ids
import app.stash.data.RevisionKind
import app.stash.data.StashDatabase
import app.stash.data.ViolationKind
import app.stash.debug.AttachmentSelfTest
import app.stash.debug.DumpReceiver
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.security.AttachmentStore
import app.stash.security.AttachmentUnreadable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.Random

/** Card 16a with real Room and real files: many per note, the one way in, remove, purge, the sweep, the check, the dump. */
@RunWith(RobolectricTestRunner::class)
class AttachmentTest {
    /** A store whose delete fails a set number of times, as a full disk or a held file would. */
    private class FlakyStore(dir: File) : AttachmentStore(dir) {
        var failures = 0
        override fun delete(fileName: String): Boolean = if (failures > 0) { failures--; false } else super.delete(fileName)
    }

    private lateinit var db: StashDatabase
    private lateinit var dir: File
    private lateinit var store: FlakyStore
    // Files are stamped by the real file system, so the clock starts at the real time.
    private val clock = FakeClock(System.currentTimeMillis())
    private val repo by lazy { TestSupport.repo(db, clock, store) }
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Long?>>()

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        dir = TestSupport.tempDir()
        store = FlakyStore(dir)
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, _, _, _ -> synchronized(events) { events += Triple(c, code, n1) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
        dir.deleteRecursively()
    }

    private val keys = mutableMapOf<String, ByteArray>()
    private val plains = mutableMapOf<String, ByteArray>()

    /** A file written to the store first, as card 16b's preparer will hand it over. */
    private fun file(n: Int = 2_000, seed: Long = Random().nextLong(), kind: AttachmentKind = AttachmentKind.PHOTO): InboundAttachment {
        val plain = ByteArray(n).also { Random(seed).nextBytes(it) }
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(plain))
        keys[id] = key; plains[id] = plain
        val mime = if (kind == AttachmentKind.AUDIO) "audio/ogg" else "image/webp"
        val (wd, ht) = if (kind == AttachmentKind.AUDIO) null to null else 1600 to 1200
        return InboundAttachment(
            id, kind, AttachmentSource.SHARE, mime, name, key, w.byteSize, w.sha256, wd, ht,
            durationMs = if (kind == AttachmentKind.AUDIO) 4_000L else null,
        )
    }

    private suspend fun note(vararg files: InboundAttachment, text: String = "a bottle at Total Wine") =
        repo.captures.insertCapture(rawText = text, text = text, source = CaptureSource.SHARED, attachments = files.toList())

    private fun raw() = db.openHelper.writableDatabase

    private suspend fun positions(captureId: String) = repo.attachments.forCapture(captureId).map { it.position }

    // ---------- many per note ----------

    @Test fun manyPerNoteKeepTheirOrderAndGapsAndTheSameBytesTwiceGiveOneRow() = runBlocking {
        val first = listOf(file(seed = 1), file(seed = 2), file(seed = 3))
        val c = note(*first.toTypedArray())
        assertEquals(listOf(0, 1, 2), positions(c.id))
        assertEquals(first.map { it.id }, repo.attachments.forCapture(c.id).map { it.id })

        val more = listOf(file(seed = 4), file(seed = 5))
        val sink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
        clock.advance(5_000)
        val r = sink.accept(Inbound.AddAttachments(c.id, more))
        assertTrue(r is InboundSink.Result.Saved)
        assertEquals(listOf(0, 1, 2, 3, 4), positions(c.id))
        // one PICTURE_ADDED revision per added file; the capture is stamped
        val added = db.revisions().forCapture(c.id).filter { it.what == RevisionKind.PICTURE_ADDED }
        assertEquals(more.map { it.id }.toSet(), added.map { it.newValue }.toSet()); assertTrue(added.all { it.oldValue == "" })
        assertEquals(clock.now(), repo.captures.get(c.id)!!.updatedAt)

        // removing position 1 leaves a gap; nothing is renumbered
        assertTrue(repo.attachments.remove(first[1].id))
        assertEquals(listOf(0, 2, 3, 4), positions(c.id))
        assertEquals(first[0].id, repo.attachments.cover(c.id)!!.id)

        // the same bytes again (the same screenshot shared twice) add nothing and return the same note
        val dup = file(seed = 4)
        val before = db.revisions().forCapture(c.id).size
        assertTrue(sink.accept(Inbound.AddAttachments(c.id, listOf(dup))) is InboundSink.Result.Saved)
        assertEquals(listOf(0, 2, 3, 4), positions(c.id))
        assertEquals(before, db.revisions().forCapture(c.id).size)
        // and within one hand-over, too
        val twice = file(seed = 9)
        val c2 = note(twice, file(seed = 9))
        assertEquals(listOf(twice.id), repo.attachments.forCapture(c2.id).map { it.id })
        // every picture reads back as written
        for (a in repo.attachments.forCapture(c.id)) {
            assertArrayEquals(plains.getValue(a.id), repo.attachments.openBytes(a.id).use { it.readBytes() })
        }
        assertTrue(repo.consistency.run().filter { it.kind != ViolationKind.FILE_WITHOUT_ATTACHMENT }.isEmpty())
    }

    @Test fun aNoteWithNoWordsNeedsAFile() = runBlocking {
        try {
            repo.captures.insertCapture(rawText = "", text = "")
            fail("a note with no words and no file was saved")
        } catch (e: IllegalArgumentException) { }
        assertTrue(repo.captures.live().isEmpty())
        val sink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
        val refused = sink.accept(Inbound.Capture.now("", "", CaptureSource.SHARED, false, null, "test", clock))
        assertTrue(refused is InboundSink.Result.Failed)
        val pic = file()
        val saved = sink.accept(Inbound.Capture.now("", "", CaptureSource.SHARED, false, null, "test", clock, attachments = listOf(pic)))
        assertTrue(saved is InboundSink.Result.Saved)
        val c = (saved as InboundSink.Result.Saved).capture
        assertEquals("", c.text)
        assertEquals(listOf(pic.id), repo.attachments.pictures(c.id).map { it.id })
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun addingToANoteThatIsGoneOrInTheBinFails() = runBlocking {
        val sink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
        val pic = file()
        assertTrue(sink.accept(Inbound.AddAttachments("no-such-note", listOf(pic))) is InboundSink.Result.Failed)
        val c = note()
        repo.captures.softDelete(c.id)
        assertTrue(sink.accept(Inbound.AddAttachments(c.id, listOf(pic))) is InboundSink.Result.Failed)
        assertTrue(repo.attachments.forCapture(c.id).isEmpty())
        // no repository: refused, not crashed
        assertTrue(DirectSink(repo.captures, { emptyList() }).accept(Inbound.AddAttachments(c.id, listOf(pic))) is InboundSink.Result.Failed)
        // the file is still there for a retry or the sweep
        assertTrue(pic.fileName in store.list())
    }

    // ---------- remove ----------

    @Test fun removeDeletesTheRowAndTheFileAndLeavesATombstoneAndARevision() = runBlocking {
        val files = listOf(file(), file(), file())
        val c = note(*files.toTypedArray())
        val gone = files[1]
        assertTrue(repo.attachments.remove(gone.id))
        assertNull(db.attachments().get(gone.id))
        assertFalse(gone.fileName in store.list())
        val tombs = db.tombstones().all().filter { it.tableName == "attachment" }
        assertEquals(listOf(gone.id), tombs.map { it.rowId })
        val revs = db.revisions().forCapture(c.id).filter { it.what == RevisionKind.PICTURE_REMOVED }
        assertEquals(1, revs.size); assertEquals(gone.id, revs.single().oldValue)
        // the others are untouched: rows, positions, files, bytes
        assertEquals(listOf(files[0].id to 0, files[2].id to 2), repo.attachments.forCapture(c.id).map { it.id to it.position })
        for (f in listOf(files[0], files[2])) {
            assertTrue(f.fileName in store.list())
            assertArrayEquals(plains.getValue(f.id), repo.attachments.openBytes(f.id).use { it.readBytes() })
        }
        // a second remove is a no-op; reading it now is refused with a reason, and logged as a number
        assertFalse(repo.attachments.remove(gone.id))
        try { repo.attachments.openBytes(gone.id); fail() } catch (e: AttachmentUnreadable) {
            assertEquals(AttachmentUnreadable.Reason.NO_ROW, e.reason)
        }
        assertTrue(Triple(DbgComponent.PICTURE, DbgCode.UNREADABLE, AttachmentUnreadable.Reason.NO_ROW.code.toLong()) in events)
        assertTrue(events.any { it.first == DbgComponent.PICTURE && it.second == DbgCode.REMOVED })
        assertTrue(repo.consistency.run().isEmpty())
    }

    // ---------- deleting a note ----------

    @Test fun purgeRemovesEveryRowAndFileAndLeavesTombstones() = runBlocking {
        val files = (1..5).map { file() }
        val c = note(*files.toTypedArray())
        assertTrue(repo.captures.purge(c.id))
        assertTrue(db.attachments().forCapture(c.id).isEmpty())
        assertTrue(store.list().isEmpty())
        val tombs = db.tombstones().all()
        assertEquals(files.map { it.id }.toSet(), tombs.filter { it.tableName == "attachment" }.map { it.rowId }.toSet())
        assertEquals(5, tombs.count { it.tableName == "attachment" })
        assertEquals(listOf(c.id), tombs.filter { it.tableName == "capture" }.map { it.rowId })
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun aFileThatCannotBeDeletedAtPurgeIsSweptLater() = runBlocking {
        val files = (1..5).map { file() }
        val c = note(*files.toTypedArray())
        store.failures = 1
        assertTrue(repo.captures.purge(c.id))
        assertEquals(0, raw().rows("SELECT * FROM attachment").size)
        assertEquals(1, store.list().size)
        assertEquals(listOf(ViolationKind.FILE_WITHOUT_ATTACHMENT), repo.consistency.run().map { it.kind })
        // young: kept by the sweep
        assertEquals(0, repo.attachments.sweep())
        assertEquals(1, store.list().size)
        clock.advance(25 * 3600_000L)
        assertEquals(1, repo.attachments.sweep())
        assertTrue(store.list().isEmpty())
        assertTrue(repo.consistency.run().isEmpty())
        assertTrue(Triple(DbgComponent.PICTURE, DbgCode.SWEPT, 1L) in events)
    }

    @Test fun theDailyPurgeOfTheBinAlsoSweeps() = runBlocking {
        val c = note(file(), file())
        repo.captures.softDelete(c.id)
        File(dir, store.newFileName()).writeBytes(ByteArray(10))
        clock.advance(31L * 24 * 3600_000L)
        assertEquals(1, repo.purgeDeleted(30))
        assertTrue(store.list().isEmpty())
        // the daily worker's path does the same
        val c2 = note(file())
        repo.captures.softDelete(c2.id)
        clock.advance(31L * 24 * 3600_000L)
        val r = app.stash.pipeline.TaskUpkeep.run(store = { app.stash.data.Store.Ready(db) }, clock = clock, attachments = store)
        assertEquals(1, r.purged)
        assertTrue(store.list().isEmpty())
        assertEquals(0, raw().rows("SELECT * FROM attachment").size)
    }

    @Test fun softDeleteAndRestoreTouchNoAttachment() = runBlocking {
        val files = listOf(file(), file())
        val c = note(*files.toTypedArray())
        val rowsBefore = raw().rows("SELECT id, capture_id, position, file_name, sha256, updated_at FROM attachment ORDER BY position")
        val filesBefore = store.list().associateWith { File(dir, it).readBytes().toList() }
        repo.captures.softDelete(c.id)
        assertEquals(rowsBefore, raw().rows("SELECT id, capture_id, position, file_name, sha256, updated_at FROM attachment ORDER BY position"))
        assertEquals(filesBefore, store.list().associateWith { File(dir, it).readBytes().toList() })
        assertTrue(repo.consistency.run().isEmpty())
        repo.captures.restore(c.id)
        assertEquals(rowsBefore, raw().rows("SELECT id, capture_id, position, file_name, sha256, updated_at FROM attachment ORDER BY position"))
        assertEquals(filesBefore, store.list().associateWith { File(dir, it).readBytes().toList() })
        assertTrue(repo.consistency.run().isEmpty())
    }

    // ---------- the sweep ----------

    @Test fun theSweepDeletesOldOrphansAndOldTmpsAndNeverARow() = runBlocking {
        val now = clock.now()
        val old = store.newFileName(); File(dir, old).writeBytes(ByteArray(10)); File(dir, old).setLastModified(now - 25 * 3600_000L)
        val young = store.newFileName(); File(dir, young).writeBytes(ByteArray(10)); File(dir, young).setLastModified(now - 3600_000L)
        val tmp = File(dir, store.newFileName() + ".tmp"); tmp.writeBytes(ByteArray(10)); tmp.setLastModified(now - 25 * 3600_000L)
        val youngTmp = File(dir, store.newFileName() + ".tmp"); youngTmp.writeBytes(ByteArray(10)); youngTmp.setLastModified(now - 3600_000L)
        // a row whose file is gone
        val lost = file()
        val c = note(lost, file())
        File(dir, lost.fileName).delete()
        File(dir, lost.fileName).let { assertFalse(it.exists()) }
        // an old file that has a row is never swept
        File(dir, repo.attachments.forCapture(c.id)[1].let { a -> db.attachments().get(a.id)!!.fileName }).setLastModified(now - 48 * 3600_000L)

        assertEquals(2, repo.attachments.sweep())
        assertFalse(File(dir, old).exists()); assertTrue(File(dir, young).exists())
        assertFalse(tmp.exists()); assertTrue(youngTmp.exists())
        assertEquals(2, repo.attachments.forCapture(c.id).size)
        val v = repo.consistency.run()
        assertTrue(v.any { it.kind == ViolationKind.ATTACHMENT_WITHOUT_FILE && it.rowId == lost.id })
        assertTrue(v.any { it.kind == ViolationKind.FILE_WITHOUT_ATTACHMENT && it.rowId == young })
        // the row is still there after the check and another sweep
        repo.attachments.sweep()
        assertTrue(db.attachments().get(lost.id) != null)
        try { repo.attachments.openBytes(lost.id); fail() } catch (e: AttachmentUnreadable) {
            assertEquals(AttachmentUnreadable.Reason.MISSING, e.reason)
        }
    }

    // ---------- the one way in fails cleanly ----------

    @Test fun aFailedTransactionInTheSinkReturnsFailedAndLeavesTheFiles() = runBlocking {
        val sink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
        val existing = file()
        val other = note(existing)
        // a second file that reuses an existing attachment id makes the insert fail half-way through the transaction
        val ok = file()
        val clash = file().let { InboundAttachment(existing.id, it.kind, it.source, it.mime, it.fileName, it.key, it.byteSize, it.sha256) }
        val item = Inbound.Capture.now("words", "words", CaptureSource.SHARED, false, null, "test", clock, attachments = listOf(ok, clash))
        val r = sink.accept(item)
        assertTrue(r is InboundSink.Result.Failed)
        assertNull(repo.captures.get(item.id))
        assertNull(db.attachments().get(ok.id))
        assertTrue(ok.fileName in store.list()); assertTrue(clash.fileName in store.list())
        // AddAttachments rolls back the same way
        val r2 = sink.accept(Inbound.AddAttachments(other.id, listOf(ok, clash)))
        assertTrue(r2 is InboundSink.Result.Failed)
        assertEquals(listOf(existing.id), repo.attachments.forCapture(other.id).map { it.id })
        assertTrue(db.revisions().forCapture(other.id).none { it.what == RevisionKind.PICTURE_ADDED })
        assertTrue(ok.fileName in store.list())
        // a retry with the same good file then works
        assertTrue(sink.accept(item.copy(attachments = listOf(ok))) is InboundSink.Result.Saved)
        assertEquals(listOf(ok.id), repo.attachments.forCapture(item.id).map { it.id })
    }

    // ---------- audio fits without being a picture ----------

    @Test fun anAudioAttachmentIsNotAPictureButIsPurgedLikeOne() = runBlocking {
        val pic = file()
        val audio = file(kind = AttachmentKind.AUDIO)
        val c = note(pic, audio)
        assertEquals(listOf(pic.id), db.attachments().picturesForCapture(c.id).map { it.id })
        assertEquals(listOf(pic.id), repo.attachments.pictures(c.id).map { it.id })
        assertEquals(listOf(pic.id, audio.id), db.attachments().forCapture(c.id).map { it.id })
        val a = db.attachments().get(audio.id)!!
        assertEquals(4_000L, a.durationMs); assertNull(a.width); assertNull(a.height)
        // card 26c: a recording is opened only through openRecording, never through the picture door
        assertArrayEquals(plains.getValue(audio.id), repo.attachments.openRecording(audio.id).use { it.readBytes() })
        try { repo.attachments.openBytes(audio.id); fail() } catch (e: AttachmentUnreadable) {
            assertEquals(AttachmentUnreadable.Reason.WRONG_KIND, e.reason)
        }
        assertTrue(repo.captures.purge(c.id))
        assertNull(db.attachments().get(audio.id))
        assertFalse(audio.fileName in store.list())
        assertTrue(db.tombstones().all().any { it.tableName == "attachment" && it.rowId == audio.id })
    }

    // ---------- the key never shows ----------

    @Test fun theDebugDumpHoldsNoKey() = runBlocking {
        val pic = file()
        note(pic)
        val key = keys.getValue(pic.id)
        val text = DumpReceiver.dump(raw(), 100).toString()
        val hex = key.joinToString("") { "%02x".format(it) }
        for (form in listOf(hex, hex.uppercase(), Base64.getEncoder().encodeToString(key), Base64.getUrlEncoder().encodeToString(key),
            Base64.getEncoder().withoutPadding().encodeToString(key), Base64.getUrlEncoder().withoutPadding().encodeToString(key))) {
            assertFalse("key found in dump", text.contains(form))
        }
        assertFalse(text.contains(pic.fileName))
        val row = org.json.JSONObject(text).getJSONObject("tables").getJSONArray("attachment").getJSONObject(0)
        assertEquals(setOf("id", "kind", "position", "byte_size"), row.keys().asSequence().toSet())
        assertEquals(5, org.json.JSONObject(text).getInt("version"))
    }

    @Test fun eventsCarryNumbersOnly() = runBlocking {
        val pic = file(n = 12_345)
        note(pic)
        assertTrue(Triple(DbgComponent.PICTURE, DbgCode.STORED, 12_345L) in events)
    }

    @Test fun androidBackupAndDeviceTransferLeaveTheFilesOut() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val p = TestSupport.context.resources.getXml(app.stash.R.xml.data_extraction_rules)
        val excludes = mutableListOf<Pair<String, String>>()
        var section = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "cloud-backup", "device-transfer" -> section = p.name
                "exclude" -> excludes += section to (p.getAttributeValue(null, "domain") + ":" + p.getAttributeValue(null, "path"))
            }
        }
        // Card 26d adds files/models/ (the speech model pack) beside the attachment files.
        assertEquals(
            setOf("cloud-backup" to "file:att/", "device-transfer" to "file:att/", "cloud-backup" to "file:models/", "device-transfer" to "file:models/"),
            excludes.toSet(),
        )
        assertEquals(AttachmentStore.DIR + "/", "att/")
    }

    @Test fun theDebugSelfTestPassesAndLeavesTheFolderEmpty() = runBlocking {
        val out = AttachmentSelfTest.run(TestSupport.context, store, 1 shl 20)
        assertTrue(out.toString(), out.getBoolean("pass"))
        assertTrue(store.list().isEmpty() && store.temps().isEmpty())
        // it never wrote to the database it was not given
        assertTrue(repo.captures.live().isEmpty())
    }
}
