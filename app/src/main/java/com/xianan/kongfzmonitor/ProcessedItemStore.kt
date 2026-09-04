package com.xianan.kongfzmonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProcessedItemStore(context: Context) : SQLiteOpenHelper(
    context,
    "processed_items.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE processed_items (
                item_id TEXT PRIMARY KEY,
                item_url TEXT NOT NULL,
                title TEXT NOT NULL,
                price REAL,
                processed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun contains(itemId: String): Boolean {
        readableDatabase.query(
            "processed_items",
            arrayOf("item_id"),
            "item_id = ?",
            arrayOf(itemId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun markProcessed(item: KongfzItem) {
        val values = ContentValues().apply {
            put("item_id", item.itemId)
            put("item_url", item.itemUrl)
            put("title", item.title)
            item.price?.let { put("price", it) }
            put("processed_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "processed_items",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun clear() {
        writableDatabase.delete("processed_items", null, null)
    }
}
