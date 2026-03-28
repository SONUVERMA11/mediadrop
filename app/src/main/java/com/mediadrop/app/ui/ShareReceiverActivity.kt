package com.mediadrop.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedUrl.isNullOrBlank() && URLUtil.isValidUrl(sharedUrl)) {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("SHARED_URL", sharedUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
        finish()
    }
}
