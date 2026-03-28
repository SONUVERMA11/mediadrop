package com.mediadrop.app.domain.model

import android.net.Uri

enum class SupportedPlatform(
    val domains: List<String>,
    val displayName: String,
    val brandColor: Long
) {
    YOUTUBE(listOf("youtube.com", "youtu.be"), "YouTube", 0xFFFF0000),
    INSTAGRAM(listOf("instagram.com", "instagr.am"), "Instagram", 0xFFE1306C),
    FACEBOOK(listOf("facebook.com", "fb.watch", "fb.com"), "Facebook", 0xFF1877F2),
    TWITTER(listOf("twitter.com", "x.com", "t.co"), "X (Twitter)", 0xFF1DA1F2),
    TIKTOK(listOf("tiktok.com", "vm.tiktok.com"), "TikTok", 0xFFFF0050),
    REDDIT(listOf("reddit.com", "v.redd.it"), "Reddit", 0xFFFF4500),
    VIMEO(listOf("vimeo.com"), "Vimeo", 0xFF1AB7EA),
    DAILYMOTION(listOf("dailymotion.com", "dai.ly"), "Dailymotion", 0xFF0066DC),
    PINTEREST(listOf("pinterest.com", "pin.it"), "Pinterest", 0xFFE60023),
    SOUNDCLOUD(listOf("soundcloud.com"), "SoundCloud", 0xFFFF5500),
    TWITCH(listOf("twitch.tv", "clips.twitch.tv"), "Twitch", 0xFF9146FF),
    LINKEDIN(listOf("linkedin.com"), "LinkedIn", 0xFF0077B5),
    GENERIC(listOf(), "Media", 0xFF6200EE);

    companion object {
        fun detect(url: String): SupportedPlatform {
            return try {
                val host = Uri.parse(url).host?.removePrefix("www.") ?: return GENERIC
                entries.firstOrNull { platform ->
                    platform.domains.any { domain -> host.contains(domain) }
                } ?: GENERIC
            } catch (e: Exception) {
                GENERIC
            }
        }
    }
}
