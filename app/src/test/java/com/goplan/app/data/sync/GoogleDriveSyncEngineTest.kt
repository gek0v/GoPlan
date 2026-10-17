package com.goplan.app.data.sync

import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.data.sync.model.RemoteFileInfo
import com.goplan.app.data.sync.model.SyncResult
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GoogleDriveSyncEngineTest {

    private lateinit var tempDir: File
    private lateinit var workspaceFileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var authManager: GoogleDriveAuthManager
    private lateinit var syncEngine: GoogleDriveSyncEngine

    private class FakeDriveServiceAdapter : DriveServiceAdapter {
        val folders = mutableListOf<RemoteFileInfo>()
        val files = mutableListOf<FakeDriveFile>()
        private var idCounter = 1
        var throwOnUpload = false

        data class FakeDriveFile(
            val info: RemoteFileInfo,
            val parentId: String,
            var content: String
        )

        override fun findFolder(name: String, parentId: String?): RemoteFileInfo? {
            return folders.firstOrNull { it.name == name }
        }

        override fun createFolder(name: String, parentId: String?): RemoteFileInfo {
            val folder = RemoteFileInfo(id = "folder-${idCounter++}", name = name)
            folders.add(folder)
            return folder
        }

        override fun listFiles(folderId: String): List<RemoteFileInfo> {
            return files.filter { it.parentId == folderId }.map { it.info }
        }

        override fun downloadFile(fileId: String): String {
            return files.first { it.info.id == fileId }.content
        }

        override fun uploadFile(name: String, parentId: String, content: String, mimeType: String): RemoteFileInfo {
            if (throwOnUpload) throw IOException("Simulated network error")
            val fileInfo = RemoteFileInfo(id = "file-${idCounter++}", name = name)
            files.add(FakeDriveFile(fileInfo, parentId, content))
            return fileInfo
        }

        override fun updateFile(fileId: String, content: String): RemoteFileInfo {
            val file = files.first { it.info.id == fileId }
            file.content = content
            return file.info
        }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sync_test").toFile()
        workspaceFileManager = WorkspaceFileManager(tempDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(workspaceFileManager, parser)
        authManager = GoogleDriveAuthManager(context = null)
        syncEngine = GoogleDriveSyncEngine(repository, authManager, parser)
    }

    @Test
    fun computeSha256_emptyString_returnsKnownSha256() {
        val hash = syncEngine.computeSha256("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun computeSha256_deterministicSample_returnsCorrectHash() {
        val hash = syncEngine.computeSha256("hello world")
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun mergeTodoDocuments_mergesStatus_priorityCompletedOverPending() {
        val localIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.PENDING,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] Idea 1"
        )
        val remoteIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.COMPLETED,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [x] Idea 1"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val mergedTasks = merged.lines.filterIsInstance<TodoLine.Task>()

        assertEquals(1, mergedTasks.size)
        assertEquals(IdeaStatus.COMPLETED, mergedTasks[0].idea.status)
    }

    @Test
    fun mergeTodoDocuments_mergesStatus_priorityInProgressOverPending() {
        val localIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.PENDING,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] Idea 1"
        )
        val remoteIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.IN_PROGRESS,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [/] Idea 1"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val mergedTasks = merged.lines.filterIsInstance<TodoLine.Task>()

        assertEquals(1, mergedTasks.size)
        assertEquals(IdeaStatus.IN_PROGRESS, mergedTasks[0].idea.status)
    }

    @Test
    fun mergeTodoDocuments_mergesStatus_priorityActiveOverDiscarded() {
        val localIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.IN_PROGRESS,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [/] Idea 1"
        )
        val remoteIdea = Idea(
            id = "idea-1",
            status = IdeaStatus.DISCARDED,
            title = "Idea 1",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [-] Idea 1"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val mergedTasks = merged.lines.filterIsInstance<TodoLine.Task>()

        assertEquals(1, mergedTasks.size)
        assertEquals(IdeaStatus.IN_PROGRESS, mergedTasks[0].idea.status)
    }

    @Test
    fun mergeTodoDocuments_combinesAndDeduplicatesPlanLinks() {
        val localIdea = Idea(
            id = "feature-x",
            status = IdeaStatus.IN_PROGRESS,
            title = "Feature X",
            tags = emptySet(),
            plans = listOf(
                PlanLink("Plan 1", "planes/plan1.md"),
                PlanLink("Plan 2", "planes/plan2.md")
            ),
            rawLine = "- [/] Feature X ([Plan 1](planes/plan1.md), [Plan 2](planes/plan2.md))"
        )
        val remoteIdea = Idea(
            id = "feature-x",
            status = IdeaStatus.IN_PROGRESS,
            title = "Feature X",
            tags = emptySet(),
            plans = listOf(
                PlanLink("Plan 2 Alt", "planes/plan2.md"),
                PlanLink("Plan 3", "planes/plan3.md")
            ),
            rawLine = "- [/] Feature X ([Plan 2 Alt](planes/plan2.md), [Plan 3](planes/plan3.md))"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val mergedTasks = merged.lines.filterIsInstance<TodoLine.Task>()

        assertEquals(1, mergedTasks.size)
        val plans = mergedTasks[0].idea.plans
        assertEquals(3, plans.size)
        assertEquals("planes/plan1.md", plans[0].relativePath)
        assertEquals("planes/plan2.md", plans[1].relativePath)
        assertEquals("planes/plan3.md", plans[2].relativePath)
    }

    @Test
    fun mergeTodoDocuments_preservesExclusiveIdeasFromBothSides() {
        val localIdea = Idea(
            id = "local-only",
            status = IdeaStatus.PENDING,
            title = "Local Only",
            tags = setOf("local"),
            plans = emptyList(),
            rawLine = "- [ ] Local Only #local"
        )
        val commonIdeaLocal = Idea(
            id = "common",
            status = IdeaStatus.PENDING,
            title = "Common Task",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] Common Task"
        )
        val commonIdeaRemote = Idea(
            id = "common",
            status = IdeaStatus.IN_PROGRESS,
            title = "Common Task",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [/] Common Task"
        )
        val remoteIdea = Idea(
            id = "remote-only",
            status = IdeaStatus.COMPLETED,
            title = "Remote Only",
            tags = setOf("remote"),
            plans = emptyList(),
            rawLine = "- [x] Remote Only #remote"
        )

        val localDoc = TodoDocument(lines = listOf(
            TodoLine.Header("# Tareas", 1),
            TodoLine.Task(localIdea),
            TodoLine.Task(commonIdeaLocal)
        ))
        val remoteDoc = TodoDocument(lines = listOf(
            TodoLine.Header("# Tareas", 1),
            TodoLine.Task(commonIdeaRemote),
            TodoLine.Task(remoteIdea)
        ))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val tasks = merged.lines.filterIsInstance<TodoLine.Task>().map { it.idea }

        assertEquals(3, tasks.size)
        val ids = tasks.map { it.id }.toSet()
        assertTrue(ids.contains("local-only"))
        assertTrue(ids.contains("common"))
        assertTrue(ids.contains("remote-only"))

        val common = tasks.first { it.id == "common" }
        assertEquals(IdeaStatus.IN_PROGRESS, common.status)
    }

    @Test
    fun mergeTodoDocuments_mergesTagsSorted() {
        val localIdea = Idea(
            id = "task-tags",
            status = IdeaStatus.PENDING,
            title = "Task Tags",
            tags = setOf("backend", "urgent"),
            plans = emptyList(),
            rawLine = "- [ ] Task Tags #backend #urgent"
        )
        val remoteIdea = Idea(
            id = "task-tags",
            status = IdeaStatus.PENDING,
            title = "Task Tags",
            tags = setOf("frontend", "urgent"),
            plans = emptyList(),
            rawLine = "- [ ] Task Tags #frontend #urgent"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val task = merged.lines.filterIsInstance<TodoLine.Task>().first().idea

        assertEquals(listOf("backend", "frontend", "urgent"), task.tags.toList())
    }

    @Test
    fun mergeTodoDocuments_keepsLongerMoreDescriptiveTitle() {
        val localIdea = Idea(
            id = "ui-refactor",
            status = IdeaStatus.PENDING,
            title = "UI Refactor",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] UI Refactor <!-- id:ui-refactor -->"
        )
        val remoteIdea = Idea(
            id = "ui-refactor",
            status = IdeaStatus.PENDING,
            title = "UI Refactor with Jetpack Compose Material 3",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] UI Refactor with Jetpack Compose Material 3 <!-- id:ui-refactor -->"
        )

        val localDoc = TodoDocument(lines = listOf(TodoLine.Task(localIdea)))
        val remoteDoc = TodoDocument(lines = listOf(TodoLine.Task(remoteIdea)))

        val merged = syncEngine.mergeTodoDocuments(localDoc, remoteDoc)
        val task = merged.lines.filterIsInstance<TodoLine.Task>().first().idea

        assertEquals("UI Refactor with Jetpack Compose Material 3", task.title)
    }

    @Test
    fun syncWorkspace_unauthenticated_returnsError() = runTest {
        val workspace = Workspace(
            id = "test-ws",
            name = "Test Workspace",
            rootDir = File(tempDir, "Test Workspace").apply { mkdirs() }
        )

        val result = syncEngine.syncWorkspace(workspace)
        assertTrue(result is SyncResult.Error)
        val error = result as SyncResult.Error
        assertEquals("No hay cuenta de Google vinculada", error.message)
    }

    @Test
    fun syncWorkspace_initialSync_uploadsLocalFilesToDrive() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val auth = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val fakeAdapter = FakeDriveServiceAdapter()
        val engine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = auth,
            parser = parser,
            customAdapter = fakeAdapter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val workspace = repository.createWorkspace("MyProject")
        repository.savePlanFile(workspace, "arch.md", "# Architecture")

        val result = engine.syncWorkspace(workspace)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(3, success.uploadedCount) // CONTEXT.md, TODO.md, arch.md
        assertEquals(0, success.downloadedCount)
        assertEquals(0, success.conflictsCount)

        // Verify folders created
        assertEquals(3, fakeAdapter.folders.size) // GoPlan, MyProject, planes
        assertEquals(3, fakeAdapter.files.size)
    }

    @Test
    fun syncWorkspace_remoteTodoUpdated_mergesAndUploadsResult() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val auth = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val fakeAdapter = FakeDriveServiceAdapter()
        val engine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = auth,
            parser = parser,
            customAdapter = fakeAdapter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val workspace = repository.createWorkspace("MergeProject")

        // Initial sync
        val initResult = engine.syncWorkspace(workspace)
        assertTrue(initResult is SyncResult.Success)

        // Remote user adds a task to TODO.md on Drive
        val todoDriveFile = fakeAdapter.files.first { it.info.name == "TODO.md" }
        val remoteTodoDoc = parser.parse(todoDriveFile.content)
        val updatedRemoteDoc = parser.addIdea(remoteTodoDoc, "Remote Idea", setOf("remote"))
        todoDriveFile.content = parser.serialize(updatedRemoteDoc)

        // Local user adds a different task
        val localDoc = repository.getTodoDocument(workspace)
        val updatedLocalDoc = parser.addIdea(localDoc, "Local Idea", setOf("local"))
        repository.saveTodoDocument(workspace, updatedLocalDoc)

        // Second sync
        val syncResult = engine.syncWorkspace(workspace)
        assertTrue(syncResult is SyncResult.Success)
        val success = syncResult as SyncResult.Success
        assertEquals(1, success.uploadedCount) // Merged TODO uploaded back
        assertEquals(1, success.downloadedCount) // Remote TODO fetched for merge

        // Verify local repository has both ideas
        val finalLocalDoc = repository.getTodoDocument(workspace)
        val finalIdeas = finalLocalDoc.lines.filterIsInstance<TodoLine.Task>().map { it.idea.title }
        assertTrue(finalIdeas.contains("Local Idea"))
        assertTrue(finalIdeas.contains("Remote Idea"))

        // Verify Drive has both ideas
        val finalDriveContent = todoDriveFile.content
        assertTrue(finalDriveContent.contains("Local Idea"))
        assertTrue(finalDriveContent.contains("Remote Idea"))
    }

    @Test
    fun syncWorkspace_contextConflict_createsConflictFileAndDownloadsRemote() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val auth = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val fakeAdapter = FakeDriveServiceAdapter()
        val engine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = auth,
            parser = parser,
            customAdapter = fakeAdapter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val workspace = repository.createWorkspace("ConflictProject")

        // First sync
        engine.syncWorkspace(workspace)

        // Both edit CONTEXT.md
        val contextDriveFile = fakeAdapter.files.first { it.info.name == "CONTEXT.md" }
        contextDriveFile.content = "# Context Remote Edition\nSome remote content"
        repository.saveContextContent(workspace, "# Context Local Edition\nSome local content")

        // Sync again
        val syncResult = engine.syncWorkspace(workspace)
        assertTrue(syncResult is SyncResult.Success)
        val success = syncResult as SyncResult.Success
        assertEquals(1, success.conflictsCount)
        assertEquals(1, success.downloadedCount)

        // Check conflict file created in workspace
        val conflictFiles = workspace.rootDir.listFiles { f -> f.name.startsWith("CONTEXT.conflict-") }
        assertNotNull(conflictFiles)
        assertEquals(1, conflictFiles!!.size)
        assertTrue(conflictFiles[0].readText().contains("Context Local Edition"))

        // Check local CONTEXT.md has remote edition
        assertEquals("# Context Remote Edition\nSome remote content", repository.getContextContent(workspace))
    }

    @Test
    fun syncWorkspace_networkException_returnsErrorResult() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val auth = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val fakeAdapter = FakeDriveServiceAdapter().apply {
            throwOnUpload = true
        }
        val engine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = auth,
            parser = parser,
            customAdapter = fakeAdapter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val workspace = repository.createWorkspace("ErrorProject")
        val result = engine.syncWorkspace(workspace)

        assertTrue(result is SyncResult.Error)
        val error = result as SyncResult.Error
        assertEquals("Simulated network error", error.message)
    }
}
