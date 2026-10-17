package com.goplan.app.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.model.WorkspaceSummary
import com.goplan.app.data.update.UpdateCheckResult
import com.goplan.app.data.update.UpdateDownloadState
import com.goplan.app.data.update.UpdateInfo
import com.goplan.app.ui.server.ServerConnectionDialog
import com.goplan.app.ui.sync.GoogleDriveSyncDialog
import com.goplan.app.ui.update.UpdateNotificationDialog
import com.goplan.app.ui.workspace.components.EditIdeaDialog
import com.goplan.app.ui.workspace.components.FilterChipsRow
import com.goplan.app.ui.workspace.components.IdeaItemRow
import com.goplan.app.ui.workspace.components.QuickCaptureBar

@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onNavigateToContext: (workspaceId: String) -> Unit,
    onNavigateToPlan: (workspaceId: String, ideaId: String, planPath: String) -> Unit,
    onExpandWithGemini: (idea: Idea) -> Unit,
    onPastePlan: () -> Unit,
    onCreatePlan: (Idea) -> Unit,
    onExport: () -> Unit,
    onSignInGoogle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val signedInAccount by viewModel.signedInAccount.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val syncSuccessMessage by viewModel.syncSuccessMessage.collectAsState()
    val updateCheckResult by viewModel.updateCheckResult.collectAsState()
    val updateDownloadState by viewModel.updateDownloadState.collectAsState()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsState()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val showWorkspaceSheet by viewModel.showWorkspaceSheet.collectAsState()
    val workspaceSummaries by viewModel.workspaceSummaries.collectAsState()

    val view = LocalView.current
    DisposableEffect(isServerRunning) {
        view.keepScreenOn = isServerRunning
        onDispose {
            view.keepScreenOn = false
        }
    }

    WorkspaceScreenContent(
        uiState = uiState,
        isServerRunning = isServerRunning,
        serverUrl = serverUrl,
        signedInAccount = signedInAccount,
        isSyncing = isSyncing,
        lastSyncTimestamp = lastSyncTimestamp,
        syncError = syncError,
        syncSuccessMessage = syncSuccessMessage,
        sha1Fingerprint = viewModel.getAppSha1Fingerprint(),
        updateCheckResult = updateCheckResult,
        updateDownloadState = updateDownloadState,
        showUpdateDialog = showUpdateDialog,
        isCheckingUpdate = isCheckingUpdate,
        showWorkspaceSheet = showWorkspaceSheet,
        workspaceSummaries = workspaceSummaries,
        onOpenWorkspaceSheet = { viewModel.openWorkspaceSheet() },
        onCloseWorkspaceSheet = { viewModel.closeWorkspaceSheet() },
        onOpenUpdateDialog = { viewModel.openUpdateDialog() },
        onDismissUpdateDialog = { viewModel.dismissUpdateDialog() },
        onDownloadUpdate = { info -> viewModel.downloadAndInstallUpdate(info) },
        onInstallDownloadedApk = { file -> viewModel.installDownloadedApk(file) },
        onCheckForUpdatesManually = { viewModel.checkForUpdates(silent = false) },
        onSignInGoogle = onSignInGoogle,
        onSyncNow = { viewModel.syncAllWorkspaces() },
        onSyncActiveWorkspace = { viewModel.syncActiveWorkspace() },
        onSignOutGoogle = { viewModel.signOutGoogle() },
        onToggleServer = { viewModel.toggleServer(it) },
        onSwitchWorkspace = { viewModel.switchWorkspace(it) },
        onCreateWorkspace = { viewModel.createAndSwitchWorkspace(it) },
        onAddIdea = { viewModel.addIdea(it) },
        onCycleStatus = { viewModel.cycleStatus(it) },
        onDiscardIdea = { viewModel.discardIdea(it) },
        onToggleStatusFilter = { viewModel.toggleStatusFilter(it) },
        onClearStatusFilters = { viewModel.clearStatusFilter() },
        onToggleTagFilter = { viewModel.toggleTagFilter(it) },
        onNavigateToContext = onNavigateToContext,
        onNavigateToPlan = onNavigateToPlan,
        onExpandWithGemini = onExpandWithGemini,
        onPastePlan = onPastePlan,
        onCreatePlan = onCreatePlan,
        onEditIdea = { idea, title, tags -> viewModel.editIdea(idea, title, tags) },
        onDeleteIdeaPermanently = { idea -> viewModel.deleteIdeaPermanently(idea) },
        onExport = onExport,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreenContent(
    uiState: WorkspaceUiState,
    isServerRunning: Boolean = false,
    serverUrl: String? = null,
    signedInAccount: GoogleSignInAccount? = null,
    isSyncing: Boolean = false,
    lastSyncTimestamp: Long? = null,
    syncError: String? = null,
    syncSuccessMessage: String? = null,
    sha1Fingerprint: String = "No disponible",
    updateCheckResult: UpdateCheckResult? = null,
    updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    showUpdateDialog: Boolean = false,
    isCheckingUpdate: Boolean = false,
    onOpenUpdateDialog: () -> Unit = {},
    onDismissUpdateDialog: () -> Unit = {},
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onInstallDownloadedApk: (java.io.File) -> Unit = {},
    onCheckForUpdatesManually: () -> Unit = {},
    onSignInGoogle: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onSyncActiveWorkspace: () -> Unit = {},
    onSignOutGoogle: () -> Unit = {},
    onToggleServer: (Boolean) -> Unit = {},
    showWorkspaceSheet: Boolean = false,
    workspaceSummaries: List<WorkspaceSummary> = emptyList(),
    onOpenWorkspaceSheet: () -> Unit = {},
    onCloseWorkspaceSheet: () -> Unit = {},
    onSwitchWorkspace: (Workspace) -> Unit,
    onCreateWorkspace: (String) -> Result<Workspace> = { Result.failure(NotImplementedError()) },
    onAddIdea: (String) -> Unit,
    onCycleStatus: (Idea) -> Unit,
    onDiscardIdea: (Idea) -> Unit,
    onToggleStatusFilter: (IdeaStatus) -> Unit,
    onClearStatusFilters: () -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onNavigateToContext: (workspaceId: String) -> Unit,
    onNavigateToPlan: (workspaceId: String, ideaId: String, planPath: String) -> Unit,
    onExpandWithGemini: (idea: Idea) -> Unit,
    onPastePlan: () -> Unit,
    onCreatePlan: (Idea) -> Unit,
    onEditIdea: (Idea, String, Set<String>) -> Unit = { _, _, _ -> },
    onDeleteIdeaPermanently: (Idea) -> Unit = {},
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showServerDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var ideaToEdit by remember { mutableStateOf<Idea?>(null) }
    var ideaToDelete by remember { mutableStateOf<Idea?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    WorkspaceChip(
                        workspaceName = uiState.activeWorkspace?.name ?: "GoPlan",
                        onClick = onOpenWorkspaceSheet
                    )
                },
                actions = {
                    // Google Drive sync action
                    IconButton(onClick = { showSyncDialog = true }) {
                        val icon = when {
                            isSyncing -> Icons.Default.CloudSync
                            signedInAccount != null -> Icons.Default.CloudDone
                            else -> Icons.Default.CloudQueue
                        }
                        val tint = when {
                            isSyncing -> MaterialTheme.colorScheme.primary
                            signedInAccount != null -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Sincronización con Google Drive",
                            tint = tint
                        )
                    }

                    // In-App update available badge/action
                    if (updateCheckResult is UpdateCheckResult.UpdateAvailable) {
                        IconButton(onClick = onOpenUpdateDialog) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Actualización disponible",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Server connection action
                    IconButton(onClick = { showServerDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Lan,
                            contentDescription = "Conexión PC / Servidor Local",
                            tint = if (isServerRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Paste plan from clipboard action
                    IconButton(onClick = onPastePlan) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Pegar plan"
                        )
                    }

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
                val listState = rememberLazyListState()
                @OptIn(ExperimentalLayoutApi::class)
                val isImeVisible = WindowInsets.isImeVisible

                LaunchedEffect(uiState.ideas.size, isImeVisible) {
                    if (uiState.ideas.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.ideas.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
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
                            onExpandWithGemini = onExpandWithGemini,
                            onCreatePlan = onCreatePlan,
                            onEdit = { ideaToEdit = it },
                            onDeletePermanently = { ideaToDelete = it }
                        )
                    }
                }
            }
        }
    }

    ideaToEdit?.let { idea ->
        EditIdeaDialog(
            idea = idea,
            onConfirm = { newTitle, newTags ->
                onEditIdea(idea, newTitle, newTags)
                ideaToEdit = null
            },
            onDismiss = { ideaToEdit = null }
        )
    }

    ideaToDelete?.let { idea ->
        AlertDialog(
            onDismissRequest = { ideaToDelete = null },
            title = { Text("Eliminar Tarea") },
            text = {
                val hasPlans = idea.plans.isNotEmpty()
                Text(
                    text = if (hasPlans) {
                        "¿Deseas eliminar definitivamente \"${idea.title}\"?\nSe borrará de TODO.md y se eliminarán sus ${idea.plans.size} plan(es) asociado(s)."
                    } else {
                        "¿Deseas eliminar definitivamente \"${idea.title}\"?\nSe borrará de TODO.md."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = idea
                        ideaToDelete = null
                        onDeleteIdeaPermanently(toDelete)
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { ideaToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showWorkspaceSheet) {
        WorkspaceSelectionBottomSheet(
            summaries = workspaceSummaries,
            onSelectWorkspace = { ws ->
                onSwitchWorkspace(ws)
                onCloseWorkspaceSheet()
            },
            onCreateWorkspace = onCreateWorkspace,
            onDismiss = onCloseWorkspaceSheet
        )
    }

    if (showServerDialog) {
        ServerConnectionDialog(
            isRunning = isServerRunning,
            serverUrl = serverUrl,
            onToggleServer = onToggleServer,
            onDismiss = { showServerDialog = false },
            onCheckUpdates = onCheckForUpdatesManually,
            isCheckingUpdates = isCheckingUpdate
        )
    }

    if (showSyncDialog) {
        GoogleDriveSyncDialog(
            account = signedInAccount,
            isSyncing = isSyncing,
            lastSyncTimestamp = lastSyncTimestamp,
            syncError = syncError,
            syncSuccessMessage = syncSuccessMessage,
            sha1Fingerprint = sha1Fingerprint,
            activeWorkspaceName = uiState.activeWorkspace?.name,
            totalWorkspacesCount = uiState.workspaces.size,
            onSignInGoogle = onSignInGoogle,
            onSyncNow = onSyncNow,
            onSyncActiveWorkspace = onSyncActiveWorkspace,
            onSignOutGoogle = onSignOutGoogle,
            onDismiss = { showSyncDialog = false }
        )
    }

    if (showUpdateDialog && updateCheckResult is UpdateCheckResult.UpdateAvailable) {
        UpdateNotificationDialog(
            updateInfo = updateCheckResult.updateInfo,
            downloadState = updateDownloadState,
            onDismissRequest = onDismissUpdateDialog,
            onDownloadClick = onDownloadUpdate,
            onInstallClick = onInstallDownloadedApk
        )
    }
}
