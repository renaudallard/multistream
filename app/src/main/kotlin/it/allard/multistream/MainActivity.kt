package it.allard.multistream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.feature.detail.DetailScreen
import it.allard.multistream.feature.library.LibraryScreen
import it.allard.multistream.feature.search.SearchScreen
import it.allard.multistream.feature.settings.SettingsScreen
import it.allard.multistream.nav.decodeTitleKey
import it.allard.multistream.nav.encodeTitleKey
import it.allard.multistream.ui.LocalFormFactor
import it.allard.multistream.ui.detectFormFactor
import it.allard.multistream.ui.theme.MultistreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as MultistreamApp).graph
        val formFactor = detectFormFactor(this)
        setContent {
            CompositionLocalProvider(
                LocalAppGraph provides graph,
                LocalFormFactor provides formFactor,
            ) {
                MultistreamTheme { MultistreamRoot() }
            }
        }
    }
}

private sealed class Dest(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Search : Dest("search", R.string.tab_search, Icons.Default.Search)
    data object Library : Dest("library", R.string.tab_library, Icons.AutoMirrored.Filled.List)
    data object Settings : Dest("settings", R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun MultistreamRoot() {
    val navController = rememberNavController()
    val tabs = listOf(Dest.Search, Dest.Library, Dest.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                tabs.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Search.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Search.route) {
                SearchScreen(onOpenTitle = { navController.navigate("detail/${encodeTitleKey(it)}") })
            }
            composable(Dest.Library.route) {
                LibraryScreen(onOpenTitle = { navController.navigate("detail/${encodeTitleKey(it)}") })
            }
            composable(Dest.Settings.route) { SettingsScreen() }
            composable(
                route = "detail/{key}",
                arguments = listOf(navArgument("key") { type = NavType.StringType }),
            ) { backStackEntry ->
                val key = decodeTitleKey(backStackEntry.arguments?.getString("key").orEmpty())
                DetailScreen(titleKey = key, onBack = { navController.popBackStack() })
            }
        }
    }
}
