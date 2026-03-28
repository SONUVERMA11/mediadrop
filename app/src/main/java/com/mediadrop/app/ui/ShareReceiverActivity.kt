package com.mediadrop.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Receives "Share" intents from other apps.
 * Redirects to MainActivity with the URL pre-filled and auto-fetch enabled
 * so the format picker pop-up appears immediately.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""

        // Extract first valid URL from the shared text (handles "Check this: https://... via ...")
        val url = sharedText.split("\\s+".toRegex())
            .firstOrNull { URLUtil.isValidUrl(it) }
            ?: if (URLUtil.isValidUrl(sharedText)) sharedText else null

        if (!url.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra("SHARED_URL", url)
                    putExtra("AUTO_FETCH", true)      // triggers format picker immediately
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
        finish()
    }
}
