package com.mediadrop.app.data.remote.api

import com.mediadrop.app.data.remote.dto.DownloadUrlDto
import com.mediadrop.app.data.remote.dto.MediaInfoDto
import com.mediadrop.app.data.remote.dto.PlaylistInfoDto
import retrofit2.http.GET
import retrofit2.http.Query

interface MediaApiService {

    @GET("info")
    suspend fun getMediaInfo(
        @Query("url") url: String
    ): MediaInfoDto

    @GET("download-url")
    suspend fun getDownloadUrl(
        @Query("url")       url: String,
        @Query("format_id") formatId: String
    ): DownloadUrlDto

    @GET("playlist-info")
    suspend fun getPlaylistInfo(
        @Query("url") url: String
    ): PlaylistInfoDto
}
