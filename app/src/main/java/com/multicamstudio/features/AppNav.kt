package com.multicamstudio.features

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.multicamstudio.AppContainer
import com.multicamstudio.features.diagnostics.DiagnosticsScreen
import com.multicamstudio.features.recording.RecordScreen
import com.multicamstudio.features.sessions.SessionDetailScreen
import com.multicamstudio.features.sessions.SessionsScreen
import com.multicamstudio.features.settings.SettingsScreen
import com.multicamstudio.features.uploads.UploadsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("record", "Record", Icons.Filled.Videocam),
    Tab("sessions", "Sessions", Icons.Filled.VideoLibrary),
    Tab("uploads", "Uploads", Icons.Filled.CloudUpload),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav(
    container: AppContainer,
    deviceRotation: () -> Int,
    permissionsVersion: Int,
    requestPermissions: () -> Unit,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "record", modifier = Modifier.padding(padding)) {
            composable("record") { RecordScreen(container, deviceRotation, permissionsVersion, requestPermissions) }
            composable("sessions") { SessionsScreen(container, snackbar, onOpen = { nav.navigate("session/$it") }) }
            composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                SessionDetailScreen(container, entry.arguments?.getString("id").orEmpty(), snackbar, onBack = { nav.popBackStack() })
            }
            composable("uploads") { UploadsScreen(container, snackbar) }
            composable("settings") { SettingsScreen(container, snackbar, onDiagnostics = { nav.navigate("diagnostics") }) }
            composable("diagnostics") { DiagnosticsScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
