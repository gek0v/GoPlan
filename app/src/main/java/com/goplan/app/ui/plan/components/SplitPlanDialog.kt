package com.goplan.app.ui.plan.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goplan.app.domain.parser.GeminiPlanParser

fun suggestPlanFileName(splitContent: String): String {
    val headingRegex = Regex("""^#{1,6}\s+(.+)$""", RegexOption.MULTILINE)
    val match = headingRegex.find(splitContent)
    val heading = match?.groupValues?.get(1)?.trim()
    return if (!heading.isNullOrBlank()) {
        val clean = GeminiPlanParser().sanitizeFileName(heading).removeSuffix(".md")
        if (clean.isNotBlank() && clean != "plan") clean else "sub-plan"
    } else {
        "sub-plan"
    }
}

@Composable
fun SplitPlanDialog(
    suggestedFileName: String,
    onConfirm: (fileName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf(suggestedFileName) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Dividir desde el cursor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "El texto desde la posición del cursor se extraerá a un nuevo archivo de plan y se vinculará a la tarea.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        isError = it.trim().isEmpty()
                    },
                    label = { Text("Nombre del nuevo archivo") },
                    suffix = { Text(".md") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("El nombre no puede estar vacío") }
                    } else {
                        { Text("Se normalizará automáticamente a kebab-case") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = fileName.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    } else {
                        isError = true
                    }
                },
                enabled = fileName.trim().isNotEmpty()
            ) {
                Text("Dividir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
