package com.mediadrop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediadrop.app.ui.downloads.DownloadsScreen
import com.mediadrop.app.ui.home.HomeScreen
import com.mediadrop.app.ui.settings.SettingsScreen
import com.mediadrop.app.ui.theme.MediaDropTheme

sealed class Screen(val route: String, val label: String) {
    object Home : Screen("home", "Home")
    object Downloads : Screen("downloads", "Downloads")
    object Settings : Screen("settings", "Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDropApp(
    startRoute: String = Screen.Home.route,
    sharedUrl: String? = null
) {
    MediaDropTheme {
        val navController = rememberNavController()
        val navBackStack by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStack?.destination

        val navItems = listOf(
            Triple(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home),
            Triple(Screen.Downloads, Icons.Filled.CloudDownload, Icons.Outlined.CloudDownload),
            Triple(Screen.Settings, Icons.Filled.Settings, Icons.Outlined.Settings)
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    navItems.forEach { (screen, filledIcon, outlineIcon) ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) filledIcon else outlineIcon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startRoute,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToDownloads = {
                            navController.navigate(Screen.Downloads.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable(Screen.Downloads.route) {
                    DownloadsScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
            }
        }
    }
}
