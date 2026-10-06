package com.chardidathing.litehub.source.feed

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chardidathing.litehub.core.model.FeedItem
import com.chardidathing.litehub.core.model.SourceStatus

// the last good items and fetch status per feed, so headlines draw on boot and offline
class FeedStore(context: Context) : SQLiteOpenHelper(context, "feed.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // position keeps the feed's own order for items without a date
        db.execSQL(
            "CREATE TABLE item (source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, link TEXT, " +
                "published INTEGER, position INTEGER NOT NULL, PRIMARY KEY (source, id))",
        )
        db.execSQL("CREATE TABLE status (source TEXT PRIMARY KEY, last_good INTEGER, error TEXT)")
    }

    // only a cache, a schema change starts it over
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS item")
        db.execSQL("DROP TABLE IF EXISTS status")
        onCreate(db)
    }

    fun items(): List<FeedItem> {
        val out = ArrayList<FeedItem>()
        readableDatabase.rawQuery("SELECT source, id, title, link, published FROM item ORDER BY source, position", null).use { c ->
            while (c.moveToNext()) {
                out += FeedItem(
                    source = c.getString(0),
                    id = c.getString(1),
                    title = c.getString(2),
                    link = if (c.isNull(3)) null else c.getString(3),
                    publishedMs = if (c.isNull(4)) null else c.getLong(4),
                )
            }
        }
        return out
    }

    fun statuses(): Map<String, SourceStatus> {
        val out = HashMap<String, SourceStatus>()
        readableDatabase.rawQuery("SELECT source, last_good, error FROM status", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = SourceStatus(
                    lastGood = if (c.isNull(1)) null else c.getLong(1),
                    error = if (c.isNull(2)) null else c.getString(2),
                )
            }
        }
        return out
    }

    fun replace(source: String, items: List<FeedItem>, fetchedAt: Long) = transaction { db ->
        db.delete("item", "source = ?", arrayOf(source))
        val row = ContentValues()
        items.forEachIndexed { i, item ->
            row.put("source", item.source)
            row.put("id", item.id)
            row.put("title", item.title)
            row.put("link", item.link)
            row.put("published", item.publishedMs)
            row.put("position", i)
            db.insertWithOnConflict("item", null, row, SQLiteDatabase.CONFLICT_IGNORE)
        }
        good(db, source, fetchedAt)
    }

    fun unchanged(source: String, fetchedAt: Long) = transaction { db -> good(db, source, fetchedAt) }

    fun failed(source: String, error: String) = transaction { db ->
        val row = ContentValues().apply {
            put("source", source)
            put("error", error)
        }
        if (db.update("status", row, "source = ?", arrayOf(source)) == 0) db.insert("status", null, row)
    }

    // a feed dropped from sources.json takes its items with it
    fun keepOnly(sources: Set<String>) = transaction { db ->
        val where = if (sources.isEmpty()) null else "source NOT IN (${sources.joinToString(",") { "?" }})"
        val args = if (sources.isEmpty()) null else sources.toTypedArray()
        db.delete("item", where, args)
        db.delete("status", where, args)
    }

    private fun good(db: SQLiteDatabase, source: String, fetchedAt: Long) {
        val row = ContentValues().apply {
            put("source", source)
            put("last_good", fetchedAt)
            putNull("error")
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
