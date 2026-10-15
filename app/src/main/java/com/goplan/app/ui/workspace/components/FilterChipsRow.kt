package com.goplan.app.ui.workspace.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goplan.app.domain.model.IdeaStatus

@Composable
fun FilterChipsRow(
    selectedStatuses: Set<IdeaStatus>,
    onToggleStatus: (IdeaStatus) -> Unit,
    onClearStatusFilters: () -> Unit,
    availableTags: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "Todos" chip: selected when no specific status filter is active
        item(key = "status_all") {
            FilterChip(
                selected = selectedStatuses.isEmpty(),
                onClick = onClearStatusFilters,
                label = { Text("Todos") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }

        // Status chips
        item(key = "status_pending") {
            FilterChip(
                selected = selectedStatuses.contains(IdeaStatus.PENDING),
                onClick = { onToggleStatus(IdeaStatus.PENDING) },
                label = { Text("Pendientes") }
            )
        }

        item(key = "status_in_progress") {
            FilterChip(
                selected = selectedStatuses.contains(IdeaStatus.IN_PROGRESS),
                onClick = { onToggleStatus(IdeaStatus.IN_PROGRESS) },
                label = { Text("En progreso") }
            )
        }

        item(key = "status_completed") {
            FilterChip(
                selected = selectedStatuses.contains(IdeaStatus.COMPLETED),
                onClick = { onToggleStatus(IdeaStatus.COMPLETED) },
                label = { Text("Completadas") }
            )
        }

        item(key = "status_discarded") {
            FilterChip(
                selected = selectedStatuses.contains(IdeaStatus.DISCARDED),
                onClick = { onToggleStatus(IdeaStatus.DISCARDED) },
                label = { Text("Descartadas") }
            )
        }

        // Divider between statuses and tags if tags exist
        if (availableTags.isNotEmpty()) {
            item(key = "tags_divider") {
                VerticalDivider(
                    modifier = Modifier.height(24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Project tag chips
            items(items = availableTags, key = { "tag_$it" }) { tag ->
                FilterChip(
                    selected = selectedTags.contains(tag),
                    onClick = { onToggleTag(tag) },
                    label = { Text("#$tag") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }
        }
    }
}
