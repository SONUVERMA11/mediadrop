package com.mediadrop.app.data.remote.api

import com.mediadrop.app.data.remote.dto.DownloadUrlDto
import com.mediadrop.app.data.remote.dto.MediaInfoDto
import retrofit2.http.GET
import retrofit2.http.Query

interface MediaApiService {

    /**
     * Fetch media info (title, thumbnail, available formats) for a given URL.
     * The backend runs yt-dlp --dump-json on the URL and returns parsed data.
     */
    @GET("info")
    suspend fun getMediaInfo(
        @Query("url") url: String
    ): MediaInfoDto

    /**
     * Get the direct CDN download URL for a specific format.
     * For audio formats requiring post-processing (mp3/flac), the backend
     * handles conversion and returns a streamable URL.
     */
    @GET("download-url")
    suspend fun getDownloadUrl(
        @Query("url") url: String,
        @Query("format_id") formatId: String
    ): DownloadUrlDto
}
