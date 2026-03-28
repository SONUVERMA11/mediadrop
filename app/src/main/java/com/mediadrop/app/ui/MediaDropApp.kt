package com.mediadrop.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.*
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.mediadrop.app.ui.downloads.DownloadsScreen
import com.mediadrop.app.ui.home.HomeScreen
import com.mediadrop.app.ui.settings.SettingsScreen
import com.mediadrop.app.ui.splash.SplashScreen
import com.mediadrop.app.ui.theme.*

sealed class Screen(val route: String, val label: String) {
    object Splash    : Screen("splash",    "Splash")
    object Home      : Screen("home",      "Home")
    object Downloads : Screen("downloads", "Downloads")
    object Settings  : Screen("settings",  "Settings")
}

@Composable
fun MediaDropApp(sharedUrl: String? = null) {
    MediaDropTheme {
        val navController = rememberNavController()

        NavHost(
            navController  = navController,
            startDestination = Screen.Splash.route
        ) {
            // Splash — no bottom bar
            composable(
                Screen.Splash.route,
                enterTransition = { fadeIn() },
                exitTransition  = { fadeOut() }
            ) {
                SplashScreen(
                    onFinished = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            // Main scaffold with bottom nav
            composable(Screen.Home.route)      { MainScaffold(navController, sharedUrl) }
            composable(Screen.Downloads.route) { MainScaffold(navController, null, Screen.Downloads) }
            composable(Screen.Settings.route)  { MainScaffold(navController, null, Screen.Settings) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    rootNav: NavController,
    sharedUrl: String? = null,
    initialTab: Screen = Screen.Home
) {
    val tabNav = rememberNavController()

    Scaffold(
        modifier  = Modifier.fillMaxSize(),
        containerColor = Navy950,
        bottomBar = {
            NavigationBar(
                containerColor = Navy900,
                tonalElevation = 0.dp
            ) {
                val navBackStack by tabNav.currentBackStackEntryAsState()
                val currentDest  = navBackStack?.destination

                listOf(
                    Triple(Screen.Home,      Icons.Filled.Home,          Icons.Outlined.Home),
                    Triple(Screen.Downloads, Icons.Filled.CloudDownload,  Icons.Outlined.CloudDownload),
                    Triple(Screen.Settings,  Icons.Filled.Settings,       Icons.Outlined.Settings)
                ).forEach { (screen, filledIcon, outlineIcon) ->
                    val selected = currentDest?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick  = {
                            tabNav.navigate(screen.route) {
                                popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) filledIcon else outlineIcon,
                                contentDescription = screen.label,
                                tint = if (selected) Gold500 else Grey400
                            )
                        },
                        label = {
                            Text(
                                screen.label,
                                color      = if (selected) Gold500 else Grey400,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                style      = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Gold500.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = tabNav,
            startDestination = initialTab.route,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                val vm = androidx.hilt.navigation.compose.hiltViewModel<com.mediadrop.app.ui.home.HomeViewModel>()
                LaunchedEffect(sharedUrl) {
                    if (!sharedUrl.isNullOrBlank()) vm.setUrlFromShare(sharedUrl)
                }
                HomeScreen(
                    viewModel = vm,
                    onNavigateToDownloads = {
                        tabNav.navigate(Screen.Downloads.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Screen.Downloads.route) { DownloadsScreen() }
            composable(Screen.Settings.route)  { SettingsScreen() }
        }
    }
}
