package com.goplan.app.domain.export

import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.Workspace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentExportManager(private val repository: WorkspaceRepository) {

    fun createZipBundle(workspace: Workspace, ideas: List<Idea>, outputZipFile: File): File {
        outputZipFile.parentFile?.mkdirs()

        ZipOutputStream(outputZipFile.outputStream().buffered()).use { zip ->
            val contextContent = repository.getContextContent(workspace)
            zip.putNextEntry(ZipEntry("CONTEXT.md"))
            zip.write(contextContent.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val todoExtractContent = if (ideas.isEmpty()) {
                ""
            } else {
                ideas.joinToString("\n") { it.rawLine } + "\n"
            }
            zip.putNextEntry(ZipEntry("TODO_EXTRACT.md"))
            zip.write(todoExtractContent.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val writtenEntries = mutableSetOf<String>()
            for (plan in ideas.flatMap { it.plans }) {
                val cleanFileName = File(plan.relativePath).name
                val entryName = "planes/$cleanFileName"
                if (writtenEntries.add(entryName)) {
                    val planContent = repository.readPlanFile(workspace, plan.relativePath)
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(planContent.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            zip.flush()
        }

        return outputZipFile
    }

    fun createUnifiedMarkdown(workspace: Workspace, ideas: List<Idea>): String {
        return buildString {
            appendLine("# CONTEXTO DEL PROYECTO")
            val context = repository.getContextContent(workspace).trim()
            if (context.isNotEmpty()) {
                appendLine(context)
            }
            appendLine()
            appendLine("## TAREAS Y PLANES")
            if (ideas.isEmpty()) {
                appendLine("*(No hay tareas seleccionadas)*")
            } else {
                for (idea in ideas) {
                    appendLine()
                    appendLine("### ${idea.title}")
                    appendLine("- Estado: ${idea.status.marker}")
                    if (idea.tags.isNotEmpty()) {
                        appendLine("- Etiquetas: ${idea.tags.joinToString(", ")}")
                    }
                    for (plan in idea.plans) {
                        appendLine()
                        appendLine("#### Plan: ${plan.label}")
                        val planContent = repository.readPlanFile(workspace, plan.relativePath).trim()
                        if (planContent.isNotEmpty()) {
                            appendLine(planContent)
                        }
                    }
                }
            }
        }.trim() + "\n"
    }
}
