package com.goplan.app.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.Workspace

/**
 * Reusable button for share and export actions with explicit vector icon and clear styling.
 * Avoids emojis and provides clean tactile separation.
 */
@Composable
fun ShareOptionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Redesigned share / export dialog with distinct vector buttons, no emojis, and
 * support for selecting specific ideas.
 */
@Composable
fun ShareExportDialog(
    workspace: Workspace?,
    ideas: List<Idea>,
    onDismiss: () -> Unit,
    onExpandWithGemini: (List<Idea>) -> Unit,
    onExportZip: (List<Idea>) -> Unit,
    onExportMarkdown: (List<Idea>) -> Unit
) {
    var showSelectIdeasDialog by remember { mutableStateOf(false) }

    if (showSelectIdeasDialog) {
        SelectIdeasExportDialog(
            ideas = ideas,
            onDismiss = { showSelectIdeasDialog = false },
            onConfirmGemini = { selected ->
                showSelectIdeasDialog = false
                onDismiss()
                onExpandWithGemini(selected)
            },
            onConfirmZip = { selected ->
                showSelectIdeasDialog = false
                onDismiss()
                onExportZip(selected)
            },
            onConfirmMarkdown = { selected ->
                showSelectIdeasDialog = false
                onDismiss()
                onExportMarkdown(selected)
            }
        )
        return
    }

    val pendingIdeas = remember(ideas) { ideas.filter { it.status == IdeaStatus.PENDING } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Exportar o Expandir",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Selecciona una opción para ${workspace?.name ?: "el workspace"}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                ShareOptionButton(
                    text = "Expandir pendientes con Gemini",
                    subtitle = "Prompt estructurado (${pendingIdeas.size} pendientes)",
                    icon = Icons.Default.AutoAwesome,
                    onClick = {
                        onDismiss()
                        onExpandWithGemini(pendingIdeas)
                    }
                )

                ShareOptionButton(
                    text = "Seleccionar ideas específicas...",
                    subtitle = "Elige cuáles ideas exportar o expandir",
                    icon = Icons.Default.Checklist,
                    onClick = {
                        showSelectIdeasDialog = true
                    }
                )

                ShareOptionButton(
                    text = "Paquete ZIP para agentes",
                    subtitle = "Archivo .zip con CONTEXT.md, TODO y planes",
                    icon = Icons.Default.Archive,
                    onClick = {
                        onDismiss()
                        onExportZip(ideas)
                    }
                )

                ShareOptionButton(
                    text = "Documento Markdown unificado",
                    subtitle = "Archivo .md con contexto y planes completos",
                    icon = Icons.Default.Description,
                    onClick = {
                        onDismiss()
                        onExportMarkdown(ideas)
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

/**
 * Dialog that allows the user to choose one or more ideas to export or expand.
 */
@Composable
fun SelectIdeasExportDialog(
    ideas: List<Idea>,
    onDismiss: () -> Unit,
    onConfirmGemini: (List<Idea>) -> Unit,
    onConfirmZip: (List<Idea>) -> Unit,
    onConfirmMarkdown: (List<Idea>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedIdeaIds by remember { mutableStateOf(setOf<String>()) }

    val filteredIdeas = remember(ideas, searchQuery) {
        if (searchQuery.isBlank()) {
            ideas
        } else {
            val q = searchQuery.trim().lowercase()
            ideas.filter { idea ->
                idea.title.lowercase().contains(q) ||
                        idea.tags.any { it.lowercase().contains(q) }
            }
        }
    }

    val selectedCount = selectedIdeaIds.size
    val selectedIdeas = remember(ideas, selectedIdeaIds) {
        ideas.filter { selectedIdeaIds.contains(it.id) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(max = 700.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Seleccionar Ideas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$selectedCount de ${ideas.size} seleccionadas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Search input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por título o #etiqueta...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick selection chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            selectedIdeaIds = filteredIdeas.map { it.id }.toSet()
                        },
                        label = { Text("Todas") }
                    )
                    AssistChip(
                        onClick = {
                            selectedIdeaIds = filteredIdeas
                                .filter { it.status == IdeaStatus.PENDING }
                                .map { it.id }
                                .toSet()
                        },
                        label = { Text("Pendientes") }
                    )
                    AssistChip(
                        onClick = {
                            selectedIdeaIds = emptySet()
                        },
                        label = { Text("Ninguna") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable list of ideas with checkboxes
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filteredIdeas.isEmpty()) {
                        item {
                            Text(
                                text = "No se encontraron ideas que coincidan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }

                    items(filteredIdeas, key = { it.id }) { idea ->
                        val isChecked = selectedIdeaIds.contains(idea.id)
                        OutlinedCard(
                            onClick = {
                                selectedIdeaIds = if (isChecked) {
                                    selectedIdeaIds - idea.id
                                } else {
                                    selectedIdeaIds + idea.id
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIdeaIds = if (checked) {
                                            selectedIdeaIds + idea.id
                                        } else {
                                            selectedIdeaIds - idea.id
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = idea.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        val statusText = when (idea.status) {
                                            IdeaStatus.PENDING -> "Pendiente"
                                            IdeaStatus.IN_PROGRESS -> "En progreso"
                                            IdeaStatus.COMPLETED -> "Completada"
                                            IdeaStatus.DISCARDED -> "Descartada"
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (idea.status) {
                                                IdeaStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                                                IdeaStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                                                IdeaStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                                                IdeaStatus.DISCARDED -> MaterialTheme.colorScheme.outline
                                            }
                                        )

                                        if (idea.tags.isNotEmpty()) {
                                            Text(
                                                text = idea.tags.joinToString(" ") { "#$it" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (idea.plans.isNotEmpty()) {
                                            Text(
                                                text = "• ${idea.plans.size} plan(es)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onConfirmGemini(selectedIdeas) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gemini ($selectedCount)", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedButton(
                        onClick = { onConfirmZip(selectedIdeas) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ZIP ($selectedCount)", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedButton(
                        onClick = { onConfirmMarkdown(selectedIdeas) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MD ($selectedCount)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        dismissButton = {}
    )
}
