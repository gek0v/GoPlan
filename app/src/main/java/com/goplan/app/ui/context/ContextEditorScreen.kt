package com.goplan.app.ui.context

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goplan.app.ui.plan.components.MarkdownAuxiliaryToolbar
import com.goplan.app.ui.plan.components.PlanMarkdownReader
import com.goplan.app.ui.plan.components.applyMarkdownShortcut
import kotlinx.coroutines.launch

@Composable
fun ContextEditorScreen(
    viewModel: ContextEditorViewModel,
    workspaceId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(workspaceId) {
        viewModel.load(workspaceId)
    }

    ContextEditorScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onUpdateContent = { viewModel.updateContent(it) },
        onSaveContext = { viewModel.saveContext() },
        onImportContent = { viewModel.importContent(it) },
        onResetToDefault = { viewModel.resetToDefault() },
        onToggleEditMode = { viewModel.toggleEditMode() },
        onClearMessages = { viewModel.clearMessages() },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextEditorScreenContent(
    uiState: ContextEditorUiState,
    onNavigateBack: () -> Unit,
    onUpdateContent: (String) -> Unit,
    onSaveContext: () -> Unit,
    onImportContent: (String) -> Unit,
    onResetToDefault: () -> Unit,
    onToggleEditMode: () -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showOverwriteConfirmDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showDiscardChangesDialog by remember { mutableStateOf(false) }
    var pendingImportContent by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
                if (content != null) {
                    if (uiState.hasUnsavedChanges) {
                        pendingImportContent = content
                        showOverwriteConfirmDialog = true
                    } else {
                        onImportContent(content)
                    }
                }
            } catch (e: Exception) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Error al leer archivo: ${e.message}")
                }
            }
        }
    }

    fun handlePasteClipboard() {
        val text = clipboardManager.getText()?.text
        if (text.isNullOrBlank()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("El portapapeles está vacío")
            }
        } else {
            if (uiState.hasUnsavedChanges) {
                pendingImportContent = text
                showOverwriteConfirmDialog = true
            } else {
                onImportContent(text)
            }
        }
    }

    BackHandler(enabled = uiState.hasUnsavedChanges) {
        showDiscardChangesDialog = true
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onClearMessages()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onClearMessages()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Contexto del Proyecto",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.hasUnsavedChanges) {
                                showDiscardChangesDialog = true
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
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

                    // Importar archivo / Pegar
                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Importar archivo / Pegar"
                            )
                        }

                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Importar archivo (.md / texto)") },
                                onClick = {
                                    showImportMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.FileOpen,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pegar del portapapeles") },
                                onClick = {
                                    showImportMenu = false
                                    handlePasteClipboard()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }

                    // Restablecer plantilla
                    IconButton(onClick = { showResetConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Restablecer plantilla"
                        )
                    }

                    // Toggle View/Edit
                    IconButton(onClick = onToggleEditMode) {
                        Icon(
                            imageVector = if (uiState.isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (uiState.isEditMode) "Ver formato" else "Editar texto"
                        )
                    }

                    // Guardar
                    IconButton(
                        onClick = onSaveContext,
                        enabled = !uiState.isSaving
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Guardar contexto"
                        )
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
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.isEditMode) {
                ContextTextEditor(
                    content = uiState.content,
                    onContentChange = onUpdateContent,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                if (uiState.content.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "El contexto está vacío.\nCambia al modo edición para redactar o importar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    PlanMarkdownReader(
                        content = uiState.content,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showOverwriteConfirmDialog && pendingImportContent != null) {
        AlertDialog(
            onDismissRequest = {
                showOverwriteConfirmDialog = false
                pendingImportContent = null
            },
            title = { Text("¿Reemplazar contenido?") },
            text = {
                Text("Hay cambios sin guardar en el contexto actual. Si continúas, serán reemplazados por el nuevo contenido.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportContent?.let { onImportContent(it) }
                        pendingImportContent = null
                        showOverwriteConfirmDialog = false
                    }
                ) {
                    Text("Reemplazar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingImportContent = null
                        showOverwriteConfirmDialog = false
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("¿Restablecer plantilla?") },
            text = {
                Text("¿Deseas restablecer el contexto a la plantilla por defecto? Se perderán las modificaciones actuales.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetToDefault()
                        showResetConfirmDialog = false
                    }
                ) {
                    Text("Restablecer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showDiscardChangesDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardChangesDialog = false },
            title = { Text("¿Descartar cambios?") },
            text = {
                Text("Tienes cambios sin guardar en el contexto. ¿Deseas salir sin guardar?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardChangesDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("Descartar y Salir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardChangesDialog = false }) {
                    Text("Continuar Editando")
                }
            }
        )
    }
}

@Composable
fun ContextTextEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = content, selection = TextRange(content.length)))
    }

    LaunchedEffect(content) {
        if (content != textFieldValue.text) {
            val safeSelection = TextRange(
                textFieldValue.selection.start.coerceIn(0, content.length),
                textFieldValue.selection.end.coerceIn(0, content.length)
            )
            textFieldValue = TextFieldValue(text = content, selection = safeSelection)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MarkdownAuxiliaryToolbar(
            onInsertShortcut = { symbol ->
                val newValue = applyMarkdownShortcut(textFieldValue, symbol)
                textFieldValue = newValue
                onContentChange(newValue.text)
            }
        )

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        TextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                if (newValue.text != content) {
                    onContentChange(newValue.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            placeholder = {
                Text(
                    text = "Escribe el contexto del proyecto en Markdown...",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
        )
    }
}
