package app.stash

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import app.stash.data.Clock
import app.stash.data.Migrations
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.security.AttachmentStore
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import java.io.File

class FakeClock(var t: Long = 1_000_000L) : Clock {
    override fun now() = t
    fun advance(ms: Long = 1000) { t += ms }
}

object TestSupport {
    val context: Context get() = RuntimeEnvironment.getApplication()

    fun memoryDb(): StashDatabase =
        Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()

    fun repo(db: StashDatabase, clock: Clock = FakeClock(), store: AttachmentStore = tempStore()) =
        Repository(null, db, null, clock, appVersion = "test", attachmentStore = store)

    /** A fresh, empty attachment folder for one test (card 16a). */
    fun tempStore(): AttachmentStore = AttachmentStore(tempDir())

    fun tempDir(): File = java.nio.file.Files.createTempDirectory("stash-att").toFile().also { it.deleteOnExit() }

    /** The exported version-1 schema (the prototype's), read from the committed schema folder. */
    fun v1Schema(): JSONObject = JSONObject(File("schemas/app.stash.data.StashDatabase/1.json").readText()).getJSONObject("database")

    /** The exported version-2 schema (card 01's), the starting point of the 2-to-3 migration (card 16a). */
    fun v2Schema(): JSONObject = JSONObject(File("schemas/app.stash.data.StashDatabase/2.json").readText()).getJSONObject("database")

    /** The exported version-3 schema (card 16a's), the starting point of the 3-to-4 migration (card 26c). */
    fun v3Schema(): JSONObject = JSONObject(File("schemas/app.stash.data.StashDatabase/3.json").readText()).getJSONObject("database")

    /** The exported version-4 schema (card 26c's). */
    fun v4Schema(): JSONObject = JSONObject(File("schemas/app.stash.data.StashDatabase/4.json").readText()).getJSONObject("database")

    /** The exported version-5 schema (card 08's: `answer_trace`). */
    fun v5Schema(): JSONObject = JSONObject(File("schemas/app.stash.data.StashDatabase/5.json").readText()).getJSONObject("database")

    fun fileDb(name: String = "stash.db"): StashDatabase =
        Room.databaseBuilder(context, StashDatabase::class.java, name).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()

    fun SupportSQLiteDatabase.rows(sql: String): List<Map<String, Any?>> = query(sql).use { c ->
        val out = mutableListOf<Map<String, Any?>>()
        while (c.moveToNext()) {
            val m = linkedMapOf<String, Any?>()
            for (i in 0 until c.columnCount) {
                m[c.getColumnName(i)] = when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    android.database.Cursor.FIELD_TYPE_BLOB -> String(c.getBlob(i))
                    else -> c.getString(i)
                }
            }
            out += m
        }
        out
    }
}
