package com.mediadrop.app.di

import com.mediadrop.app.data.repository.DownloadRepositoryImpl
import com.mediadrop.app.data.repository.MediaRepositoryImpl
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
