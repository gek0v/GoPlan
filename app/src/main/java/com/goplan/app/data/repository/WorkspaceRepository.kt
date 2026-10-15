package com.goplan.app.data.repository

import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class WorkspaceRepository(
    private val fileManager: WorkspaceFileManager,
    private val parser: TodoMarkdownParser
) {

    private val fileMutex = Mutex()

    private val _workspaceChangesFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val workspaceChangesFlow: SharedFlow<String> = _workspaceChangesFlow.asSharedFlow()

    suspend fun notifyWorkspaceChanged(workspaceId: String) {
        _workspaceChangesFlow.emit(workspaceId)
    }

    fun getWorkspaces(): List<Workspace> {
        return fileManager.getWorkspaces()
    }

    fun createWorkspace(name: String): Workspace {
        val workspace = runBlocking {
            fileMutex.withLock {
                fileManager.createWorkspace(name)
            }
        }
        _workspaceChangesFlow.tryEmit(workspace.id)
        return workspace
    }

    fun getTodoDocument(workspace: Workspace): TodoDocument = runBlocking {
        fileMutex.withLock {
            val content = fileManager.readTodoFile(workspace)
            parser.parse(content)
        }
    }

    fun saveTodoDocument(workspace: Workspace, document: TodoDocument) {
        runBlocking {
            fileMutex.withLock {
                val serialized = parser.serialize(document)
                fileManager.writeTodoFile(workspace, serialized)
            }
        }
        _workspaceChangesFlow.tryEmit(workspace.id)
    }

    fun getContextContent(workspace: Workspace): String = runBlocking {
        fileMutex.withLock {
            fileManager.readContextFile(workspace)
        }
    }

    fun saveContextContent(workspace: Workspace, content: String) {
        runBlocking {
            fileMutex.withLock {
                fileManager.writeContextFile(workspace, content)
            }
        }
        _workspaceChangesFlow.tryEmit(workspace.id)
    }

    fun savePlanFile(workspace: Workspace, fileName: String, content: String): File {
        val file = runBlocking {
            fileMutex.withLock {
                fileManager.savePlanFile(workspace, fileName, content)
            }
        }
        _workspaceChangesFlow.tryEmit(workspace.id)
        return file
    }

    fun readPlanFile(workspace: Workspace, relativePath: String): String = runBlocking {
        fileMutex.withLock {
            fileManager.readPlanFile(workspace, relativePath)
        }
    }
}
