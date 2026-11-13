package app.stash

import android.database.sqlite.SQLiteDatabase
import app.stash.TestSupport.rows
import app.stash.ask.AskEngine
import app.stash.ask.Lookups
import app.stash.data.Migrations
import app.stash.data.SafetyCopy
import app.stash.data.TraceRoute
import app.stash.security.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.Random

/**
 * Migration 4 to 5 (card 08: `answer_trace`) with Room and real SQLite on the JVM. A real version-4 file is built from
 * the committed 4.json and seeded the way the owner's phone looks after cards 16a to 26f and 07: notes with records, a
 * task, placements with subjects, readings with metrics, revisions, a note in the bin, a tombstone, his word list,
 * PHOTO attachments and a recording whose encrypted files exist, and writings-out in `transcription`. It is opened
 * through Room with the production migrations; Room checks every table against the version-5 entities on open.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV5Test {
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

    private val v4Tables = listOf(
        "capture", "extraction", "record", "task", "reading", "metric", "whereabouts", "subject", "place_location",
        "nudge_log", "revision", "tombstone", "miss_log", "app_event", "vocab_word", "correction", "attachment", "transcription",
    )

    /** Every cell as a string; a blob (the attachment keys) as its hex, so a changed key would show. */
    private fun cells(c: android.database.Cursor): List<List<String?>> =
        generateSequence {
            if (!c.moveToNext()) null
            else (0 until c.columnCount).map { i ->
                if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(i).joinToString("") { "%02x".format(it) } else c.getString(i)
            }
        }.toList()

    private fun snapshot(query: (String) -> List<List<String?>>): Map<String, List<List<String?>>> =
        v4Tables.associateWith { query("SELECT * FROM `$it` ORDER BY rowid") }

    private fun createV4() {
        val file = TestSupport.context.getDatabasePath("stash.db")
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val v4 = TestSupport.v4Schema()
        val ents = v4.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            val t = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
            val idx = e.optJSONArray("indices") ?: continue
            for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t))
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${v4.getString("identityHash")}')")

        fun capture(id: String, at: Long, text: String, source: String = "VOICE", sensitive: Int = 0, deletedAt: Long? = null, ts: String? = null, ta: Int = 0) = db.execSQL(
            "INSERT INTO capture (id, created_at, updated_at, zone, raw_text, text, source, captured_locked, sensitive, sensitive_reason, lat, lon, accuracy_m, extract_state, extract_attempts, app_version, photo_hint, deleted_at, transcribe_state, transcribe_attempts) " +
                "VALUES (?, ?, ?, 'America/Phoenix', ?, ?, ?, 0, ?, ?, 33.45, -112.07, 12.5, 'DONE', 1, '0.4-debug', 'NONE', ?, ?, ?)",
            arrayOf<Any?>(id, at, at + 5, text, text, source, sensitive, if (sensitive == 1) "SAID" else null, deletedAt, ts, ta),
        )
        fun extraction(cap: String, at: Long) = db.execSQL(
            "INSERT INTO extraction (id, capture_id, at, extractor, schema_version, text_hash, output_json, status, error, tokens_in, tokens_out) VALUES (?, ?, ?, 'rules-3', 3, 'h', '{\"records\":[]}', 'CURRENT', NULL, NULL, NULL)",
            arrayOf<Any?>("$cap-x", cap, at),
        )
        fun record(cap: String, kind: String, title: String, at: Long, subject: String? = null, pinned: Int = 0, deletedAt: Long? = null, place: String? = null) = db.execSQL(
            "INSERT INTO record (id, capture_id, extraction_id, seq, kind, title, quote, subject_id, place_subject_id, place_text, spot_text, happened_on, happened_text, confidence, attention, pinned, supersedes_record_id, claims_to_close_record_id, aliases, tags, deleted_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, 0, ?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, 'HIGH', NULL, ?, NULL, NULL, '[]', '[]', ?, ?, ?)",
            arrayOf<Any?>("$cap-r", cap, "$cap-x", kind, title, title, subject, place, pinned, deletedAt, at, at),
        )
        fun indexed(id: String, type: String, body: String) = db.execSQL("INSERT INTO search_fts (doc_id, doc_type, body) VALUES (?, ?, ?)", arrayOf(id, type, body))
        fun file(id: String, cap: String, kind: String, mime: String, position: Int, at: Long, size: Int, ocr: String?, duration: Long?) {
            val plain = ByteArray(size).also { Random(at + position).nextBytes(it) }
            val key = store.newKey(); val name = store.newFileName()
            val w = store.write(name, key, id, ByteArrayInputStream(plain))
            plains[id] = plain
            db.execSQL(
                "INSERT INTO attachment (id, capture_id, kind, position, mime, file_name, file_key, format_version, byte_size, sha256, width, height, duration_ms, ocr_text, ocr_engine, source, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, cap, kind, position, mime, name, key, w.byteSize, w.sha256, if (kind == "AUDIO") null else 1600, if (kind == "AUDIO") null else 1200,
                    duration, ocr, if (ocr != null) "mlkit-latin" else null, if (kind == "AUDIO") "RECORDER" else "CAMERA", at, at),
            )
        }

        // c-1: a placement with its subject
        capture("c-1", 1000, "I'm putting my reading glasses in the nightstand drawer")
        extraction("c-1", 1001)
        db.execSQL("INSERT INTO subject (id, type, name, norm_name, aliases, volatility, sensitive, merged_into_id, created_at, updated_at) VALUES ('s-1', 'ITEM', 'reading glasses', 'reading glasses', '[]', 'DAILY', 0, NULL, 1001, 1001)")
        record("c-1", "PLACED", "Reading glasses in the nightstand drawer", 1001, subject = "s-1")
        db.execSQL("INSERT INTO whereabouts (record_id, item_subject_id, item_text, where_text, person_subject_id, person_text, direction, copy_label, said_date_text, due_back_on, returned_at, created_at, updated_at) VALUES ('c-1-r', 's-1', 'reading glasses', 'in the nightstand drawer', NULL, NULL, 'PLACED', NULL, NULL, NULL, NULL, 1001, 1001)")
        indexed("c-1", "CAPTURE", "I'm putting my reading glasses in the nightstand drawer")
        indexed("c-1-r", "RECORD", "Reading glasses in the nightstand drawer reading glasses")
        // c-2: three pool readings with their metrics
        capture("c-2", 2000, "Pool test: free chlorine 5, pH 7.2, alkalinity 70")
        extraction("c-2", 2001)
        db.execSQL("INSERT INTO subject (id, type, name, norm_name, aliases, volatility, sensitive, merged_into_id, created_at, updated_at) VALUES ('s-2', 'THING', 'Pool', 'pool', '[]', NULL, 0, NULL, 2001, 2001)")
        for ((m, label, v) in listOf(Triple("m-1", "free chlorine", "5"), Triple("m-2", "pH", "7.2"), Triple("m-3", "alkalinity", "70"))) {
            db.execSQL("INSERT INTO metric (id, subject_id, key, label, aliases, unit, created_at, updated_at) VALUES (?, 's-2', ?, ?, '[]', NULL, 2001, 2001)", arrayOf(m, label.replace(' ', '_').lowercase(), label))
            db.execSQL("INSERT INTO reading (id, record_id, subject_id, metric_id, value_text, value_num, unit, measured_at, created_at, updated_at) VALUES (?, 'c-2-r', 's-2', ?, ?, ?, NULL, 2000, 2001, 2001)", arrayOf<Any?>("rd-$m", m, v, v.toDouble()))
        }
        record("c-2", "READING", "Pool test", 2001, subject = "s-2")
        indexed("c-2", "CAPTURE", "Pool test: free chlorine 5, pH 7.2, alkalinity 70")
        indexed("c-2-r", "RECORD", "Pool test Pool free chlorine pH alkalinity")
        // c-3: a Private task, pinned, with a revision and a nudge
        capture("c-3", 3000, "change the gate code", sensitive = 1)
        extraction("c-3", 3001)
        record("c-3", "TASK", "Change the gate code", 3001, pinned = 1)
        db.execSQL("INSERT INTO task (record_id, state, due_kind, due_from, due_to, due_text, at_place_subject_id, at_place_text, perishable, next_nudge_on, nudge_count, ignored_in_a_row, quiet, snoozed_until, closed_at, closed_how, closed_by_record_id, last_active_at, expires_on, created_at, updated_at) " +
            "VALUES ('c-3-r', 'OPEN', 'SOON', NULL, NULL, 'soon', NULL, NULL, 0, '2026-11-14', 1, 0, 0, NULL, NULL, NULL, NULL, 3001, '2026-12-14', 3001, 3001)")
        db.execSQL("INSERT INTO revision (id, at, capture_id, record_id, subject_id, what, old_value, new_value) VALUES ('rv-1', 3500, 'c-3', 'c-3-r', NULL, 'PINNED', '', '')")
        db.execSQL("INSERT INTO nudge_log (id, at, type, record_ids, outcome) VALUES ('n-1', 3600, 'DAILY', '[\"c-3-r\"]', 'SHOWN')")
        indexed("c-3", "CAPTURE", "change the gate code")
        indexed("c-3-r", "RECORD", "Change the gate code")
        // c-4: a shared note at a place with two pictures, one read
        capture("c-4", 4000, "A Barolo to try", source = "SHARED")
        extraction("c-4", 4001)
        record("c-4", "OTHER", "A Barolo to try", 4001, place = "Total Wine")
        file("a-1", "c-4", "PHOTO", "image/webp", 0, 4000, 3_000, "BAROLO 2019", null)
        file("a-2", "c-4", "PHOTO", "image/webp", 1, 4000, 3_001, null, null)
        indexed("c-4", "CAPTURE", "A Barolo to try BAROLO 2019")
        indexed("c-4-r", "RECORD", "A Barolo to try")
        // c-5: a voice note with its recording, written out (two writings-out, one replaced)
        capture("c-5", 5000, "I changed the furnace filter", ts = "DONE", ta = 1)
        extraction("c-5", 5001)
        record("c-5", "EVENT", "Changed the furnace filter", 5001)
        file("a-3", "c-5", "AUDIO", "audio/ogg", 0, 5000, 4_000, null, 2_500)
        db.execSQL("INSERT INTO transcription (id, capture_id, attachment_id, at, engine, text, status, error, audio_ms, proc_ms, chunks, doubt, asked_by_owner) VALUES ('t-1', 'c-5', 'a-3', 5100, 'sherpa:base', 'I change the furnace filter', 'REPLACED', NULL, 2500, 900, 1, 0, 0)")
        db.execSQL("INSERT INTO transcription (id, capture_id, attachment_id, at, engine, text, status, error, audio_ms, proc_ms, chunks, doubt, asked_by_owner) VALUES ('t-2', 'c-5', 'a-3', 5200, 'sherpa:base', 'I changed the furnace filter', 'CURRENT', NULL, 2500, 950, 1, 0, 1)")
        indexed("c-5", "CAPTURE", "I changed the furnace filter")
        indexed("c-5-r", "RECORD", "Changed the furnace filter")
        // c-6: a recording still waiting (no words), and c-7 in the bin; a tombstone, an event, a miss, the word list
        capture("c-6", 6000, "", ts = "PENDING")
        file("a-4", "c-6", "AUDIO", "audio/ogg", 0, 6000, 1_000, null, 900)
        indexed("c-6", "CAPTURE", "")
        capture("c-7", 7000, "old note", deletedAt = 7500)
        extraction("c-7", 7001)
        record("c-7", "OTHER", "old note", 7001, deletedAt = 7500)
        db.execSQL("INSERT INTO revision (id, at, capture_id, record_id, subject_id, what, old_value, new_value) VALUES ('rv-2', 7500, 'c-7', NULL, NULL, 'DELETED', '', '')")
        db.execSQL("INSERT INTO tombstone (id, table_name, row_id, deleted_at) VALUES ('tb-1', 'capture', 'gone-1', 5500)")
        db.execSQL("INSERT INTO app_event (id, at, component, code, n1, n2, ref_id, ms) VALUES ('e-1', 5000, 'AUDIO', 'STORED', 4000, 2500, 'a-3', NULL)")
        db.execSQL("INSERT INTO miss_log (id, at, question, reason) VALUES ('ms-1', 6500, 'where is the kayak', 'NO_MATCH')")
        db.execSQL("INSERT INTO vocab_word (word) VALUES ('Syd'), ('Costco'), ('frunk')")
        db.execSQL("INSERT INTO correction (heard, meant) VALUES ('sid', 'Syd')")
        db.version = 4
        db.close()
    }

    private fun readV4(): Map<String, List<List<String?>>> {
        val d = SQLiteDatabase.openDatabase(TestSupport.context.getDatabasePath("stash.db").path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return snapshot { sql -> d.rawQuery(sql, null).use { cells(it) } }
        } finally {
            d.close()
        }
    }

    @Test fun version4OpensAtVersion5WithEveryTableUnchangedCellForCell() = runBlocking {
        createV4()
        val before = readV4()
        assertEquals(7, before.getValue("capture").size)
        assertEquals(4, before.getValue("attachment").size)
        assertEquals(2, before.getValue("transcription").size)
        assertEquals(3, before.getValue("reading").size)

        val db = TestSupport.fileDb()
        val raw = db.openHelper.writableDatabase
        assertEquals(5, raw.version)
        val after = snapshot { sql -> raw.query(sql).use { cells(it) } }
        for (t in v4Tables) assertEquals("table $t changed", before.getValue(t), after.getValue(t))
        assertEquals(0, raw.rows("SELECT * FROM answer_trace").size)
        assertEquals(1, raw.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_answer_trace_at'").size)

        // the app reads them: search (words and picture words), the files, a clean check, nothing queued by the migration
        val clock = FakeClock(10_000)
        val repo = TestSupport.repo(db, clock, store)
        assertEquals(setOf("c-1", "c-1-r"), repo.index.search("nightstand").map { it.docId }.toSet())
        assertEquals(setOf("c-4"), repo.index.search("2019").map { it.docId }.toSet())
        for ((id, plain) in plains.filterKeys { it != "a-3" && it != "a-4" }) assertArrayEquals(plain, repo.attachments.openBytes(id).use { it.readBytes() })
        assertArrayEquals(plains.getValue("a-3"), repo.attachments.openRecording("a-3").use { it.readBytes() })
        assertTrue(repo.consistency.run().toString(), repo.consistency.run().isEmpty())
        assertTrue(repo.captures.toRead().isEmpty())

        // and the migrated database answers questions and keeps their traces
        val engine = AskEngine(Lookups(repo.ask, clock::now, { ZoneOffset.UTC }), repo.traces, clock::now, { ZoneOffset.UTC }, "test")
        val r = engine.ask("Where did I put my glasses?")
        assertEquals(listOf("c-1-r"), r.sources.map { it.id })
        assertEquals("pH was 7.2, just now.", engine.ask("What was my pH last time?").text)
        val secret = engine.ask("gate code")
        assertEquals(0, secret.sources.size) // Private: never shown, only counted
        assertEquals(1, secret.privateCount)
        assertEquals(TraceRoute.ROUTER, repo.traces.newest().last().route)
        assertEquals(3, raw.rows("SELECT * FROM answer_trace").size)
        db.close()
    }

    @Test fun theMigrationAddsOneTableCopiedExactlyFromTheExportedSchema() {
        assertEquals(2, Migrations.V4_TO_V5.size)
        val v4 = entities(TestSupport.v4Schema())
        val v5 = entities(TestSupport.v5Schema())
        assertEquals(5, TestSupport.v5Schema().getInt("version"))
        // the only difference between 4.json and 5.json is the new table
        assertEquals(v4.keys + "answer_trace", v5.keys)
        for (t in v4.keys) assertEquals("table $t changed", v4.getValue(t).toString(), v5.getValue(t).toString())
        val t = v5.getValue("answer_trace")
        assertEquals(t.getString("createSql").replace("\${TABLE_NAME}", "answer_trace"), Migrations.V4_TO_V5[0])
        assertEquals(1, t.getJSONArray("indices").length())
        assertEquals(t.getJSONArray("indices").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "answer_trace"), Migrations.V4_TO_V5[1])
        // the card's columns, and nothing that rewrites, drops or deletes
        val cols = (0 until t.getJSONArray("fields").length()).map { t.getJSONArray("fields").getJSONObject(it).getString("columnName") }
        assertEquals(
            listOf("id", "at", "question", "route", "route_name", "steps_json", "source_ids", "answer_text", "written_by", "outcome", "error_code",
                "ms_total", "marked_wrong", "app_version", "router_version", "prompt_version", "updated_at"),
            cols,
        )
        assertFalse(Migrations.V4_TO_V5.any { Regex("\\b(UPDATE|DELETE|DROP|INSERT|ALTER)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), Migrations.ALL.map { it.startVersion to it.endVersion })
    }

    private fun entities(db: JSONObject): Map<String, JSONObject> {
        val ents = db.getJSONArray("entities")
        return (0 until ents.length()).map { ents.getJSONObject(it) }.associateBy { it.getString("tableName") }
    }
}
