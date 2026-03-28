package com.mediadrop.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl  = intent?.getStringExtra("SHARED_URL")
        val autoFetch  = intent?.getBooleanExtra("AUTO_FETCH", false) ?: false

        setContent {
            MediaDropApp(
                dataStore  = dataStore,
                sharedUrl  = sharedUrl,
                autoFetch  = autoFetch
            )
        }
    }
}
