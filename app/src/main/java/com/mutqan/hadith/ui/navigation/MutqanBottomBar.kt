package com.mutqan.hadith.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.mutqan.hadith.R
import com.mutqan.hadith.ui.common.LibraryIcon
import com.mutqan.hadith.ui.common.NetworkIcon

/** The destinations of the bottom bar, in the order the bar lists them; the first is where the app opens. */
enum class TopLevelTab(val route: String, @StringRes val label: Int) {
    DAILY(Routes.HOME, R.string.tab_daily),
    LIBRARY(Routes.LIBRARY, R.string.tab_library),
    INSIGHTS(Routes.INSIGHTS, R.string.tab_insights),
    ;

    companion object {
        /** The bar shows on the tabs themselves and on nothing pushed above them. */
        fun isTopLevel(route: String?): Boolean = entries.any { it.route == route }
    }
}

@Composable
fun MutqanBottomBar(currentRoute: String?, onSelect: (TopLevelTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        TopLevelTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab.route == currentRoute,
                onClick = { onSelect(tab) },
                icon = {
                    when (tab) {
                        TopLevelTab.DAILY -> Icon(Icons.Filled.Home, contentDescription = null)
                        TopLevelTab.LIBRARY -> LibraryIcon()
                        TopLevelTab.INSIGHTS -> NetworkIcon()
                    }
                },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

/**
 * Switches tab without stacking one on the other: whatever was open above the tab being left (a hadith
 * being read, say) is saved with it and comes back when the tab is chosen again; Back from any tab
 * returns to the first one.
 */
fun NavHostController.navigateToTab(tab: TopLevelTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
