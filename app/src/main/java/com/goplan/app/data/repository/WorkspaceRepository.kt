package com.goplan.app.data.repository

import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import java.io.File

class WorkspaceRepository(
    private val fileManager: WorkspaceFileManager,
    private val parser: TodoMarkdownParser
) {

    fun getWorkspaces(): List<Workspace> {
        return fileManager.getWorkspaces()
    }

    fun createWorkspace(name: String): Workspace {
        return fileManager.createWorkspace(name)
    }

    fun getTodoDocument(workspace: Workspace): TodoDocument {
        val content = fileManager.readTodoFile(workspace)
        return parser.parse(content)
    }

    fun saveTodoDocument(workspace: Workspace, document: TodoDocument) {
        val serialized = parser.serialize(document)
        fileManager.writeTodoFile(workspace, serialized)
    }

    fun getContextContent(workspace: Workspace): String {
        return fileManager.readContextFile(workspace)
    }

    fun saveContextContent(workspace: Workspace, content: String) {
        fileManager.writeContextFile(workspace, content)
    }

    fun savePlanFile(workspace: Workspace, fileName: String, content: String): File {
        return fileManager.savePlanFile(workspace, fileName, content)
    }

    fun readPlanFile(workspace: Workspace, relativePath: String): String {
        return fileManager.readPlanFile(workspace, relativePath)
    }
}
