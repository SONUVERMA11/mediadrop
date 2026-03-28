package com.mediadrop.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl = intent?.getStringExtra("SHARED_URL")
        val navigateToDownloads = intent?.getBooleanExtra("navigate_to_downloads", false) ?: false

        setContent {
            MediaDropApp(
                startRoute = if (navigateToDownloads) Screen.Downloads.route else Screen.Home.route,
                sharedUrl = sharedUrl
            )
        }
    }
}
