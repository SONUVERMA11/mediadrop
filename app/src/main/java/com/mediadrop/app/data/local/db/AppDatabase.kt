package com.mediadrop.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mediadrop.app.data.local.dao.DownloadDao
import com.mediadrop.app.data.local.entity.DownloadEntity

@Database(
    entities  = [DownloadEntity::class],
    version   = 2,               // bumped: added formatId + hasAudio columns
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val DATABASE_NAME = "mediadrop_db"

        /** Non-destructive migration: add the two new columns with safe defaults */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN formatId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN hasAudio INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
