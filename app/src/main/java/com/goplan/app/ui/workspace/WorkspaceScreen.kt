package com.goplan.app.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.Workspace
import com.goplan.app.ui.workspace.components.FilterChipsRow
import com.goplan.app.ui.workspace.components.IdeaItemRow
import com.goplan.app.ui.workspace.components.QuickCaptureBar

@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onNavigateToContext: (workspaceId: String) -> Unit,
    onNavigateToPlan: (workspaceId: String, ideaId: String, planPath: String) -> Unit,
    onExpandWithGemini: (idea: Idea) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    WorkspaceScreenContent(
        uiState = uiState,
        onSwitchWorkspace = { viewModel.switchWorkspace(it) },
        onCreateWorkspace = { viewModel.createWorkspace(it) },
        onAddIdea = { viewModel.addIdea(it) },
        onCycleStatus = { viewModel.cycleStatus(it) },
        onDiscardIdea = { viewModel.discardIdea(it) },
        onToggleStatusFilter = { viewModel.toggleStatusFilter(it) },
        onClearStatusFilters = { viewModel.clearStatusFilter() },
        onToggleTagFilter = { viewModel.toggleTagFilter(it) },
        onNavigateToContext = onNavigateToContext,
        onNavigateToPlan = onNavigateToPlan,
        onExpandWithGemini = onExpandWithGemini,
        onExport = onExport,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreenContent(
    uiState: WorkspaceUiState,
    onSwitchWorkspace: (Workspace) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onAddIdea: (String) -> Unit,
    onCycleStatus: (Idea) -> Unit,
    onDiscardIdea: (Idea) -> Unit,
    onToggleStatusFilter: (IdeaStatus) -> Unit,
    onClearStatusFilters: () -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onNavigateToContext: (workspaceId: String) -> Unit,
    onNavigateToPlan: (workspaceId: String, ideaId: String, planPath: String) -> Unit,
    onExpandWithGemini: (idea: Idea) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var workspaceDropdownExpanded by remember { mutableStateOf(false) }
    var showCreateWorkspaceDialog by remember { mutableStateOf(false) }
    var newWorkspaceName by remember { mutableStateOf("") }

    if (showCreateWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateWorkspaceDialog = false
                newWorkspaceName = ""
            },
            title = { Text("Nuevo Workspace") },
            text = {
                OutlinedTextField(
                    value = newWorkspaceName,
                    onValueChange = { newWorkspaceName = it },
                    label = { Text("Nombre del workspace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newWorkspaceName.trim()
                        if (name.isNotBlank()) {
                            onCreateWorkspace(name)
                            newWorkspaceName = ""
                            showCreateWorkspaceDialog = false
                        }
                    },
                    enabled = newWorkspaceName.trim().isNotBlank()
                ) {
                    Text("Crear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateWorkspaceDialog = false
                        newWorkspaceName = ""
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { workspaceDropdownExpanded = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.activeWorkspace?.name ?: "GoPlan",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Cambiar workspace"
                            )
                        }

                        DropdownMenu(
                            expanded = workspaceDropdownExpanded,
                            onDismissRequest = { workspaceDropdownExpanded = false }
                        ) {
                            uiState.workspaces.forEach { ws ->
                                DropdownMenuItem(
                                    text = { Text(ws.name) },
                                    onClick = {
                                        workspaceDropdownExpanded = false
                                        onSwitchWorkspace(ws)
                                    },
                                    leadingIcon = if (ws.id == uiState.activeWorkspace?.id) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("+ Crear Workspace") },
                                onClick = {
                                    workspaceDropdownExpanded = false
                                    showCreateWorkspaceDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                            )
                        }
                    }
                },
                actions = {
                    // CONTEXT.md action
                    IconButton(
                        onClick = {
                            uiState.activeWorkspace?.let { onNavigateToContext(it.id) }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Contexto (CONTEXT.md)"
                        )
                    }

                    // Export action
                    IconButton(onClick = onExport) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Exportar para agentes"
                        )
                    }
                }
            )
        },
        bottomBar = {
            QuickCaptureBar(
                availableTags = uiState.availableTags,
                onSendIdea = onAddIdea
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            FilterChipsRow(
                selectedStatuses = uiState.selectedStatuses,
                onToggleStatus = onToggleStatusFilter,
                onClearStatusFilters = onClearStatusFilters,
                availableTags = uiState.availableTags,
                selectedTags = uiState.selectedTags,
                onToggleTag = onToggleTagFilter
            )

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.ideas.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.allIdeas.isEmpty()) {
                            "No hay ideas en este workspace.\n¡Escribe una idea abajo para comenzar!"
                        } else {
                            "No hay ideas que coincidan con los filtros seleccionados."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = uiState.ideas,
                        key = { index, idea -> "${idea.id}_$index" }
                    ) { _, idea ->
                        IdeaItemRow(
                            idea = idea,
                            onCycleStatus = onCycleStatus,
                            onDiscard = onDiscardIdea,
                            onOpenPlan = { clickedIdea, plan ->
                                uiState.activeWorkspace?.let { ws ->
                                    onNavigateToPlan(ws.id, clickedIdea.id, plan.relativePath)
                                }
                            },
                            onExpandWithGemini = onExpandWithGemini
                        )
                    }
                }
            }
        }
    }
}
