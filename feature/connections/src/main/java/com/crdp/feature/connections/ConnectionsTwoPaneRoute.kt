package com.crdp.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Desktop / DeX two-pane shell for connections.
 *  - Left: stripped-down [ConnectionListPane] with selection state.
 *  - Right: [ConnectionDetailsPaneContent] for the selected profile, or an empty hint.
 *
 * Reuses [ConnectionListViewModel] for both panes (profile list + reachability +
 * delete). No separate details ViewModel is needed because the list already has
 * every profile loaded.
 */
@Composable
fun ConnectionsTwoPaneRoute(
    viewModel: ConnectionListViewModel,
    viewMode: ConnectionViewMode,
    onOpenSession: (String) -> Unit,
    onEditConnection: (String) -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Auto-select the first profile when one becomes available so the right
    // pane isn't empty on first render.
    LaunchedEffect(profiles) {
        if (selectedId == null && profiles.isNotEmpty()) {
            selectedId = profiles.first().id
        }
        if (selectedId != null && profiles.none { it.id == selectedId }) {
            selectedId = profiles.firstOrNull()?.id
        }
    }

    val selectedProfile = remember(profiles, selectedId) {
        profiles.firstOrNull { it.id == selectedId }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            ConnectionListPane(
                profiles = profiles,
                reachability = reachability,
                viewMode = viewMode,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onEdit = onEditConnection,
                onDelete = { viewModel.delete(it) },
            )
        }

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val profile = selectedProfile
            if (profile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No connection selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Pick a machine from the list, or add a new one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ConnectionDetailsPaneContent(
                    profile = profile,
                    reachability = reachability[profile.id],
                    onConnect = { onOpenSession(profile.id) },
                    onEdit = { onEditConnection(profile.id) },
                    onDelete = {
                        val id = profile.id
                        viewModel.delete(profile)
                        if (selectedId == id) selectedId = null
                    },
                )
            }
        }
    }
}
