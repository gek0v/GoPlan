package com.goplan.app.ui.intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goplan.app.GoPlanApplication
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.preferences.RecentExpansion
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.GeminiPlanParser
import com.goplan.app.domain.parser.ParsedPlanFile
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.ui.intent.components.SelectIdeaDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks whether the incoming text matches typical CONTEXT.md templates or headers.
 */
fun isContextContent(text: String): Boolean {
    val trimmed = text.trim()
    val firstLine = trimmed.lines().firstOrNull()?.trim() ?: ""
    return firstLine.startsWith("# Contexto", ignoreCase = true) ||
            firstLine.startsWith("# Context", ignoreCase = true) ||
            firstLine.equals("# CONTEXTO", ignoreCase = true) ||
            firstLine.equals("# CONTEXT", ignoreCase = true) ||
            firstLine.startsWith("## Contexto", ignoreCase = true)
}

/**
 * Saves parsed plan files to workspace planes/ directory, links them to the idea in TODO.md,
 * advances idea status to IN_PROGRESS, and clears recent expansion cache.
 */
suspend fun executeImportPlans(
    workspaceRepository: WorkspaceRepository,
    preferencesManager: PreferencesManager,
    todoParser: TodoMarkdownParser,
    workspace: Workspace,
    idea: Idea,
    plans: List<ParsedPlanFile>
) {
    val planLinks = mutableListOf<PlanLink>()
    for (plan in plans) {
        val savedFile = workspaceRepository.savePlanFile(workspace, plan.fileName, plan.content)
        val label = plan.fileName.removeSuffix(".md")
        planLinks.add(PlanLink(label = label, relativePath = "planes/${savedFile.name}"))
    }
    val currentDoc = workspaceRepository.getTodoDocument(workspace)
    val updatedDoc = todoParser.linkPlansToIdea(currentDoc, idea.id, planLinks)
    workspaceRepository.saveTodoDocument(workspace, updatedDoc)
    preferencesManager.clearRecentExpansion()
}

/**
 * Saves context text to CONTEXT.md in the selected workspace and clears recent expansion.
 */
