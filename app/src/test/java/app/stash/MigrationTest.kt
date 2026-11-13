package app.stash

import android.database.sqlite.SQLiteDatabase
import app.stash.TestSupport.rows
import app.stash.data.Migrations
import app.stash.data.SafetyCopy
import app.stash.domain.AnswerComposer
import app.stash.domain.Searcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migration 1 to 2 with Room and real SQLite on the JVM (Robolectric). It builds a real version-1 database file
 * from the committed exported schema 1.json, seeds it as the card says, then opens it through Room with the
 * production migration. Room itself checks the migrated tables, indices and the FTS table against the version-2
 * entities when it opens, so a mismatch fails here, not on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private data class Seed(
        val id: String, val createdAt: Long, val rawText: String, val text: String, val kind: String,
        val item: String? = null, val place: String? = null, val person: String? = null, val eventDate: String? = null,
        val lat: Double? = null, val lon: Double? = null, val supersedesId: String? = null,
        val localOnly: Int = 0, val source: String = "RULES",
    )

    private val seeds = listOf(
        Seed("n1", 1000, "raw glasses", "I'm putting my reading glasses in the nightstand drawer", "STORED_ITEM", "reading glasses", "in the nightstand drawer"),
        Seed("n2", 2000, "glasses again", "My reading glasses are on the desk", "STORED_ITEM", "reading glasses", "on the desk", supersedesId = "n1"),
        Seed("n3", 3000, "guitar", "I loaned my guitar to Frank on Tuesday", "LOAN", "guitar", null, "Frank", "Tuesday", source = "MODEL"),
        Seed("n4", 4000, "milk", "I need to remember to buy milk at Costco", "REMINDER", "milk", "at Costco"),
        Seed("n5", 5000, "I am updating the app for stash right now", "I am updating the app for stash right now", "OTHER"),
        Seed("n6", 6000, "spare key", "Syd has the spare key", "STORED_ITEM", "spare key", "with Syd", lat = 33.8198, lon = -117.9),
        Seed("n7", 7000, "private thing", "Something private", "OTHER", localOnly = 1),
    )

    @Before fun clean() = wipe()
    @After fun cleanAfter() = wipe()

    /** Robolectric keeps the app's files between tests of a class; every test starts from nothing. */
    private fun wipe() {
        val ctx = TestSupport.context
        ctx.databaseList().forEach { ctx.deleteDatabase(it) }
        File(ctx.filesDir, SafetyCopy.BACKUP_NAME).delete()
    }

    private fun createV1(name: String = "stash.db"): File {
        val file = TestSupport.context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val v1 = TestSupport.v1Schema()
        val ents = v1.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${v1.getString("identityHash")}')")
        for (s in seeds) {
            db.execSQL(
                "INSERT INTO note (id, createdAt, rawText, text, kind, item, place, person, eventDate, lat, lon, placeName, expiresAt, supersedesId, localOnly, structureSource) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?,?,?)",
                arrayOf(s.id, s.createdAt, s.rawText, s.text, s.kind, s.item, s.place, s.person, s.eventDate, s.lat, s.lon, s.supersedesId, s.localOnly, s.source),
            )
        }
        db.execSQL("INSERT INTO vocab_word (word) VALUES ('Syd'), ('Costco')")
        db.execSQL("INSERT INTO correction (heard, meant) VALUES ('sid', 'Syd')")
        db.version = 1
        db.close()
        return file
    }

    @Test fun v1SeedDocumentsTheDroppedColumnsAreEmpty() {
        val file = createV1()
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        db.rawQuery("SELECT COUNT(*) FROM note WHERE placeName IS NOT NULL OR expiresAt IS NOT NULL", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test fun everyNoteBecomesOneCaptureWithOneRecordAndNothingIsLost() {
        createV1()
        val db = TestSupport.fileDb()
        val raw = db.openHelper.writableDatabase

        val captures = raw.rows("SELECT * FROM capture ORDER BY created_at").associateBy { it["id"] as String }
        assertEquals(seeds.size, captures.size)
        for (s in seeds) {
            val c = captures.getValue(s.id)
            assertEquals(s.rawText, c["raw_text"]); assertEquals(s.text, c["text"]); assertEquals(s.createdAt, c["created_at"])
            assertEquals(s.lat, c["lat"]); assertEquals(s.lon, c["lon"])
            assertEquals(s.localOnly.toLong(), c["sensitive"])
            assertEquals("PENDING", c["extract_state"]); assertEquals(0L, c["extract_attempts"])
            assertEquals("VOICE", c["source"]); assertNull(c["zone"]); assertNull(c["captured_locked"])
            assertEquals("NONE", c["photo_hint"]); assertEquals(s.createdAt, c["updated_at"])
        }

        val records = raw.rows("SELECT * FROM record").groupBy { it["capture_id"] as String }
        for (s in seeds) {
            val rs = records.getValue(s.id)
            assertEquals(1, rs.size)
            val r = rs.single()
            assertEquals("${s.id}-r0", r["id"]); assertEquals("${s.id}-x0", r["extraction_id"])
            val kind = when (s.kind) { "STORED_ITEM" -> "PLACED"; "LOAN" -> "LOAN"; "REMINDER" -> "TASK"; else -> "OTHER" }
            assertEquals(kind, r["kind"])
            assertEquals(s.text.take(80), r["title"]); assertEquals(s.text, r["quote"])
            assertEquals("MEDIUM", r["confidence"]); assertEquals(0L, r["pinned"]); assertEquals("[]", r["aliases"]); assertEquals("[]", r["tags"])
            assertEquals(s.supersedesId?.let { "$it-r0" }, r["supersedes_record_id"])
        }
        // the supersession link points at the right record
        assertEquals("n1-r0", raw.rows("SELECT supersedes_record_id FROM record WHERE id = 'n2-r0'").single().values.single())

        val w = raw.rows("SELECT * FROM whereabouts").associateBy { it["record_id"] as String }
        assertEquals(setOf("n1-r0", "n2-r0", "n3-r0", "n6-r0"), w.keys)
        assertEquals("reading glasses", w.getValue("n1-r0")["item_text"]); assertEquals("in the nightstand drawer", w.getValue("n1-r0")["where_text"])
        assertEquals("PLACED", w.getValue("n1-r0")["direction"])
        assertEquals("guitar", w.getValue("n3-r0")["item_text"]); assertEquals("Frank", w.getValue("n3-r0")["person_text"])
        assertEquals("Tuesday", w.getValue("n3-r0")["said_date_text"]); assertEquals("LENT", w.getValue("n3-r0")["direction"])
        val t = raw.rows("SELECT * FROM task").single()
        assertEquals("n4-r0", t["record_id"]); assertEquals("at Costco", t["at_place_text"]); assertEquals("OPEN", t["state"])
        assertEquals("NONE", t["due_kind"]); assertEquals(4000L, t["last_active_at"])

        val x = raw.rows("SELECT * FROM extraction").associateBy { it["capture_id"] as String }
        assertEquals("prototype-model", x.getValue("n3")["extractor"]); assertEquals("prototype-rules", x.getValue("n1")["extractor"])
        assertEquals("CURRENT", x.getValue("n1")["status"]); assertEquals(0L, x.getValue("n1")["schema_version"])
        val out = org.json.JSONObject(x.getValue("n4")["output_json"] as String)
        assertEquals("REMINDER", out.getString("kind")); assertEquals("milk", out.getString("item")); assertEquals("at Costco", out.getString("place"))

        // vocabulary and corrections unchanged; the old table is gone
        assertEquals(setOf("Syd", "Costco"), raw.rows("SELECT word FROM vocab_word").map { it["word"] }.toSet())
        assertEquals(listOf("sid" to "Syd"), raw.rows("SELECT * FROM correction").map { it["heard"] to it["meant"] })
        assertEquals(0, raw.rows("SELECT name FROM sqlite_master WHERE name = 'note'").size)

        // search documents exist for every capture and record, and a word only in the text finds it
        assertEquals(seeds.size * 2, raw.rows("SELECT * FROM search_fts").size)
        val hits = runBlocking { TestSupport.repo(db).index.search("nightstand") }
        assertEquals(setOf("n1", "n1-r0"), hits.map { it.docId }.toSet())
        db.close()
    }

    @Test fun theAppStillAnswersFromMigratedNotes() = runBlocking {
        createV1()
        val db = TestSupport.fileDb()
        val repo = TestSupport.repo(db)
        val now = 10_000L + 1000L * 3600 * 24 * 21
        val glasses = Searcher.search("Where did I put my glasses?", repo.noteViews())
        // n2 replaced n1, so the desk is current and the drawer is history
        assertTrue(AnswerComposer.compose(glasses, now).startsWith("Your reading glasses are on the desk"))
        assertEquals("n1-r0", glasses.first().history.first().id)
        val milk = Searcher.search("milk", repo.noteViews()).first().note
        assertEquals("milk", milk.item); assertEquals("at Costco", milk.place)
        assertTrue(repo.consistency.run().isEmpty())
        db.close()
    }

    @Test fun safetyCopyIsMadeOnceFromAVersion1FileAndNeverOverwritten() {
        val file = createV1()
        val backup = File(TestSupport.context.filesDir, SafetyCopy.BACKUP_NAME)
        assertFalse(backup.exists())
        assertTrue(SafetyCopy.ensureV1Backup(TestSupport.context))
        assertTrue(backup.exists())
        // the copy is a complete version-1 database with every note
        val c = SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY)
        c.rawQuery("SELECT COUNT(*) FROM note", null).use { it.moveToFirst(); assertEquals(seeds.size, it.getInt(0)) }
        assertEquals(1, c.version)
        c.close()
        // a second call does nothing; changing the original does not change the copy
        val size = backup.length()
        val d = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        d.execSQL("DELETE FROM note"); d.close()
        assertFalse(SafetyCopy.ensureV1Backup(TestSupport.context))
        assertEquals(size, backup.length())
    }

    @Test fun noSafetyCopyOfAVersion2Database() {
        createV1()
        TestSupport.fileDb().also { it.openHelper.writableDatabase; it.close() }
        assertFalse(SafetyCopy.ensureV1Backup(TestSupport.context))
        assertFalse(File(TestSupport.context.filesDir, SafetyCopy.BACKUP_NAME).exists())
    }

    /** A real version-2 file built from the committed 2.json, as the phone has it after card 01. */
    private fun createV2(name: String = "stash.db"): File {
        val file = TestSupport.context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val v2 = TestSupport.v2Schema()
        val ents = v2.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            val t = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
            val idx = e.optJSONArray("indices") ?: continue
            for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t))
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${v2.getString("identityHash")}')")
        for ((i, c) in v2Captures.withIndex()) {
            db.execSQL(
                "INSERT INTO capture (id, created_at, updated_at, zone, raw_text, text, source, captured_locked, sensitive, sensitive_reason, lat, lon, accuracy_m, extract_state, extract_attempts, app_version, photo_hint, deleted_at) " +
                    "VALUES (?, ?, ?, 'Europe/London', ?, ?, 'VOICE', 1, ?, NULL, 51.5, -0.12, 20.0, 'DONE', 1, '0.1-prototype', 'NONE', NULL)",
                arrayOf<Any?>(c.first, 1000L * (i + 1), 1000L * (i + 1), "raw " + c.second, c.second, if (i == 2) 1 else 0),
            )
            db.execSQL(
                "INSERT INTO extraction (id, capture_id, at, extractor, schema_version, text_hash, output_json, status, error, tokens_in, tokens_out) VALUES (?, ?, ?, 'rules-2', 1, 'h', '{}', 'CURRENT', NULL, NULL, NULL)",
                arrayOf<Any?>(c.first + "-x", c.first, 1000L * (i + 1)),
            )
            db.execSQL(
                "INSERT INTO record (id, capture_id, extraction_id, seq, kind, title, quote, subject_id, place_subject_id, place_text, spot_text, happened_on, happened_text, confidence, attention, pinned, supersedes_record_id, claims_to_close_record_id, aliases, tags, deleted_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 0, 'OTHER', ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, 'HIGH', NULL, ?, NULL, NULL, '[]', '[]', NULL, ?, ?)",
                arrayOf<Any?>(c.first + "-r", c.first, c.first + "-x", c.second.take(20), c.second, i % 2, 1000L * (i + 1), 1000L * (i + 1)),
            )
            db.execSQL("INSERT INTO search_fts (doc_id, doc_type, body) VALUES (?, 'CAPTURE', ?)", arrayOf(c.first, c.second))
            db.execSQL("INSERT INTO search_fts (doc_id, doc_type, body) VALUES (?, 'RECORD', ?)", arrayOf(c.first + "-r", c.second.take(20) + " " + c.second))
        }
        db.version = 2
        db.close()
        return file
    }

    private val v2Captures = listOf(
        "c-1" to "I'm putting my reading glasses in the nightstand drawer",
        "c-2" to "Pool test: free chlorine 5, pH 7.2, alkalinity 70",
        "c-3" to "Something private",
    )

    @Test fun version2OpensAtVersion5WithEveryNoteUnchangedAndNoAttachments() {
        createV2()
        val before = SQLiteDatabase.openDatabase(TestSupport.context.getDatabasePath("stash.db").path, null, SQLiteDatabase.OPEN_READONLY).let { d ->
            fun dump(sql: String) = d.rawQuery(sql, null).use { c ->
                generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { c.getString(it) } else null }.toList()
            }
            val out = dump("SELECT * FROM capture ORDER BY id") to dump("SELECT * FROM record ORDER BY id")
            d.close(); out
        }
        val db = TestSupport.fileDb()
        val raw = db.openHelper.writableDatabase
        assertEquals(5, raw.version)
        fun dump(sql: String) = raw.query(sql).use { c ->
            generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { c.getString(it) } else null }.toList()
        }
        // 2 to 3 to 4 to 5: every version-2 column unchanged; card 26c's two columns appended as null and 0
        assertEquals(before.first.map { it + listOf(null, "0") }, dump("SELECT * FROM capture ORDER BY id"))
        assertEquals(before.second, dump("SELECT * FROM record ORDER BY id"))
        assertEquals(3, before.first.size); assertEquals(3, before.second.size)
        assertEquals(0, raw.rows("SELECT * FROM attachment").size)
        assertEquals(1, raw.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_attachment_capture_id'").size)
        // the app still reads them: search, and a clean consistency check
        val repo = TestSupport.repo(db)
        val hits = runBlocking { repo.index.search("nightstand") }
        assertEquals(setOf("c-1", "c-1-r"), hits.map { it.docId }.toSet())
        assertTrue(runBlocking { repo.consistency.run() }.isEmpty())
        db.close()
    }

    @Test fun theMigrationAddsOnlyTheAttachmentTableAndItsIndex() {
        assertEquals(2, Migrations.V2_TO_V3.size)
        assertTrue(Migrations.V2_TO_V3[0].startsWith("CREATE TABLE IF NOT EXISTS `attachment`"))
        assertTrue(Migrations.V2_TO_V3[1].startsWith("CREATE INDEX IF NOT EXISTS `index_attachment_capture_id`"))
        // copied exactly from the committed schema
        val v3 = org.json.JSONObject(File("schemas/app.stash.data.StashDatabase/3.json").readText()).getJSONObject("database")
        val ents = v3.getJSONArray("entities")
        val att = (0 until ents.length()).map { ents.getJSONObject(it) }.single { it.getString("tableName") == "attachment" }
        assertEquals(att.getString("createSql").replace("\${TABLE_NAME}", "attachment"), Migrations.V2_TO_V3[0])
        assertEquals(att.getJSONArray("indices").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "attachment"), Migrations.V2_TO_V3[1])
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), Migrations.ALL.map { it.startVersion to it.endVersion })
    }

    @Test fun noDestructiveFallbackAnywhereInTheCode() {
        val bad = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("fallbackToDestructiveMigration") }.toList()
        assertTrue("destructive fallback found in $bad", bad.isEmpty())
    }
}
