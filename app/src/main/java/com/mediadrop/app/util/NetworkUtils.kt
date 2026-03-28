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
    data class Unknown(val originalCause: Throwable) : MediaError(originalCause.message ?: "Unknown error")

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
        // Network connectivity
        msg.contains("network") || msg.contains("unable to resolve") ||
        msg.contains("failed to connect") || msg.contains("timeout") ||
        msg.contains("sockettimeout") || msg.contains("no address")      -> MediaError.NoInternet

        // Geo restricted
        msg.contains("geo") || msg.contains("region") ||
        msg.contains("country") || msg.contains("geo_restricted")        -> MediaError.GeoRestricted

        // Private / login required
        msg.contains("private") || msg.contains("login") ||
        msg.contains("auth") || msg.contains("private_content")          -> MediaError.PrivateContent

        // Rate limited
        msg.contains("rate") || msg.contains("429") ||
        msg.contains("rate_limited") || msg.contains("too many")         -> MediaError.RateLimited

        // Unsupported URL
        msg.contains("unsupported") || msg.contains("no video formats") ||
        msg.contains("unsupported_url") || msg.contains("not support")   -> MediaError.UnsupportedUrl

        // Storage
        msg.contains("space") || msg.contains("storage")                 -> MediaError.StorageFull

        // Format unavailable
        msg.contains("format") && !msg.contains("video formats")         -> MediaError.FormatUnavailable

        // Catch-all HTTP errors — show "try again" style message
        msg.contains("http 4") || msg.contains("http 5") ||
        msg.contains("parse") || msg.contains("parse_failed")            -> MediaError.ParseFailed

        else -> MediaError.Unknown(originalCause = this)
    }
}

