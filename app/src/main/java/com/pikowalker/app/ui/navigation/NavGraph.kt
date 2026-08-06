package com.pikowalker.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pikowalker.app.ui.screens.MapScreen
import com.pikowalker.app.ui.screens.SettingsScreen
import com.pikowalker.app.ui.screens.StatsScreen
import com.pikowalker.app.viewmodel.WalkViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Map      : Screen("map",      "地圖", Icons.Filled.Map)
    object Stats    : Screen("stats",    "統計", Icons.Filled.BarChart)
    object Settings : Screen("settings", "設定", Icons.Filled.Settings)
}

val bottomNavItems = listOf(Screen.Map, Screen.Stats, Screen.Settings)

@Composable
fun PikoWalkerNavGraph(
    viewModel: WalkViewModel,
    onRequestHcPermission: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Map.route) {
                MapScreen(viewModel)
            }
            composable(Screen.Stats.route) {
                StatsScreen(viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel, onRequestHcPermission)
            }
        }
    }
}
