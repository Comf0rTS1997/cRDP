package com.crdp.feature.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.reachability.ReachabilityProbe

enum class ConnectionViewMode { List, Grid }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListRoute(
    viewModel: ConnectionListViewModel,
    onAddConnection: () -> Unit,
    onEditConnection: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewMode: ConnectionViewMode = ConnectionViewMode.List,
    onViewModeChange: (ConnectionViewMode) -> Unit = {},
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()

    ConnectionListScreen(
        profiles = profiles,
        reachability = reachability,
        onAddConnection = onAddConnection,
        onEditConnection = onEditConnection,
        onOpenSession = onOpenSession,
        onOpenDetails = onOpenDetails,
        onOpenSettings = onOpenSettings,
        onDelete = viewModel::delete,
        viewMode = viewMode,
        onViewModeChange = onViewModeChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionListScreen(
    profiles: List<ConnectionProfile>,
    reachability: Map<String, ReachabilityProbe>,
    onAddConnection: () -> Unit,
    onEditConnection: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: (ConnectionProfile) -> Unit,
    viewMode: ConnectionViewMode,
    onViewModeChange: (ConnectionViewMode) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filtered = profiles.filter { p ->
        searchQuery.isBlank() ||
            p.displayName.contains(searchQuery, ignoreCase = true) ||
            when (p) {
                is DirectConnectionProfile -> p.host.contains(searchQuery, ignoreCase = true)
                is GatewayConnectionProfile -> p.targetHost.contains(searchQuery, ignoreCase = true)
            }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddConnection,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New connection") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Top bar ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "cRDP",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    onViewModeChange(
                        if (viewMode == ConnectionViewMode.List) ConnectionViewMode.Grid else ConnectionViewMode.List,
                    )
                }) {
                    Icon(
                        imageVector = if (viewMode == ConnectionViewMode.List) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = if (viewMode == ConnectionViewMode.List) "Switch to grid" else "Switch to list",
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

            // ── Search bar ────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    val textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search connections…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        },
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Section header ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All machines",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── List / Grid ───────────────────────────────────────────
            if (viewMode == ConnectionViewMode.List) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(filtered, key = { it.id }) { profile ->
                        ConnectionListItem(
                            profile = profile,
                            reachability = reachability[profile.id],
                            onTap = { onOpenSession(profile.id) },
                            onDetails = { onOpenDetails(profile.id) },
                            onEdit = { onEditConnection(profile.id) },
                            onDelete = { onDelete(profile) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                    if (filtered.isEmpty()) {
                        item {
                            EmptyState()
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { profile ->
                        ConnectionGridItem(
                            profile = profile,
                            reachability = reachability[profile.id],
                            onTap = { onOpenSession(profile.id) },
                            onDetails = { onOpenDetails(profile.id) },
                            onEdit = { onEditConnection(profile.id) },
                            onDelete = { onDelete(profile) },
                        )
                    }
                    if (filtered.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionListItem(
    profile: ConnectionProfile,
    reachability: ReachabilityProbe?,
    onTap: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    selected: Boolean = false,
) {
    val supporting = when (profile) {
        is DirectConnectionProfile ->
            if (profile.username.isNotBlank()) "${profile.username}@${profile.host}"
            else profile.host
        is GatewayConnectionProfile -> "${profile.targetHost}:${profile.targetPort}"
    }
    val initials = profile.displayName
        .replace(Regex("[-_]"), " ")
        .split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("")
        .ifBlank { "?" }

    var menuExpanded by remember { mutableStateOf(false) }

    val rowBg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    // Custom row (not Material3 ListItem) — ListItem hits a known intrinsic-measurement
    // crash ("maxWidth(-72) must be >= minWidth(0)") in nested-pane contexts on
    // Compose BOM 2024.12.01.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarBox(initials = initials)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusDot(reachability = reachability)
        Spacer(Modifier.width(4.dp))
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Details") },
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                    onClick = { menuExpanded = false; onDetails() },
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menuExpanded = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ConnectionGridItem(
    profile: ConnectionProfile,
    reachability: ReachabilityProbe?,
    onTap: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    selected: Boolean = false,
) {
    val supporting = when (profile) {
        is DirectConnectionProfile ->
            if (profile.username.isNotBlank()) "${profile.username}@${profile.host}"
            else profile.host
        is GatewayConnectionProfile -> "${profile.targetHost}:${profile.targetPort}"
    }
    val initials = profile.displayName
        .replace(Regex("[-_]"), " ")
        .split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("")
        .ifBlank { "?" }

    var menuExpanded by remember { mutableStateOf(false) }

    val tileColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        color = tileColor,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarBox(initials = initials, size = 52.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(reachability = reachability)
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menuExpanded = false; onDetails() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pane variant of the connection list used inside the desktop / DeX two-pane shell.
 * No Scaffold, no FAB, no top branding — just a search bar + scrollable list/grid.
 *
 * Tapping a row calls [onSelect] to update the master-detail selection rather than
 * jumping straight to a session (Connect happens from the detail pane).
 */
@Composable
fun ConnectionListPane(
    profiles: List<ConnectionProfile>,
    reachability: Map<String, ReachabilityProbe>,
    viewMode: ConnectionViewMode,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filtered = profiles.filter { p ->
        searchQuery.isBlank() ||
            p.displayName.contains(searchQuery, ignoreCase = true) ||
            when (p) {
                is DirectConnectionProfile -> p.host.contains(searchQuery, ignoreCase = true)
                is GatewayConnectionProfile -> p.targetHost.contains(searchQuery, ignoreCase = true)
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                val textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search connections…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = "All machines · ${filtered.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        )

        if (viewMode == ConnectionViewMode.List) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(filtered, key = { it.id }) { profile ->
                    ConnectionListItem(
                        profile = profile,
                        reachability = reachability[profile.id],
                        onTap = { onSelect(profile.id) },
                        onDetails = { onSelect(profile.id) },
                        onEdit = { onEdit(profile.id) },
                        onDelete = { onDelete(profile) },
                        selected = profile.id == selectedId,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
                if (filtered.isEmpty()) {
                    item { EmptyState() }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { profile ->
                    ConnectionGridItem(
                        profile = profile,
                        reachability = reachability[profile.id],
                        onTap = { onSelect(profile.id) },
                        onDetails = { onSelect(profile.id) },
                        onEdit = { onEdit(profile.id) },
                        onDelete = { onDelete(profile) },
                        selected = profile.id == selectedId,
                    )
                }
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No connections yet.\nTap + to add one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AvatarBox(initials: String, size: Dp = 44.dp) {
    val bgColor = MaterialTheme.colorScheme.primaryContainer
    val textColor = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }
    }
}

@Composable
private fun StatusDot(reachability: ReachabilityProbe?) {
    val color = when {
        reachability == null -> MaterialTheme.colorScheme.outline
        reachability.online -> Color(0xFF66BB6A)
        else -> Color(0xFFE57373)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = RoundedCornerShape(50)),
    )
}
