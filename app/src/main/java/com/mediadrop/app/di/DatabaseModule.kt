package com.mediadrop.app.di

import android.content.Context
import androidx.room.Room
import com.mediadrop.app.data.local.dao.DownloadDao
import com.mediadrop.app.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()   // safety net for any other version jumps
            .build()

    @Provides
    fun provideDownloadDao(database: AppDatabase): DownloadDao =
        database.downloadDao()
}
