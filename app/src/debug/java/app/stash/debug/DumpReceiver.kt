package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.stash.StashApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only (src/debug). `adb shell am broadcast -n app.stash/app.stash.debug.DumpReceiver --ei limit 100`
 * writes files/debug/dump.json: every table except the search index, newest first, [limit] rows each. It holds the
 * owner's words in plain text, which is the stated condition of the debug build (docs/STORAGE.md). tools/peek.py
 * sends the broadcast and reads the file.
 */
class DumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val limit = intent.getIntExtra("limit", 1000).coerceAtLeast(1)
        val pending = goAsync()
        thread {
            try {
                val db = (context.applicationContext as StashApp).container.database().openHelper.readableDatabase
                val file = File(context.filesDir, "debug/dump.json")
                file.parentFile?.mkdirs()
                file.writeText(dump(db, limit).toString())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * The dump itself, apart from the broadcast so a JVM test can run it. An `attachment` row is printed as id,
         * kind, position and size and nothing else: never its key or file name (docs/cards/16a item 5).
         */
        fun dump(db: SupportSQLiteDatabase, limit: Int): JSONObject {
            val tables = JSONObject()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'search_fts%' AND name NOT IN ('android_metadata', 'room_master_table')").use { names ->
                while (names.moveToNext()) {
                    val t = names.getString(0)
                    val cols = db.query("PRAGMA table_info(`$t`)").use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList() }
                    val order = cols.firstOrNull { it == "created_at" || it == "at" } ?: "rowid"
                    val select = if (t == "attachment") "id, kind, position, byte_size" else "*"
                    val rows = JSONArray()
                    db.query("SELECT $select FROM `$t` ORDER BY `$order` DESC LIMIT $limit").use { c ->
                        while (c.moveToNext()) rows.put(row(c))
                    }
                    tables.put(t, rows)
                }
            }
            return JSONObject().put("version", db.version).put("tables", tables)
        }

        private fun row(c: Cursor): JSONObject {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                o.put(
                    c.getColumnName(i),
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size} bytes>"
                        else -> c.getString(i)
                    },
                )
            }
            return o
        }
    }
}
