package com.goplan.app.ui.workspace.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun QuickCaptureBar(
    availableTags: List<String>,
    onSendIdea: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "Captura rápida de idea... (#tag)"
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Tag autocomplete calculation
    val cursorPosition = textFieldValue.selection.end
    val textBeforeCursor = textFieldValue.text.take(cursorPosition)
    val lastHashIndex = textBeforeCursor.lastIndexOf('#')

    val isTagQueryActive = if (lastHashIndex != -1) {
        val textAfterHash = textBeforeCursor.substring(lastHashIndex + 1)
        !textAfterHash.contains(Regex("""\s"""))
    } else {
        false
    }

    val activeQuery = if (isTagQueryActive) {
        textBeforeCursor.substring(lastHashIndex + 1)
    } else {
        ""
    }

    val matchingTags = if (isTagQueryActive) {
        availableTags.filter { it.startsWith(activeQuery, ignoreCase = true) }
    } else {
        emptyList()
    }

    fun submitCurrentText() {
        val trimmed = textFieldValue.text.trim()
        if (trimmed.isNotBlank()) {
            onSendIdea(trimmed)
            textFieldValue = TextFieldValue("")
        }
    }

    fun insertTag(tag: String) {
        val prefix = textFieldValue.text.substring(0, lastHashIndex)
        val suffix = textFieldValue.text.substring(cursorPosition)
        val insertion = "#$tag "
        val newText = prefix + insertion + suffix
        val newCursor = prefix.length + insertion.length
        textFieldValue = TextFieldValue(
            text = newText,
            selection = TextRange(newCursor)
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(bottom = 8.dp)
        ) {
            // Autocomplete chip row
            AnimatedVisibility(
                visible = matchingTags.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                        .padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(matchingTags) { tag ->
                        SuggestionChip(
                            onClick = { insertTag(tag) },
                            label = { Text("#$tag") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = null
                        )
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { submitCurrentText() }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                val isSendEnabled = textFieldValue.text.isNotBlank()

                IconButton(
                    onClick = { submitCurrentText() },
                    enabled = isSendEnabled,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isSendEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (isSendEnabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar idea"
                    )
                }
            }
        }
    }
}
