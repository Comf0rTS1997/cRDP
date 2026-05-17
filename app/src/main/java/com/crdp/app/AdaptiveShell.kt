package com.crdp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Width breakpoint above which we switch to the desktop / DeX shell (Material 3 Expanded)
 * — AND a matching height floor so a phone in landscape (wide but short) stays on the
 * compact phone layout.
 */
private const val EXPANDED_WIDTH_DP = 840
private const val EXPANDED_HEIGHT_DP = 600

/**
 * True when the current window is big enough in BOTH dimensions to show the desktop
 * NavigationRail shell. Reads from [LocalConfiguration], so it tracks live resizes /
 * rotation / DeX window resizes.
 *
 * Excluding phone-landscape (≈ 891 × 411 dp on a S25/S24 class device) is intentional:
 * the master-detail UI is unusable at that aspect ratio.
 */
@Composable
fun isExpandedWindow(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp >= EXPANDED_WIDTH_DP &&
        cfg.screenHeightDp >= EXPANDED_HEIGHT_DP
}

/**
 * Adaptive top-level shell. At compact width it just renders [content] (the
 * NavHost) directly. At expanded width it wraps content in a NavigationRail +
 * main area row, with the rail hidden when the current destination is a
 * full-screen session.
 */
@Composable
fun AdaptiveAppShell(
    navController: NavController,
    content: @Composable () -> Unit,
) {
    val expanded = isExpandedWindow()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route.orEmpty()
    // The RDP session takes over the whole window — rail would be intrusive.
    val railHidden = currentRoute.startsWith("session/")

    if (!expanded || railHidden) {
        content()
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        CrdpNavigationRail(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight(),
            currentRoute = currentRoute,
            onNavigateConnections = {
                navController.navigate("connections") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onNavigateVault = {
                navController.navigate("settings/vault") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            },
            onNavigateSettings = {
                navController.navigate("settings") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            },
            onNavigateAbout = {
                navController.navigate("settings/about") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            },
            onAddConnection = { navController.navigate("editor/new") },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}

@Composable
private fun CrdpNavigationRail(
    modifier: Modifier = Modifier,
    currentRoute: String,
    onNavigateConnections: () -> Unit,
    onNavigateVault: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAbout: () -> Unit,
    onAddConnection: () -> Unit,
) {
    NavigationRail(
        modifier = modifier,
        // No header — the first rail item lines up vertically with the search bar
        // on the right pane (both sit at the top of their respective columns with
        // matching default padding).
        header = null,
    ) {
        RailItem(
            icon = Icons.Default.Monitor,
            label = "Connections",
            selected = currentRoute == "connections" ||
                currentRoute.startsWith("editor/") ||
                currentRoute.startsWith("details/"),
            onClick = onNavigateConnections,
        )
        RailItem(
            icon = Icons.Default.Lock,
            label = "Vault",
            selected = currentRoute == "settings/vault",
            onClick = onNavigateVault,
        )
        RailItem(
            icon = Icons.Default.Settings,
            label = "Settings",
            selected = currentRoute == "settings",
            onClick = onNavigateSettings,
        )
        RailItem(
            icon = Icons.Default.Info,
            label = "About",
            selected = currentRoute == "settings/about",
            onClick = onNavigateAbout,
        )

        // Push the "+ New" action to the bottom of the rail.
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            onClick = onAddConnection,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(width = 64.dp, height = 56.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New connection")
                Text(
                    text = "New",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}
