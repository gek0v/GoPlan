package com.goplan.app.ui.plan.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class ListItem(val text: String, val isTask: Boolean = false, val isChecked: Boolean = false) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data object EmptyLine : MarkdownBlock
}

fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()

    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    var codeLanguage = ""
    val codeLines = mutableListOf<String>()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLanguage, codeLines.joinToString("\n")))
                codeLines.clear()
                codeLanguage = ""
                inCodeBlock = false
            } else {
                inCodeBlock = true
                codeLanguage = trimmed.removePrefix("```").trim()
            }
            continue
        }

        if (inCodeBlock) {
            codeLines.add(line)
            continue
        }

        if (trimmed.isEmpty()) {
            blocks.add(MarkdownBlock.EmptyLine)
            continue
        }

        val headingMatch = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MarkdownBlock.Heading(level, text))
            continue
        }

        if (trimmed.startsWith(">")) {
            val text = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.Blockquote(text))
            continue
        }

        val taskMatch = Regex("""^[-*]\s+\[([ xX/\-])\]\s*(.*)$""").matchEntire(trimmed)
        if (taskMatch != null) {
            val mark = taskMatch.groupValues[1]
            val text = taskMatch.groupValues[2]
            val isChecked = mark.equals("x", ignoreCase = true)
            blocks.add(MarkdownBlock.ListItem(text = text, isTask = true, isChecked = isChecked))
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            val text = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.ListItem(text = text, isTask = false))
            continue
        }

        blocks.add(MarkdownBlock.Paragraph(line))
    }

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeLanguage, codeLines.joinToString("\n")))
    }

    return blocks
}

@Composable
fun renderInlineMarkdown(text: String): AnnotatedString {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    return remember(text, primaryColor, surfaceVariant) {
        buildAnnotatedString {
            val tokenRegex = Regex("""(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|\[[^\]]+\]\([^)]+\))""")
            var cursor = 0
            for (match in tokenRegex.findAll(text)) {
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val token = match.value
                when {
                    token.startsWith("`") && token.endsWith("`") -> {
                        val code = token.removeSurrounding("`")
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = surfaceVariant
                            )
                        ) {
                            append(" $code ")
                        }
                    }
                    token.startsWith("**") && token.endsWith("**") -> {
                        val bold = token.removeSurrounding("**")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(bold)
                        }
                    }
                    token.startsWith("*") && token.endsWith("*") -> {
                        val italic = token.removeSurrounding("*")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(italic)
                        }
                    }
                    token.startsWith("[") && token.contains("](") && token.endsWith(")") -> {
                        val label = token.substringAfter("[").substringBefore("]")
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(label)
                        }
                    }
                    else -> append(token)
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
}

fun applyMarkdownShortcut(currentValue: TextFieldValue, symbol: String): TextFieldValue {
    val text = currentValue.text
    val selection = currentValue.selection
    val start = selection.min
    val end = selection.max
    val selectedText = if (start != end) text.substring(start, end) else ""

    val (newText, newSelection) = when (symbol) {
        "**" -> {
            if (selectedText.isNotEmpty()) {
                val rep = text.replaceRange(start, end, "**$selectedText**")
                rep to TextRange(end + 4)
            } else {
                val rep = text.replaceRange(start, start, "****")
                rep to TextRange(start + 2)
            }
        }
        "```" -> {
            if (selectedText.isNotEmpty()) {
                val block = "```\n$selectedText\n```"
                val rep = text.replaceRange(start, end, block)
                rep to TextRange(start + block.length)
            } else {
                val block = "```\n\n```"
                val rep = text.replaceRange(start, start, block)
                rep to TextRange(start + 4)
            }
        }
        "- [ ]" -> {
            val insert = "- [ ] "
            val rep = text.replaceRange(start, end, insert)
            rep to TextRange(start + insert.length)
        }
        "- " -> {
            val insert = "- "
            val rep = text.replaceRange(start, end, insert)
            rep to TextRange(start + insert.length)
        }
        "#" -> {
            val insert = "# "
            val rep = text.replaceRange(start, end, insert)
            rep to TextRange(start + insert.length)
        }
        "##" -> {
            val insert = "## "
            val rep = text.replaceRange(start, end, insert)
            rep to TextRange(start + insert.length)
        }
        else -> {
            val rep = text.replaceRange(start, end, symbol)
            rep to TextRange(start + symbol.length)
        }
    }
    return TextFieldValue(text = newText, selection = newSelection)
}

@Composable
fun MarkdownAuxiliaryToolbar(
    onInsertShortcut: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val shortcuts = listOf(
            "#" to "H1",
            "##" to "H2",
            "- [ ]" to "Tarea",
            "```" to "Código",
            "**" to "Negrita",
            "- " to "Lista"
        )
        shortcuts.forEach { (symbol, _) ->
            SuggestionChip(
                onClick = { onInsertShortcut(symbol) },
                label = { Text(symbol, fontWeight = FontWeight.Bold) }
            )
        }
    }
}

@Composable
fun PlanMarkdownReader(
    content: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(blocks) { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    when (block.level) {
                        1 -> Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                        2 -> Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                        else -> Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = block.code,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        if (block.isTask) {
                            Checkbox(
                                checked = block.isChecked,
                                onCheckedChange = null,
                                modifier = Modifier.size(20.dp),
                                enabled = false
                            )
                        } else {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MarkdownBlock.EmptyLine -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun PlanMarkdownEditor(
    content: String,
    onContentChange: (String) -> Unit,
    onCursorPositionChange: ((Int) -> Unit)? = null,
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
                onCursorPositionChange?.invoke(newValue.selection.start)
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
                onCursorPositionChange?.invoke(newValue.selection.start)
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
                    text = "Escribe el plan aquí en Markdown...",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
        )
    }
}

@Composable
fun PlanMarkdownViewer(
    content: String,
    isEditMode: Boolean,
    onContentChange: (String) -> Unit,
    onCursorPositionChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (isEditMode) {
        PlanMarkdownEditor(
            content = content,
            onContentChange = onContentChange,
            onCursorPositionChange = onCursorPositionChange,
            modifier = modifier
        )
    } else {
        PlanMarkdownReader(
            content = content,
            modifier = modifier
        )
    }
}
