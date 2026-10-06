package com.chardidathing.litehub.source.calendar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chardidathing.litehub.core.model.CalendarEvent
import com.chardidathing.litehub.core.model.SourceStatus

// expanded occurrences and fetch status per source, so calendars draw on boot and offline
class CalendarStore(context: Context) : SQLiteOpenHelper(context, "calendar.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE event (source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, location TEXT, " +
                "all_day INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, PRIMARY KEY (source, id))",
        )
        db.execSQL("CREATE TABLE status (source TEXT PRIMARY KEY, last_good INTEGER, error TEXT, window_day INTEGER)")
    }

    // only a cache, a schema change starts it over
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS event")
        db.execSQL("DROP TABLE IF EXISTS status")
        onCreate(db)
    }

    class Status(val status: SourceStatus, val windowDay: Long?)

    fun events(): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        readableDatabase.rawQuery("SELECT source, id, title, location, all_day, start, end FROM event ORDER BY start", null).use { c ->
            while (c.moveToNext()) {
                out += CalendarEvent(
                    source = c.getString(0),
                    id = c.getString(1),
                    title = c.getString(2),
                    location = if (c.isNull(3)) null else c.getString(3),
                    allDay = c.getInt(4) != 0,
                    startMs = c.getLong(5),
                    endMs = c.getLong(6),
                )
            }
        }
        return out
    }

    fun statuses(): Map<String, Status> {
        val out = HashMap<String, Status>()
        readableDatabase.rawQuery("SELECT source, last_good, error, window_day FROM status", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Status(
                    SourceStatus(
                        lastGood = if (c.isNull(1)) null else c.getLong(1),
                        error = if (c.isNull(2)) null else c.getString(2),
                    ),
                    windowDay = if (c.isNull(3)) null else c.getLong(3),
                )
            }
        }
        return out
    }

    // a fresh fetch replaces everything that source had
    fun replace(source: String, events: List<CalendarEvent>, fetchedAt: Long, windowDay: Long) = transaction { db ->
        db.delete("event", "source = ?", arrayOf(source))
        val row = ContentValues()
        for (e in events) {
            row.put("source", e.source)
            row.put("id", e.id)
            row.put("title", e.title)
            row.put("location", e.location)
            row.put("all_day", if (e.allDay) 1 else 0)
            row.put("start", e.startMs)
            row.put("end", e.endMs)
            db.insertWithOnConflict("event", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        }
        writeStatus(db, source, fetchedAt, null, windowDay)
    }

    // the feed hadn't changed, only the time moves on
    fun unchanged(source: String, fetchedAt: Long, windowDay: Long) = transaction { db ->
        writeStatus(db, source, fetchedAt, null, windowDay)
    }

    fun failed(source: String, error: String) = transaction { db ->
        val row = ContentValues().apply {
            put("source", source)
            put("error", error)
        }
        if (db.update("status", row, "source = ?", arrayOf(source)) == 0) db.insert("status", null, row)
    }

    // a source dropped from sources.json takes its events with it
    fun keepOnly(sources: Set<String>) = transaction { db ->
        val marks = sources.joinToString(",") { "?" }
        val args = sources.toTypedArray()
        val where = if (sources.isEmpty()) null else "source NOT IN ($marks)"
        db.delete("event", where, if (sources.isEmpty()) null else args)
        db.delete("status", where, if (sources.isEmpty()) null else args)
    }

    private fun writeStatus(db: SQLiteDatabase, source: String, lastGood: Long, error: String?, windowDay: Long) {
        val row = ContentValues().apply {
            put("source", source)
            put("last_good", lastGood)
            put("error", error)
            put("window_day", windowDay)
        }
        db.insertWithOnConflict("status", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