suspend fun executeImportContext(
    workspaceRepository: WorkspaceRepository,
    preferencesManager: PreferencesManager,
    workspace: Workspace,
    contextContent: String
) {
    workspaceRepository.saveContextContent(workspace, contextContent)
    preferencesManager.clearRecentExpansion()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPlanBottomSheet(
    incomingText: String,
    workspaces: List<Workspace>,
    recentExpansion: RecentExpansion? = null,
    onDismiss: () -> Unit,
    onConfirmImportPlans: (workspace: Workspace, idea: Idea, plans: List<ParsedPlanFile>) -> Unit,
    onConfirmImportContext: (workspace: Workspace, contextContent: String) -> Unit,
    ideasProvider: (suspend (Workspace) -> List<Idea>)? = null,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val looksLikeContext = remember(incomingText) { isContextContent(incomingText) }
    var selectedTab by remember { mutableIntStateOf(if (looksLikeContext) 1 else 0) }

    // Preselect workspace based on recentExpansion if possible, or fallback to first
    val preselectedWorkspace = remember(workspaces, recentExpansion) {
        if (recentExpansion != null) {
            workspaces.find { it.id == recentExpansion.workspaceId } ?: workspaces.firstOrNull()
        } else {
            workspaces.firstOrNull()
        }
    }

    var selectedWorkspace by remember(preselectedWorkspace) { mutableStateOf(preselectedWorkspace) }
    var workspaceIdeas by remember { mutableStateOf<List<Idea>>(emptyList()) }
    var selectedIdea by remember { mutableStateOf<Idea?>(null) }

    var workspaceDropdownExpanded by remember { mutableStateOf(false) }
    var showSelectIdeaDialog by remember { mutableStateOf(false) }

    // Load ideas whenever selectedWorkspace changes
    LaunchedEffect(selectedWorkspace) {
        val ws = selectedWorkspace ?: return@LaunchedEffect
        val ideas = if (ideasProvider != null) {
            ideasProvider(ws)
        } else {
            try {
                val app = GoPlanApplication.instance
                val doc = withContext(Dispatchers.IO) {
                    app.workspaceRepository.getTodoDocument(ws)
                }
                doc.lines.filterIsInstance<TodoLine.Task>().map { it.idea }
            } catch (_: Exception) {
                emptyList()
            }
        }
        workspaceIdeas = ideas

        // Preselect idea based on recentExpansion if matching workspace
        if (recentExpansion != null && ws.id == recentExpansion.workspaceId) {
            val matched = ideas.find { it.id == recentExpansion.ideaId }
            selectedIdea = matched ?: ideas.firstOrNull()
        } else {
            selectedIdea = ideas.firstOrNull()
        }
    }

    // Parse plans according to currently selected idea
    val parsedPlans = remember(incomingText, selectedIdea) {
        val fallback = selectedIdea?.title ?: "Plan"
        GeminiPlanParser().parsePlans(rawText = incomingText, fallbackIdeaTitle = fallback)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = "Importar Contenido",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tab selector (Plan vs Context)
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Plan Técnico") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Contexto (CONTEXT.md)") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Workspace Selector
            Text(
                text = "Workspace de destino:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { workspaceDropdownExpanded = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedWorkspace?.name ?: "Seleccionar workspace",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = workspaceDropdownExpanded,
                    onDismissRequest = { workspaceDropdownExpanded = false }
                ) {
                    workspaces.forEach { ws ->
                        DropdownMenuItem(
                            text = { Text(ws.name) },
                            onClick = {
                                selectedWorkspace = ws
                                workspaceDropdownExpanded = false
                            },
                            leadingIcon = if (ws.id == selectedWorkspace?.id) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                // PLAN MODE
                Text(
                    text = "Vincular a Idea:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                val isRecentIdea = recentExpansion != null &&
                        selectedIdea != null &&
                        recentExpansion.workspaceId == selectedWorkspace?.id &&
                        recentExpansion.ideaId == selectedIdea?.id

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = workspaceIdeas.isNotEmpty()) {
                            showSelectIdeaDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedIdea?.title ?: if (workspaceIdeas.isEmpty()) {
                                    "No hay ideas en este workspace"
                                } else {
                                    "Seleccionar idea"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isRecentIdea) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Sugerida por expansión reciente en Gemini",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (workspaceIdeas.isNotEmpty()) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                }

                if (showSelectIdeaDialog) {
                    SelectIdeaDialog(
                        ideas = workspaceIdeas,
                        selectedIdea = selectedIdea,
                        recentExpansion = recentExpansion,
                        workspaceId = selectedWorkspace?.id,
                        onIdeaSelected = { selectedIdea = it },
                        onDismiss = { showSelectIdeaDialog = false }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Files extracted list
                Text(
                    text = "Archivos detectados (${parsedPlans.size}):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                parsedPlans.forEach { file ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "planes/${file.fileName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${file.content.length} caracteres",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preview Box
                Text(
                    text = "Vista previa del contenido:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                ) {
                    Text(
                        text = incomingText,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ws = selectedWorkspace
                            val idea = selectedIdea
                            if (ws != null && idea != null && parsedPlans.isNotEmpty()) {
                                onConfirmImportPlans(ws, idea, parsedPlans)
                            }
                        },
                        enabled = selectedWorkspace != null && selectedIdea != null && parsedPlans.isNotEmpty()
                    ) {
                        Text("Confirmar e Importar Plan")
                    }
                }

            } else {
                // CONTEXT MODE
                Text(
                    text = "Este contenido se guardará como el archivo CONTEXT.md del workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Vista previa de CONTEXT.md:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    Text(
                        text = incomingText,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ws = selectedWorkspace
                            if (ws != null) {
                                onConfirmImportContext(ws, incomingText)
                            }
                        },
                        enabled = selectedWorkspace != null && incomingText.isNotBlank()
                    ) {
                        Text("Guardar como CONTEXT.md")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
