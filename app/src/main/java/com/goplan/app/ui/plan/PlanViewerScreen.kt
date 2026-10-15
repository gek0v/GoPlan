package com.goplan.app.ui.plan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goplan.app.ui.plan.components.PlanMarkdownViewer
import com.goplan.app.ui.plan.components.SplitPlanDialog
import com.goplan.app.ui.plan.components.suggestPlanFileName

@Composable
fun PlanViewerScreen(
    viewModel: PlanViewerViewModel,
    workspaceId: String,
    ideaId: String,
    initialPlanPath: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(workspaceId, ideaId, initialPlanPath) {
        viewModel.load(workspaceId, ideaId, initialPlanPath)
    }

    PlanViewerScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleEditMode = { viewModel.toggleEditMode() },
        onSavePlan = { viewModel.saveCurrentPlan() },
        onSelectPlan = { viewModel.selectPlan(it) },
        onUpdateContent = { viewModel.updateContent(it) },
        onSplitPlan = { cursor, name -> viewModel.splitPlanFromCursor(cursor, name) },
        onDeletePlan = {
            viewModel.deleteActivePlan { hasRemaining ->
                if (!hasRemaining) {
                    onNavigateBack()
                }
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanViewerScreenContent(
    uiState: PlanViewerUiState,
    onNavigateBack: () -> Unit,
    onToggleEditMode: () -> Unit,
    onSavePlan: () -> Unit,
    onSelectPlan: (Int) -> Unit,
    onUpdateContent: (String) -> Unit,
    onSplitPlan: (cursorIndex: Int, newFileName: String) -> Unit,
    onDeletePlan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSplitDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentCursorPosition by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.idea?.title ?: "Plan",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    if (uiState.isEditMode) {
                        IconButton(onClick = { showSplitDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CallSplit,
                                contentDescription = "Dividir desde el cursor"
                            )
                        }
                    }

                    IconButton(onClick = onToggleEditMode) {
                        Icon(
                            imageVector = if (uiState.isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (uiState.isEditMode) "Ver formato" else "Editar Markdown"
                        )
                    }

                    IconButton(onClick = onSavePlan, enabled = !uiState.isSaving) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Guardar plan"
                        )
                    }

                    if (uiState.activePlan != null) {
                        IconButton(onClick = { showDeleteDialog = true }, enabled = !uiState.isSaving) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Eliminar plan",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.plans.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = uiState.activePlanIndex.coerceIn(0, uiState.plans.size - 1),
                    edgePadding = 16.dp,
                    divider = { HorizontalDivider() }
                ) {
                    uiState.plans.forEachIndexed { index, plan ->
                        Tab(
                            selected = index == uiState.activePlanIndex,
                            onClick = { onSelectPlan(index) },
                            text = {
                                Text(
                                    text = plan.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                PlanMarkdownViewer(
                    content = uiState.planContent,
                    isEditMode = uiState.isEditMode,
                    onContentChange = onUpdateContent,
                    onCursorPositionChange = { currentCursorPosition = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showSplitDialog) {
        val splitText = if (uiState.planContent.isNotEmpty()) {
            val validCursor = currentCursorPosition.coerceIn(0, uiState.planContent.length)
            uiState.planContent.substring(validCursor)
        } else {
            ""
        }
        val suggestedName = remember(splitText) { suggestPlanFileName(splitText) }

        SplitPlanDialog(
            suggestedFileName = suggestedName,
            onConfirm = { fileName ->
                showSplitDialog = false
                onSplitPlan(currentCursorPosition, fileName)
            },
            onDismiss = { showSplitDialog = false }
        )
    }

    if (showDeleteDialog) {
        val planLabel = uiState.activePlan?.label ?: "este plan"
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar plan") },
            text = { Text("¿Deseas eliminar '$planLabel'? Se borrará el archivo de disco y se desvinculará de la tarea.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeletePlan()
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
