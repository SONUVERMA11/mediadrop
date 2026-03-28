package com.mediadrop.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mediadrop.app.data.local.dao.DownloadDao
import com.mediadrop.app.data.local.entity.DownloadEntity

@Database(
    entities = [DownloadEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val DATABASE_NAME = "mediadrop_db"
    }
}
