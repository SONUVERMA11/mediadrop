package com.mediadrop.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkUtils @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

// ── Error types ─────────────────────────────────────────────────────────────

sealed class MediaError(message: String) : Exception(message) {
    object NoInternet : MediaError("No internet connection")
    object UnsupportedUrl : MediaError("Unsupported URL")
    object GeoRestricted : MediaError("Geo-restricted content")
    object PrivateContent : MediaError("Private content")
    object RateLimited : MediaError("Rate limited")
    object ParseFailed : MediaError("Parse failed")
    object StorageFull : MediaError("Storage full")
    object FormatUnavailable : MediaError("Format unavailable")
    data class Unknown(val cause: Throwable) : MediaError(cause.message ?: "Unknown error")

    fun userMessage(): String = when (this) {
        is NoInternet -> "No internet connection. Check your network."
        is UnsupportedUrl -> "This link is not supported yet."
        is GeoRestricted -> "This content is not available in your region."
        is PrivateContent -> "This content is private or login-protected."
        is RateLimited -> "Too many requests. Please wait a moment."
        is ParseFailed -> "Could not extract media info. Try again."
        is StorageFull -> "Not enough storage space."
        is FormatUnavailable -> "Selected format is no longer available."
        is Unknown -> "An unexpected error occurred. Please try again."
    }
}

fun Throwable.toMediaError(): MediaError {
    val msg = message?.lowercase() ?: ""
    return when {
        msg.contains("geo") || msg.contains("region") || msg.contains("country") ->
            MediaError.GeoRestricted
        msg.contains("private") || msg.contains("login") || msg.contains("auth") ->
            MediaError.PrivateContent
        msg.contains("rate") || msg.contains("429") ->
            MediaError.RateLimited
        msg.contains("unsupported") || msg.contains("no video formats") ->
            MediaError.UnsupportedUrl
        msg.contains("space") || msg.contains("storage") ->
            MediaError.StorageFull
        msg.contains("format") ->
            MediaError.FormatUnavailable
        else -> MediaError.Unknown(this)
    }
}
