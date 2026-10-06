package com.chardidathing.litehub.source.ha

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chardidathing.litehub.core.model.Entity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// last known state per entity, so the dashboard draws on boot before ha answers
class EntityCache(context: Context) : SQLiteOpenHelper(context, "ha_cache.db", null, 1) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entity (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
    }

    // it's a cache, a schema change just starts it over
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entity")
        onCreate(db)
    }

    fun load(ids: Collection<String>): Map<String, Entity> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Entity>()
        val marks = ids.joinToString(",") { "?" }
        readableDatabase.rawQuery("SELECT json FROM entity WHERE id IN ($marks)", ids.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                // a row we can't read is just a cache miss
                val entity = try {
                    json.decodeFromString(Entity.serializer(), c.getString(0))
                } catch (e: SerializationException) {
                    continue
                }
                out[entity.id] = entity
            }
        }
        return out
    }

    fun write(save: Collection<Entity>, delete: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues()
            for (entity in save) {
                row.put("id", entity.id)
                row.put("json", json.encodeToString(Entity.serializer(), entity))
                db.insertWithOnConflict("entity", null, row, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (id in delete) db.delete("entity", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
