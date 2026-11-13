package app.stash

import android.database.sqlite.SQLiteDatabase
import app.stash.TestSupport.rows
import app.stash.data.Migrations
import app.stash.data.SafetyCopy
import app.stash.data.TranscribeState
import app.stash.security.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

/**
 * Migration 3 to 4 (card 26c) with Room and real SQLite on the JVM. A real version-3 file is built from the committed
 * 3.json and seeded the way the owner's phone looks after card 16a: notes with records, a task, a placement with its
 * subject, a reading, revisions, a note in the bin, a tombstone, his word list, and notes with PHOTO attachments whose
 * encrypted files exist. It is opened through Room with the production migrations; Room checks every table against
 * the version-4 entities on open, so a mismatch fails here and not on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV4Test {
    private lateinit var dir: File
    private lateinit var store: AttachmentStore
    private val plains = mutableMapOf<String, ByteArray>()

    @Before fun clean() {
        wipe()
        dir = TestSupport.tempDir()
        store = AttachmentStore(dir)
    }

    @After fun cleanAfter() {
        wipe()
        dir.deleteRecursively()
    }

    private fun wipe() {
        val ctx = TestSupport.context
        ctx.databaseList().forEach { ctx.deleteDatabase(it) }
        File(ctx.filesDir, SafetyCopy.BACKUP_NAME).delete()
    }

    /** Every table except the search index and Room's own, each row as strings, in a fixed order. */
    private fun snapshot(query: (String) -> List<List<String?>>, tables: List<String>): Map<String, List<List<String?>>> =
        tables.associateWith { query("SELECT * FROM `$it` ORDER BY rowid") }

    /** Every cell as a string; a blob (the attachment keys) as its hex, so a changed key would show. */
    private fun cells(c: android.database.Cursor): List<List<String?>> =
        generateSequence {
            if (!c.moveToNext()) null
            else (0 until c.columnCount).map { i ->
                if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(i).joinToString("") { "%02x".format(it) } else c.getString(i)
            }
        }.toList()

    private val v3Tables = listOf(
        "capture", "extraction", "record", "task", "reading", "metric", "whereabouts", "subject", "place_location",
        "nudge_log", "revision", "tombstone", "miss_log", "app_event", "vocab_word", "correction", "attachment",
    )

    private fun createV3(): File {
        val file = TestSupport.context.getDatabasePath("stash.db")
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val v3 = TestSupport.v3Schema()
        val ents = v3.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            val t = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
            val idx = e.optJSONArray("indices") ?: continue
            for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t))
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${v3.getString("identityHash")}')")

        fun capture(id: String, at: Long, raw: String, text: String, source: String = "VOICE", sensitive: Int = 0, reason: String? = null,
            deletedAt: Long? = null, hint: String = "NONE") = db.execSQL(
            "INSERT INTO capture (id, created_at, updated_at, zone, raw_text, text, source, captured_locked, sensitive, sensitive_reason, lat, lon, accuracy_m, extract_state, extract_attempts, app_version, photo_hint, deleted_at) " +
                "VALUES (?, ?, ?, 'America/Phoenix', ?, ?, ?, 0, ?, ?, 33.45, -112.07, 12.5, 'DONE', 1, '0.3-debug', ?, ?)",
            arrayOf<Any?>(id, at, at + 5, raw, text, source, sensitive, reason, hint, deletedAt),
        )
        fun extraction(id: String, cap: String, at: Long) = db.execSQL(
            "INSERT INTO extraction (id, capture_id, at, extractor, schema_version, text_hash, output_json, status, error, tokens_in, tokens_out) VALUES (?, ?, ?, 'rules-3', 3, 'h', '{\"records\":[]}', 'CURRENT', NULL, NULL, NULL)",
            arrayOf<Any?>(id, cap, at),
        )
        fun record(id: String, cap: String, x: String, kind: String, title: String, quote: String, at: Long, subject: String? = null, pinned: Int = 0, deletedAt: Long? = null) = db.execSQL(
            "INSERT INTO record (id, capture_id, extraction_id, seq, kind, title, quote, subject_id, place_subject_id, place_text, spot_text, happened_on, happened_text, confidence, attention, pinned, supersedes_record_id, claims_to_close_record_id, aliases, tags, deleted_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, 0, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, 'HIGH', NULL, ?, NULL, NULL, '[]', '[]', ?, ?, ?)",
            arrayOf<Any?>(id, cap, x, kind, title, quote, subject, pinned, deletedAt, at, at),
        )
        fun indexed(id: String, type: String, body: String) = db.execSQL("INSERT INTO search_fts (doc_id, doc_type, body) VALUES (?, ?, ?)", arrayOf(id, type, body))
        fun photo(id: String, cap: String, position: Int, at: Long, ocr: String?) {
            val plain = ByteArray(3_000 + position).also { Random(at + position).nextBytes(it) }
            val key = store.newKey(); val name = store.newFileName()
            val w = store.write(name, key, id, ByteArrayInputStream(plain))
            plains[id] = plain
            db.execSQL(
                "INSERT INTO attachment (id, capture_id, kind, position, mime, file_name, file_key, format_version, byte_size, sha256, width, height, duration_ms, ocr_text, ocr_engine, source, created_at, updated_at) " +
                    "VALUES (?, ?, 'PHOTO', ?, 'image/webp', ?, ?, 1, ?, ?, 1600, 1200, NULL, ?, ?, 'CAMERA', ?, ?)",
                arrayOf<Any?>(id, cap, position, name, key, w.byteSize, w.sha256, ocr, if (ocr != null) "mlkit-latin" else null, at, at),
            )
        }

        // c-1: a placement, with a subject
        capture("c-1", 1000, "I'm putting my reading glasses in the nightstand drawer", "I'm putting my reading glasses in the nightstand drawer")
        extraction("c-1-x", "c-1", 1001)
        db.execSQL("INSERT INTO subject (id, type, name, norm_name, aliases, volatility, sensitive, merged_into_id, created_at, updated_at) VALUES ('s-1', 'ITEM', 'reading glasses', 'reading glasses', '[]', 'DAILY', 0, NULL, 1001, 1001)")
        record("c-1-r", "c-1", "c-1-x", "PLACED", "Reading glasses in the nightstand drawer", "reading glasses in the nightstand drawer", 1001, subject = "s-1")
        db.execSQL("INSERT INTO whereabouts (record_id, item_subject_id, item_text, where_text, person_subject_id, person_text, direction, copy_label, said_date_text, due_back_on, returned_at, created_at, updated_at) VALUES ('c-1-r', 's-1', 'reading glasses', 'in the nightstand drawer', NULL, NULL, 'PLACED', NULL, NULL, NULL, NULL, 1001, 1001)")
        indexed("c-1", "CAPTURE", "I'm putting my reading glasses in the nightstand drawer")
        indexed("c-1-r", "RECORD", "Reading glasses in the nightstand drawer reading glasses in the nightstand drawer")
        // c-2: a reading with a metric
        capture("c-2", 2000, "pool test free chlorine 5", "Pool test: free chlorine 5")
        extraction("c-2-x", "c-2", 2001)
        db.execSQL("INSERT INTO subject (id, type, name, norm_name, aliases, volatility, sensitive, merged_into_id, created_at, updated_at) VALUES ('s-2', 'THING', 'pool', 'pool', '[]', NULL, 0, NULL, 2001, 2001)")
        db.execSQL("INSERT INTO metric (id, subject_id, key, label, aliases, unit, created_at, updated_at) VALUES ('m-1', 's-2', 'free_chlorine', 'free chlorine', '[]', 'ppm', 2001, 2001)")
        record("c-2-r", "c-2", "c-2-x", "READING", "Pool free chlorine 5", "free chlorine 5", 2001, subject = "s-2")
        db.execSQL("INSERT INTO reading (id, record_id, subject_id, metric_id, value_text, value_num, unit, measured_at, created_at, updated_at) VALUES ('rd-1', 'c-2-r', 's-2', 'm-1', '5', 5.0, 'ppm', 2000, 2001, 2001)")
        indexed("c-2", "CAPTURE", "Pool test: free chlorine 5")
        indexed("c-2-r", "RECORD", "Pool free chlorine 5 free chlorine 5 pool free chlorine")
        // c-3: a Private task, pinned by the owner (a revision)
        capture("c-3", 3000, "private note change the gate code", "change the gate code", sensitive = 1, reason = "SAID")
        extraction("c-3-x", "c-3", 3001)
        record("c-3-r", "c-3", "c-3-x", "TASK", "Change the gate code", "change the gate code", 3001, pinned = 1)
        db.execSQL("INSERT INTO task (record_id, state, due_kind, due_from, due_to, due_text, at_place_subject_id, at_place_text, perishable, next_nudge_on, nudge_count, ignored_in_a_row, quiet, snoozed_until, closed_at, closed_how, closed_by_record_id, last_active_at, expires_on, created_at, updated_at) " +
            "VALUES ('c-3-r', 'OPEN', 'SOON', NULL, NULL, 'soon', NULL, NULL, 0, '2026-11-14', 1, 0, 0, NULL, NULL, NULL, NULL, 3001, '2026-12-14', 3001, 3001)")
        db.execSQL("INSERT INTO revision (id, at, capture_id, record_id, subject_id, what, old_value, new_value) VALUES ('rv-1', 3500, 'c-3', 'c-3-r', NULL, 'PINNED', '', '')")
        indexed("c-3", "CAPTURE", "change the gate code")
        indexed("c-3-r", "RECORD", "Change the gate code change the gate code")
        // c-4: a shared screenshot note with words and two pictures (one read)
        capture("c-4", 4000, "Saves the Day tell me when they play Phoenix", "Saves the Day tell me when they play Phoenix", source = "SHARED", hint = "DISMISSED")
        extraction("c-4-x", "c-4", 4001)
        record("c-4-r", "c-4", "c-4-x", "IDEA", "Saves the Day in Phoenix", "Saves the Day tell me when they play Phoenix", 4001)
        photo("a-1", "c-4", 0, 4000, "SAVES THE DAY TOUR 2027")
        photo("a-2", "c-4", 1, 4000, null)
        indexed("c-4", "CAPTURE", "Saves the Day tell me when they play Phoenix SAVES THE DAY TOUR 2027")
        indexed("c-4-r", "RECORD", "Saves the Day in Phoenix Saves the Day tell me when they play Phoenix")
        // c-5: a picture-only note (blank words, one picture)
        capture("c-5", 5000, "", "", source = "PHOTO")
        photo("a-3", "c-5", 0, 5000, null)
        indexed("c-5", "CAPTURE", "")
        // c-6: in the bin (not indexed), and a purged note's tombstone
        capture("c-6", 6000, "old note", "old note", deletedAt = 6500)
        extraction("c-6-x", "c-6", 6001)
        record("c-6-r", "c-6", "c-6-x", "OTHER", "old note", "old note", 6001, deletedAt = 6500)
        db.execSQL("INSERT INTO revision (id, at, capture_id, record_id, subject_id, what, old_value, new_value) VALUES ('rv-2', 6500, 'c-6', NULL, NULL, 'DELETED', '', '')")
        db.execSQL("INSERT INTO tombstone (id, table_name, row_id, deleted_at) VALUES ('tb-1', 'capture', 'gone-1', 5500)")
        db.execSQL("INSERT INTO app_event (id, at, component, code, n1, n2, ref_id, ms) VALUES ('e-1', 4000, 'PICTURE', 'STORED', 3000, NULL, 'a-1', NULL)")
        db.execSQL("INSERT INTO nudge_log (id, at, type, record_ids, outcome) VALUES ('n-1', 3600, 'DAILY', '[\"c-3-r\"]', 'SHOWN')")
        db.execSQL("INSERT INTO vocab_word (word) VALUES ('Syd'), ('Costco'), ('frunk')")
        db.execSQL("INSERT INTO correction (heard, meant) VALUES ('sid', 'Syd')")
        db.version = 3
        db.close()
        return file
    }

    private fun readV3(): Map<String, List<List<String?>>> {
        val d = SQLiteDatabase.openDatabase(TestSupport.context.getDatabasePath("stash.db").path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return snapshot({ sql -> d.rawQuery(sql, null).use { cells(it) } }, v3Tables)
        } finally {
            d.close()
        }
    }

    @Test fun version3OpensAtTheLatestVersionWithEveryRowUnchangedAndTheNewColumnsDefaulted() = runBlocking {
        createV3()
        val before = readV3()
        assertEquals(6, before.getValue("capture").size)
        assertEquals(3, before.getValue("attachment").size)

        val db = TestSupport.fileDb()
        val raw = db.openHelper.writableDatabase
        assertEquals(5, raw.version) // 3 to 4, then card 08's 4 to 5
        val after = snapshot({ sql -> raw.query(sql).use { cells(it) } }, v3Tables)
        // every table but capture byte-for-byte the same; capture the same with card 26c's two columns appended
        for (t in v3Tables - "capture") assertEquals("table $t changed", before.getValue(t), after.getValue(t))
        assertEquals(before.getValue("capture").map { it + listOf(null, "0") }, after.getValue("capture"))
        val caps = raw.rows("SELECT id, transcribe_state, transcribe_attempts FROM capture")
        assertTrue(caps.all { it["transcribe_state"] == null && it["transcribe_attempts"] == 0L })
        assertEquals(0, raw.rows("SELECT * FROM transcription").size)
        assertEquals(1, raw.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_transcription_capture_id'").size)

        // the app reads them: the entity, search by words and picture words, the pictures' bytes, a clean check
        val repo = TestSupport.repo(db, FakeClock(10_000), store)
        val c4 = repo.captures.get("c-4")!!
        assertNull(c4.transcribeState); assertEquals(0, c4.transcribeAttempts)
        assertEquals(setOf("c-1", "c-1-r"), repo.index.search("nightstand").map { it.docId }.toSet())
        assertEquals(setOf("c-4"), repo.index.search("tour").map { it.docId }.toSet())
        assertTrue(repo.index.search("old").isEmpty())
        for ((id, plain) in plains) assertArrayEquals(plain, repo.attachments.openBytes(id).use { it.readBytes() })
        assertEquals(listOf("a-1", "a-2"), repo.attachments.pictures("c-4").map { it.id })
        assertNull(repo.recordings.info("c-4"))
        assertEquals(0, repo.recordings.totals().count)
        assertTrue(repo.consistency.run().toString(), repo.consistency.run().isEmpty())
        // nothing waits to be read or written out because of the migration
        assertTrue(repo.captures.toRead().isEmpty())
        assertTrue(raw.rows("SELECT id FROM capture WHERE transcribe_state IS NOT NULL").isEmpty())
        // and the migrated database takes a recording like a new one
        val sink = app.stash.capture.DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
        val saver = app.stash.capture.RecordingSaver(store, sink, { null }, "test", FakeClock(20_000))
        val audio = app.stash.domain.EncodedAudio(ByteArray(900) { it.toByte() }, "audio/ogg", 1_500, app.stash.domain.AudioFormat.OPUS_12)
        val saved = saver.save(audio, capturedLocked = false, markPrivate = false) as app.stash.capture.InboundSink.Result.Saved
        assertEquals(TranscribeState.PENDING.name, raw.rows("SELECT transcribe_state FROM capture WHERE id = '${saved.capture.id}'").single()["transcribe_state"])
        assertTrue(repo.consistency.run().isEmpty())
        db.close()
    }

    @Test fun theMigrationAddsTwoColumnsAndOneTableCopiedExactlyFromTheExportedSchema() {
        assertEquals(4, Migrations.V3_TO_V4.size)
        val v3 = entities(TestSupport.v3Schema())
        val v4 = entities(TestSupport.v4Schema())
        // the only differences between 3.json and 4.json: capture's two columns and the new transcription table
        assertEquals(v3.keys + "transcription", v4.keys)
        for (t in v3.keys - "capture") assertEquals("table $t changed", v3.getValue(t).toString(), v4.getValue(t).toString())
        val oldSql = v3.getValue("capture").getString("createSql")
        val newSql = v4.getValue("capture").getString("createSql")
        val added = listOf("`transcribe_state` TEXT", "`transcribe_attempts` INTEGER NOT NULL DEFAULT 0")
        assertEquals(oldSql.replace(", PRIMARY KEY(`id`))", ", " + added.joinToString(", ") + ", PRIMARY KEY(`id`))"), newSql)
        assertEquals(added.map { "ALTER TABLE `capture` ADD COLUMN $it" }, Migrations.V3_TO_V4.take(2))
        // capture's indices are unchanged
        assertEquals(v3.getValue("capture").getJSONArray("indices").toString(), v4.getValue("capture").getJSONArray("indices").toString())
        // the new table and its one index, exactly as exported
        val t = v4.getValue("transcription")
        assertEquals(t.getString("createSql").replace("\${TABLE_NAME}", "transcription"), Migrations.V3_TO_V4[2])
        assertEquals(1, t.getJSONArray("indices").length())
        assertEquals(t.getJSONArray("indices").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "transcription"), Migrations.V3_TO_V4[3])
        // nothing in the migration rewrites, drops or deletes
        assertFalse(Migrations.V3_TO_V4.any { Regex("\\b(UPDATE|DELETE|DROP|INSERT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }

    @Test fun aVersion1DatabaseStillMigratesAllTheWayToTheLatestVersion() {
        // The MigrationTest version-1 cases open at the latest version too; this checks the chain (card 08 made it end at 5).
        assertEquals(5, Migrations.ALL.last().endVersion)
        assertEquals((1..4).toList(), Migrations.ALL.map { it.startVersion })
    }

    private fun entities(db: JSONObject): Map<String, JSONObject> {
        val ents = db.getJSONArray("entities")
        return (0 until ents.length()).map { ents.getJSONObject(it) }.associateBy { it.getString("tableName") }
    }
}
