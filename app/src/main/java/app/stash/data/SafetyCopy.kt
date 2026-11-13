package app.stash.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Before Room opens the database: if the file is still at version 1 (the prototype's) and no safety copy exists,
 * fold its write-ahead log into the main file and copy it to `files/stash-v1-backup.db`. An existing copy is never
 * overwritten. This is what lets a failed migration be undone by hand (docs/cards/01, "Safety copy").
 */
object SafetyCopy {
    const val BACKUP_NAME = "stash-v1-backup.db"

    /** Returns true if a copy was made now. Never throws: a failure to copy must not stop the app opening. */
    fun ensureV1Backup(context: Context, dbName: String = "stash.db"): Boolean {
        return try {
            val dbFile = context.getDatabasePath(dbName)
            val backup = File(context.filesDir, BACKUP_NAME)
            if (!dbFile.exists() || backup.exists()) return false
            if (userVersion(dbFile) != 1) return false
            checkpoint(dbFile)
            context.filesDir.mkdirs()
            val tmp = File(context.filesDir, "$BACKUP_NAME.part")
            dbFile.copyTo(tmp, overwrite = true)
            if (backup.exists()) { tmp.delete(); return false }
            tmp.renameTo(backup)
        } catch (e: Exception) {
            false
        }
    }

    private fun userVersion(file: File): Int {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return try { db.version } finally { db.close() }
    }

    private fun checkpoint(file: File) {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }
}
