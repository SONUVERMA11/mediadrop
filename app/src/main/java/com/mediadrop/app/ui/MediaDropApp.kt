package com.mediadrop.app.ui

import android.os.Build
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.mediadrop.app.ui.downloads.DownloadsScreen
import com.mediadrop.app.ui.home.HomeScreen
import com.mediadrop.app.ui.home.HomeViewModel
import com.mediadrop.app.ui.settings.PrefKeys
import com.mediadrop.app.ui.settings.SettingsScreen
import com.mediadrop.app.ui.settings.ThemeMode
import com.mediadrop.app.ui.splash.SplashScreen
import com.mediadrop.app.ui.theme.*
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// ── Route constants ──────────────────────────────────────────────────────────
object Routes {
    const val SPLASH    = "splash"
    const val MAIN      = "main"
    const val HOME      = "home"
    const val DOWNLOADS = "downloads"
    const val SETTINGS  = "settings"
}

// ─────────────────────────────────────────────────────────────────────────────
// MediaDropApp — reads theme preference ONCE at top level, wraps everything
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaDropApp(
    dataStore : DataStore<Preferences>,
    sharedUrl : String? = null,
    autoFetch  : Boolean = false
) {
    val systemDark  = isSystemInDarkTheme()

    // Collect theme mode from DataStore directly (avoids needing a whole ViewModel here)
    val themeModeName by dataStore.data
        .map { it[PrefKeys.THEME_MODE] ?: ThemeMode.DARK.name }
        .collectAsState(initial = ThemeMode.DARK.name)

    val isDark = when (runCatching { ThemeMode.valueOf(themeModeName) }.getOrElse { ThemeMode.DARK }) {
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
        ThemeMode.SYSTEM -> systemDark
    }

    MediaDropTheme(darkTheme = isDark) {
        val rootNav = rememberNavController()

        NavHost(
            navController    = rootNav,
            startDestination = Routes.SPLASH
        ) {
            composable(
                Routes.SPLASH,
                enterTransition = { fadeIn() },
                exitTransition  = { fadeOut() }
            ) {
                SplashScreen(
                    onFinished = {
                        rootNav.navigate(Routes.MAIN) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }
                )
            }

            // ONE permanent main destination — tab nav lives entirely inside here
            composable(Routes.MAIN) {
                MainScreen(sharedUrl = sharedUrl, autoFetch = autoFetch)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MainScreen — owns the inner tab NavController, never re-created between tabs
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MainScreen(sharedUrl: String?, autoFetch: Boolean = false) {
    val tabNav = rememberNavController()

    val tabs = listOf(
        TabDef(Routes.HOME,      Icons.Filled.Home,          Icons.Outlined.Home,          "Home"),
        TabDef(Routes.DOWNLOADS, Icons.Filled.CloudDownload, Icons.Outlined.CloudDownload, "Downloads"),
        TabDef(Routes.SETTINGS,  Icons.Filled.Settings,      Icons.Outlined.Settings,      "Settings")
    )

    Scaffold(
        modifier       = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar      = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                val backStack   by tabNav.currentBackStackEntryAsState()
                val currentDest  = backStack?.destination

                tabs.forEach { tab ->
                    val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick  = {
                            if (!selected) {
                                tabNav.navigate(tab.route) {
                                    popUpTo(tabNav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        },
                        icon  = {
                            Icon(
                                imageVector        = if (selected) tab.filledIcon else tab.outlineIcon,
                                contentDescription = tab.label,
                                tint               = if (selected) MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Text(
                                text       = tab.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                style      = MaterialTheme.typography.labelSmall,
                                color      = if (selected) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = tabNav,
            startDestination = Routes.HOME,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = hiltViewModel()
                LaunchedEffect(sharedUrl) {
                    if (!sharedUrl.isNullOrBlank()) {
                        vm.setUrlFromShare(sharedUrl)
                    }
                }
                LaunchedEffect(autoFetch) {
                    // Triggered from ShareReceiverActivity — auto-fetch after URL is set
                    if (autoFetch && !sharedUrl.isNullOrBlank()) vm.fetchMedia()
                }
                HomeScreen(
                    viewModel             = vm,
                    onNavigateToDownloads = {
                        tabNav.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
                    }
                )
            }
            composable(Routes.DOWNLOADS) { DownloadsScreen() }
            composable(Routes.SETTINGS)  { SettingsScreen() }
        }
    }
}

private data class TabDef(
    val route       : String,
    val filledIcon  : androidx.compose.ui.graphics.vector.ImageVector,
    val outlineIcon : androidx.compose.ui.graphics.vector.ImageVector,
    val label       : String
)
