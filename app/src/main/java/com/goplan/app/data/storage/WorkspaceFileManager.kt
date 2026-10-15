package com.goplan.app.data.storage

import com.goplan.app.domain.model.Workspace
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WorkspaceFileManager(val baseDir: File) {

    companion object {
        const val DEFAULT_CONTEXT_TEMPLATE = """# Contexto del Proyecto

## Stack Tecnológico
- Lenguaje: Kotlin
- Framework / UI: Jetpack Compose
- Arquitectura: Clean Architecture / MVVM

## Pautas y Convenciones
- Código modular y legible.
- Manejo defensivo de excepciones.

## Instrucciones para Agentes
- Priorizar soluciones simples y mantenibles.
- Generar especificaciones técnicas claras y accionables.
"""

        const val DEFAULT_TODO_TEMPLATE = """# Tareas

"""
    }

    fun getWorkspaces(): List<Workspace> {
        if (!baseDir.exists()) {
            return emptyList()
        }
        val subDirs = baseDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?: return emptyList()

        return subDirs
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                Workspace(
                    id = dir.name,
                    name = dir.name,
                    rootDir = dir
                )
            }
    }

    fun createWorkspace(name: String): Workspace {
        val folderName = sanitizeWorkspaceName(name)
        val rootDir = File(baseDir, folderName)
        initWorkspaceStructure(rootDir)
        return Workspace(
            id = folderName,
            name = folderName,
            rootDir = rootDir
        )
    }

    fun initWorkspaceStructure(rootDir: File) {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        val planesDir = File(rootDir, "planes")
        if (!planesDir.exists()) {
            planesDir.mkdirs()
        }

        val contextFile = File(rootDir, "CONTEXT.md")
        if (!contextFile.exists()) {
            writeAtomic(contextFile, DEFAULT_CONTEXT_TEMPLATE)
        }

        val todoFile = File(rootDir, "TODO.md")
        if (!todoFile.exists()) {
            writeAtomic(todoFile, DEFAULT_TODO_TEMPLATE)
        }
    }

    fun writeAtomic(targetFile: File, content: String) {
        val parentDir = targetFile.parentFile ?: baseDir
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempFile = File(parentDir, "${targetFile.name}.${System.nanoTime()}.tmp")
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun readTodoFile(workspace: Workspace): String {
        val todoFile = File(workspace.rootDir, "TODO.md")
        if (!todoFile.exists()) {
            writeAtomic(todoFile, DEFAULT_TODO_TEMPLATE)
        }
        return todoFile.readText(Charsets.UTF_8)
    }

    fun writeTodoFile(workspace: Workspace, content: String) {
        val todoFile = File(workspace.rootDir, "TODO.md")
        writeAtomic(todoFile, content)
    }

    fun readContextFile(workspace: Workspace): String {
        val contextFile = File(workspace.rootDir, "CONTEXT.md")
        if (!contextFile.exists()) {
            writeAtomic(contextFile, DEFAULT_CONTEXT_TEMPLATE)
        }
        return contextFile.readText(Charsets.UTF_8)
    }

    fun writeContextFile(workspace: Workspace, content: String) {
        val contextFile = File(workspace.rootDir, "CONTEXT.md")
        writeAtomic(contextFile, content)
    }

    fun savePlanFile(workspace: Workspace, fileName: String, content: String): File {
        val cleanName = File(fileName).name
        val planesDir = File(workspace.rootDir, "planes")
        if (!planesDir.exists()) {
            planesDir.mkdirs()
        }
        val targetFile = File(planesDir, cleanName)
        writeAtomic(targetFile, content)
        return targetFile
    }

    fun readPlanFile(workspace: Workspace, relativePath: String): String {
        val rootCanonical = workspace.rootDir.canonicalFile
        val trimmed = relativePath.trimStart('/', '\\')

        val resolved = File(workspace.rootDir, trimmed).canonicalFile
        if (!resolved.toPath().startsWith(rootCanonical.toPath())) {
            throw SecurityException("Path traversal detected: $relativePath")
        }

        val targetFile = if (resolved.exists()) {
            resolved
        } else {
            val inPlanes = File(File(workspace.rootDir, "planes"), File(trimmed).name).canonicalFile
            if (inPlanes.exists()) {
                inPlanes
            } else {
                resolved
            }
        }

        if (!targetFile.exists()) {
            throw IOException("Plan file not found: $relativePath")
        }

        return targetFile.readText(Charsets.UTF_8)
    }

    private fun sanitizeWorkspaceName(name: String): String {
        val trimmed = name.trim()
        val sanitized = trimmed.replace(Regex("""[/\\:*?"<>|]"""), "_").trim('.')
        return sanitized.ifEmpty { "workspace" }
    }
}
