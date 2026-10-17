package com.goplan.app.ui.workspace

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.data.sync.DriveServiceAdapter
import com.goplan.app.data.sync.GoogleDriveAuthManager
import com.goplan.app.data.sync.GoogleDriveSyncEngine
import com.goplan.app.data.sync.model.RemoteFileInfo
import com.goplan.app.data.update.AppUpdateManager
import com.goplan.app.data.update.ReleaseApiClient
import com.goplan.app.data.update.UpdateCheckResult
import com.goplan.app.data.update.UpdateDownloadState
import com.goplan.app.data.update.UpdateInfo
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.server.KtorServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var baseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, parser)

        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_prefs.preferences_pb") }
        )
        preferencesManager = PreferencesManager(dataStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        testScope.cancel()
    }

    private fun createViewModel(): WorkspaceViewModel {
        return WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testInitialLoad_createsDefaultWorkspaceWhenEmpty() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertNotNull(state.activeWorkspace)
        assertEquals("Default", state.activeWorkspace?.name)
        assertEquals(1, state.workspaces.size)
        assertEquals("Default", state.workspaces[0].name)
        assertTrue(state.ideas.isEmpty())
    }

    @Test
    fun testInitialLoad_restoresSavedActiveWorkspace() = runTest {
        repository.createWorkspace("Alpha")
        val beta = repository.createWorkspace("Beta")
        preferencesManager.saveActiveWorkspace(beta.id)

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertNotNull(state.activeWorkspace)
        assertEquals("Beta", state.activeWorkspace?.name)
        assertEquals(2, state.workspaces.size)
    }

    @Test
    fun testCreateWorkspace_switchesToNewWorkspace() = runTest {
        val viewModel = createViewModel()

        viewModel.createWorkspace("Proyecto X")
        val state = viewModel.uiState.value

        assertNotNull(state.activeWorkspace)
        assertEquals("Proyecto X", state.activeWorkspace?.name)
        assertTrue(state.workspaces.any { it.name == "Proyecto X" })
    }

    @Test
    fun testSwitchWorkspace_updatesActiveWorkspaceAndLoadsItsIdeas() = runTest {
        val ws1 = repository.createWorkspace("Workspace 1")
        val ws2 = repository.createWorkspace("Workspace 2")

        // Add idea to Workspace 2 directly via parser and repository
        val doc2 = repository.getTodoDocument(ws2)
        val updatedDoc2 = parser.addIdea(doc2, "Idea en ws2", setOf("ws2tag"))
        repository.saveTodoDocument(ws2, updatedDoc2)

        preferencesManager.saveActiveWorkspace(ws1.id)
        val viewModel = createViewModel()

        assertEquals("Workspace 1", viewModel.uiState.value.activeWorkspace?.name)
        assertTrue(viewModel.uiState.value.ideas.isEmpty())

        // Set a tag filter in Workspace 1
        viewModel.toggleTagFilter("someTag")
        assertEquals(setOf("someTag"), viewModel.uiState.value.selectedTags)

        // Switch to Workspace 2
        viewModel.switchWorkspace(ws2)
        val state = viewModel.uiState.value

        assertEquals("Workspace 2", state.activeWorkspace?.name)
        assertEquals(1, state.ideas.size)
        assertEquals("Idea en ws2", state.ideas[0].title)
        assertTrue(state.availableTags.contains("ws2tag"))
        assertTrue("Switching workspace must reset selectedTags", state.selectedTags.isEmpty())
    }

    @Test
    fun testAddIdea_extractsTagsAndPersistsToDisk() = runTest {
        val viewModel = createViewModel()

        viewModel.addIdea("Construir UI login #auth #mobile")
        val state = viewModel.uiState.value

        assertEquals(1, state.allIdeas.size)
        val idea = state.allIdeas[0]
        assertEquals("Construir UI login", idea.title)
        assertEquals(setOf("auth", "mobile"), idea.tags)
        assertEquals(IdeaStatus.PENDING, idea.status)
        assertEquals(listOf("auth", "mobile"), state.availableTags)

        // Verify disk content
        val activeWs = state.activeWorkspace!!
        val diskDoc = repository.getTodoDocument(activeWs)
        val diskIdeas = parser.serialize(diskDoc)
        assertTrue(diskIdeas.contains("Construir UI login"))
        assertTrue(diskIdeas.contains("#auth"))
        assertTrue(diskIdeas.contains("#mobile"))
    }

    @Test
    fun testCycleStatus_advancesThroughPendingInProgressCompleted() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Tarea cíclica")

        val initialIdea = viewModel.uiState.value.ideas[0]
        assertEquals(IdeaStatus.PENDING, initialIdea.status)

        // 1st cycle: PENDING -> IN_PROGRESS
        viewModel.cycleStatus(initialIdea)
        val inProgressIdea = viewModel.uiState.value.ideas[0]
        assertEquals(IdeaStatus.IN_PROGRESS, inProgressIdea.status)

        // 2nd cycle: IN_PROGRESS -> COMPLETED
        viewModel.cycleStatus(inProgressIdea)
        val completedIdea = viewModel.uiState.value.ideas[0]
        assertEquals(IdeaStatus.COMPLETED, completedIdea.status)

        // 3rd cycle: COMPLETED -> PENDING
        viewModel.cycleStatus(completedIdea)
        val backToPendingIdea = viewModel.uiState.value.ideas[0]
        assertEquals(IdeaStatus.PENDING, backToPendingIdea.status)
    }

    @Test
    fun testCycleStatus_withStaleIdeaSnapshot_usesCurrentDocumentStatus() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Tarea con snapshot desactualizado")

        val staleInitialIdea = viewModel.uiState.value.ideas[0]
        assertEquals(IdeaStatus.PENDING, staleInitialIdea.status)

        // 1st cycle: PENDING -> IN_PROGRESS
        viewModel.cycleStatus(staleInitialIdea)
        assertEquals(IdeaStatus.IN_PROGRESS, viewModel.uiState.value.ideas[0].status)

        // 2nd cycle: pass the same STALE initialIdea snapshot (whose status is still PENDING)
        // ViewModel must look up from TODO.md and advance from IN_PROGRESS -> COMPLETED
        viewModel.cycleStatus(staleInitialIdea)
        assertEquals(IdeaStatus.COMPLETED, viewModel.uiState.value.ideas[0].status)
    }

    @Test
    fun testDiscardIdea_marksAsDiscardedAndHidesByDefault() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Idea para descartar")

        val idea = viewModel.uiState.value.ideas[0]
        viewModel.discardIdea(idea)

        val state = viewModel.uiState.value
        // Default filter hides DISCARDED ideas
        assertTrue("Discarded idea should be hidden in default view", state.ideas.isEmpty())
        // But remains in allIdeas
        assertEquals(1, state.allIdeas.size)
        assertEquals(IdeaStatus.DISCARDED, state.allIdeas[0].status)
    }

    @Test
    fun testStatusFiltering_togglingDiscardedShowsDiscardedIdeas() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Idea activa")
        viewModel.addIdea("Idea descartada")

        val ideaToDiscard = viewModel.uiState.value.ideas.first { it.title == "Idea descartada" }
        viewModel.discardIdea(ideaToDiscard)

        // Default: only active idea visible
        assertEquals(1, viewModel.uiState.value.ideas.size)
        assertEquals("Idea activa", viewModel.uiState.value.ideas[0].title)

        // Filter for DISCARDED
        viewModel.toggleStatusFilter(IdeaStatus.DISCARDED)
        val discardedState = viewModel.uiState.value
        assertEquals(1, discardedState.ideas.size)
        assertEquals("Idea descartada", discardedState.ideas[0].title)

        // Clear status filter -> back to default (only non-discarded)
        viewModel.clearStatusFilter()
        assertEquals(1, viewModel.uiState.value.ideas.size)
        assertEquals("Idea activa", viewModel.uiState.value.ideas[0].title)
    }

    @Test
    fun testTagFiltering_filtersIdeasBySelectedTags() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Backend auth #backend")
        viewModel.addIdea("Frontend login #frontend")
        viewModel.addIdea("Fullstack feature #backend #frontend")

        assertEquals(3, viewModel.uiState.value.ideas.size)

        // Filter by backend
        viewModel.toggleTagFilter("backend")
        val backendIdeas = viewModel.uiState.value.ideas.map { it.title }
        assertEquals(2, backendIdeas.size)
        assertTrue(backendIdeas.contains("Backend auth"))
        assertTrue(backendIdeas.contains("Fullstack feature"))

        // Add filter by frontend (now requires both backend AND frontend)
        viewModel.toggleTagFilter("frontend")
        val bothIdeas = viewModel.uiState.value.ideas.map { it.title }
        assertEquals(1, bothIdeas.size)
        assertEquals("Fullstack feature", bothIdeas[0])

        // Toggle backend off -> only frontend
        viewModel.toggleTagFilter("backend")
        val frontendIdeas = viewModel.uiState.value.ideas.map { it.title }
        assertEquals(2, frontendIdeas.size)
        assertTrue(frontendIdeas.contains("Frontend login"))
        assertTrue(frontendIdeas.contains("Fullstack feature"))

        // Clear all tag filters -> all 3
        viewModel.clearTagFilters()
        assertEquals(3, viewModel.uiState.value.ideas.size)
    }

    @Test
    fun testCreatePlanManually_createsFileAndLinksToIdea() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Diseñar base de datos")

        val idea = viewModel.uiState.value.ideas.first { it.title == "Diseñar base de datos" }
        assertEquals(IdeaStatus.PENDING, idea.status)
        assertTrue(idea.plans.isEmpty())

        var createdLink: com.goplan.app.domain.model.PlanLink? = null
        viewModel.createPlanManually(idea) { link ->
            createdLink = link
        }

        assertNotNull(createdLink)
        assertEquals("disenar-base-de-datos", createdLink?.label)
        assertEquals("planes/disenar-base-de-datos.md", createdLink?.relativePath)

        val updatedIdea = viewModel.uiState.value.ideas.first { it.title == "Diseñar base de datos" }
        assertEquals(IdeaStatus.IN_PROGRESS, updatedIdea.status)
        assertEquals(1, updatedIdea.plans.size)
        assertEquals("planes/disenar-base-de-datos.md", updatedIdea.plans[0].relativePath)

        // Verify the plan file exists and has content
        val activeWs = viewModel.uiState.value.activeWorkspace!!
        val content = repository.readPlanFile(activeWs, "planes/disenar-base-de-datos.md")
        assertTrue(content.contains("# Plan: Diseñar base de datos"))
    }

    @Test
    fun testEditIdea_updatesTitleAndTagsInTodoAndUiState() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Idea inicial #v1")

        val idea = viewModel.uiState.value.ideas.first { it.title == "Idea inicial" }
        viewModel.editIdea(idea, "Idea modificada", setOf("v2", "release"))

        val updatedIdeas = viewModel.uiState.value.ideas
        assertEquals(1, updatedIdeas.size)
        assertEquals("Idea modificada", updatedIdeas[0].title)
        assertEquals(setOf("v2", "release"), updatedIdeas[0].tags)

        val activeWs = viewModel.uiState.value.activeWorkspace!!
        val doc = repository.getTodoDocument(activeWs)
        val task = doc.lines.filterIsInstance<com.goplan.app.domain.model.TodoLine.Task>().first()
        assertEquals("Idea modificada", task.idea.title)
        assertEquals(setOf("v2", "release"), task.idea.tags)
    }

    @Test
    fun testDeleteIdeaPermanently_removesIdeaAndAssociatedPlanFiles() = runTest {
        val viewModel = createViewModel()
        viewModel.addIdea("Idea a eliminar")

        val idea = viewModel.uiState.value.ideas.first()
        viewModel.createPlanManually(idea) {}

        val activeWs = viewModel.uiState.value.activeWorkspace!!
        val planFile = File(activeWs.rootDir, "planes/idea-a-eliminar.md")
        assertTrue(planFile.exists())

        val ideaWithPlan = viewModel.uiState.value.ideas.first()
        viewModel.deleteIdeaPermanently(ideaWithPlan, deleteAssociatedPlans = true)

        assertTrue(viewModel.uiState.value.ideas.isEmpty())
        assertFalse(planFile.exists())

        val doc = repository.getTodoDocument(activeWs)
        val tasks = doc.lines.filterIsInstance<com.goplan.app.domain.model.TodoLine.Task>()
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun testViewModelRefreshesWhenWorkspaceChangesFlowEmits() = runTest {
        val viewModel = createViewModel()
        val activeWs = viewModel.uiState.value.activeWorkspace!!
        assertEquals("Default", activeWs.name)
        assertTrue(viewModel.uiState.value.ideas.isEmpty())

        // Simular que un agente o Ktor guarda una nueva idea directamente en disco via repository
        val currentDoc = repository.getTodoDocument(activeWs)
        val updatedDoc = parser.addIdea(currentDoc, "Idea externa recibida", setOf("reactivo"))
        repository.saveTodoDocument(activeWs, updatedDoc)

        val state = viewModel.uiState.value
        assertEquals(1, state.ideas.size)
        assertEquals("Idea externa recibida", state.ideas[0].title)
        assertTrue(state.availableTags.contains("reactivo"))
    }

    @Test
    fun testServerState_whenManagerIsNull_defaultsToFalseAndNull() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.isServerRunning.value)
        assertNull(viewModel.serverUrl.value)

        viewModel.toggleServer(true)
        assertFalse(viewModel.isServerRunning.value)
        assertNull(viewModel.serverUrl.value)

        viewModel.toggleServer(false)
        assertFalse(viewModel.isServerRunning.value)
        assertNull(viewModel.serverUrl.value)
    }

    @Test
    fun testServerState_withKtorServerManager_togglesCorrectly() = runTest {
        val serverManager = KtorServerManager(
            repository = repository,
            preferencesManager = preferencesManager,
            ipProvider = { "127.0.0.1" }
        )

        try {
            val viewModel = WorkspaceViewModel(
                workspaceRepository = repository,
                preferencesManager = preferencesManager,
                parser = parser,
                ktorServerManager = serverManager,
                ioDispatcher = testDispatcher
            )

            assertFalse(viewModel.isServerRunning.value)
            assertNull(viewModel.serverUrl.value)

            // Toggle server ON
            viewModel.toggleServer(true)
            assertTrue("Server should be running after toggleServer(true)", viewModel.isServerRunning.value)
            assertNotNull(viewModel.serverUrl.value)
            assertTrue(viewModel.serverUrl.value!!.startsWith("http://127.0.0.1:"))

            // Toggle server OFF
            viewModel.toggleServer(false)
            assertFalse("Server should not be running after toggleServer(false)", viewModel.isServerRunning.value)
            assertNull(viewModel.serverUrl.value)
        } finally {
            serverManager.stop()
        }
    }

    private class FakeDriveServiceAdapter : DriveServiceAdapter {
        val folders = mutableListOf<RemoteFileInfo>()
        val files = mutableListOf<RemoteFileInfo>()
        var throwError = false

        override fun findFolder(name: String, parentId: String?): RemoteFileInfo? {
            return folders.firstOrNull { it.name == name }
        }

        override fun createFolder(name: String, parentId: String?): RemoteFileInfo {
            val f = RemoteFileInfo("folder-${folders.size + 1}", name)
            folders.add(f)
            return f
        }

        override fun listFiles(folderId: String): List<RemoteFileInfo> = files

        override fun downloadFile(fileId: String): String = ""

        override fun uploadFile(name: String, parentId: String, content: String, mimeType: String): RemoteFileInfo {
            if (throwError) throw java.io.IOException("Simulated network failure")
            val f = RemoteFileInfo("file-${files.size + 1}", name)
            files.add(f)
            return f
        }

        override fun updateFile(fileId: String, content: String): RemoteFileInfo {
            if (throwError) throw java.io.IOException("Simulated network failure")
            return RemoteFileInfo(fileId, "updated")
        }
    }

    @Test
    fun testSyncState_initialValues_whenEngineAndAuthNull() = runTest {
        val viewModel = createViewModel()

        assertNull(viewModel.signedInAccount.value)
        assertFalse(viewModel.isSyncing.value)
        assertNull(viewModel.lastSyncTimestamp.value)
        assertNull(viewModel.syncError.value)
        assertNull(viewModel.syncSuccessMessage.value)
    }

    @Test
    fun testSyncActiveWorkspace_whenEngineNull_setsErrorMessage() = runTest {
        val viewModel = createViewModel()

        viewModel.syncActiveWorkspace()

        assertFalse(viewModel.isSyncing.value)
        assertEquals("Motor de sincronización no disponible", viewModel.syncError.value)
        assertNull(viewModel.syncSuccessMessage.value)
    }

    @Test
    fun testSyncActiveWorkspace_success_updatesTimestampAndSuccessMessage() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val adapter = FakeDriveServiceAdapter()
        val syncEngine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = authManager,
            parser = parser,
            customAdapter = adapter,
            ioDispatcher = testDispatcher
        )

        val viewModel = WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            syncEngine = syncEngine,
            authManager = authManager,
            ioDispatcher = testDispatcher
        )

        assertEquals(account, viewModel.signedInAccount.value)
        assertNull(viewModel.lastSyncTimestamp.value)

        viewModel.syncActiveWorkspace()

        assertFalse(viewModel.isSyncing.value)
        assertNotNull(viewModel.lastSyncTimestamp.value)
        assertNull(viewModel.syncError.value)
        assertNotNull(viewModel.syncSuccessMessage.value)
        assertTrue(viewModel.syncSuccessMessage.value!!.startsWith("Sincronización completada"))
    }

    @Test
    fun testSyncAllWorkspaces_success_updatesTimestampAndRefreshesWorkspaces() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val adapter = FakeDriveServiceAdapter()
        val syncEngine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = authManager,
            parser = parser,
            customAdapter = adapter,
            ioDispatcher = testDispatcher
        )

        val viewModel = WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            syncEngine = syncEngine,
            authManager = authManager,
            ioDispatcher = testDispatcher
        )

        viewModel.syncAllWorkspaces()

        assertFalse(viewModel.isSyncing.value)
        assertNotNull(viewModel.lastSyncTimestamp.value)
        assertNull(viewModel.syncError.value)
        assertNotNull(viewModel.syncSuccessMessage.value)
        assertTrue(viewModel.syncSuccessMessage.value!!.contains("Global"))
    }

    @Test
    fun testSyncActiveWorkspace_error_updatesSyncError() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        val adapter = FakeDriveServiceAdapter().apply {
            throwError = true
        }
        val syncEngine = GoogleDriveSyncEngine(
            repository = repository,
            authManager = authManager,
            parser = parser,
            customAdapter = adapter,
            ioDispatcher = testDispatcher
        )

        val viewModel = WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            syncEngine = syncEngine,
            authManager = authManager,
            ioDispatcher = testDispatcher
        )

        viewModel.syncActiveWorkspace()

        assertFalse(viewModel.isSyncing.value)
        assertNotNull(viewModel.syncError.value)
        assertEquals("Simulated network failure", viewModel.syncError.value)
        assertNull(viewModel.syncSuccessMessage.value)
    }

    @Test
    fun testSignOutGoogle_clearsAccountAndSyncMessages() = runTest {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )

        val viewModel = WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            authManager = authManager,
            ioDispatcher = testDispatcher
        )

        assertEquals(account, viewModel.signedInAccount.value)

        viewModel.signOutGoogle()

        assertNull(viewModel.signedInAccount.value)
        assertNull(viewModel.syncError.value)
        assertNull(viewModel.syncSuccessMessage.value)
    }

    @Test
    fun testCheckForUpdates_availableShowsDialog() = runTest {
        val mockClient = object : ReleaseApiClient {
            override suspend fun fetchLatestRelease(): String = """
                {
                  "tag_name": "v9.9.9",
                  "name": "Super release",
                  "body": "Amazing features",
                  "assets": [{ "name": "app.apk", "browser_download_url": "https://example.com/app.apk", "size": 12345 }]
                }
            """.trimIndent()
            override suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit) {}
        }
        val updateManager = AppUpdateManager(
            context = null,
            apiClient = mockClient,
            ioDispatcher = testDispatcher,
            cacheDirProvider = { tempFolder.newFolder("update_vm_test") }
        )
        val viewModel = WorkspaceViewModel(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            parser = parser,
            appUpdateManager = updateManager,
            ioDispatcher = testDispatcher
        )

        viewModel.checkForUpdates(silent = false)

        assertTrue(viewModel.updateCheckResult.value is UpdateCheckResult.UpdateAvailable)
        assertTrue(viewModel.showUpdateDialog.value)

        viewModel.dismissUpdateDialog()
        assertFalse(viewModel.showUpdateDialog.value)
    }

    @Test
    fun testOpenAndCloseWorkspaceSheet() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.showWorkspaceSheet.value)

        viewModel.openWorkspaceSheet()
        assertTrue(viewModel.showWorkspaceSheet.value)

        viewModel.closeWorkspaceSheet()
        assertFalse(viewModel.showWorkspaceSheet.value)
    }

    @Test
    fun testCreateAndSwitchWorkspace_success_switchesAndRefreshes() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.createAndSwitchWorkspace("Project Delta")

        assertTrue(result.isSuccess)
        val created = result.getOrNull()!!
        assertEquals("Project Delta", created.name)
        assertEquals(created.id, viewModel.uiState.value.activeWorkspace?.id)
        assertFalse(viewModel.showWorkspaceSheet.value)
    }

    @Test
    fun testCreateAndSwitchWorkspace_duplicateName_fails() = runTest {
        val viewModel = createViewModel()
        viewModel.createAndSwitchWorkspace("Existing")

        val duplicateResult = viewModel.createAndSwitchWorkspace("existing")

        assertTrue(duplicateResult.isFailure)
        assertTrue(duplicateResult.exceptionOrNull()?.message?.contains("Ya existe") == true)
    }

    @Test
    fun testCreateAndSwitchWorkspace_invalidChars_fails() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.createAndSwitchWorkspace("bad/name?*")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("inválidos") == true)
    }

    @Test
    fun testCreateAndSwitchWorkspace_blankName_fails() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.createAndSwitchWorkspace("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("vacío") == true)
    }

    @Test
    fun testLoadWorkspaceSummaries_updatesState() = runTest {
        val viewModel = createViewModel()
        viewModel.createAndSwitchWorkspace("Space 1")
        viewModel.createAndSwitchWorkspace("Space 2")

        viewModel.loadWorkspaceSummaries()

        val summaries = viewModel.workspaceSummaries.value
        assertTrue(summaries.size >= 2)
        val activeSummary = summaries.find { it.workspace.name == "Space 2" }
        assertNotNull(activeSummary)
        assertTrue(activeSummary?.isActive == true)
    }

    @Test
    fun testLoadWorkspaceSummaries_multipleCalls_cancelsPreviousAndCompletesWithLatestState() = runTest {
        val viewModel = createViewModel()
        viewModel.createAndSwitchWorkspace("Project A")
        viewModel.createAndSwitchWorkspace("Project B")

        // Rapid successive invocations should cancel previous in-flight jobs and produce correct summaries
        viewModel.loadWorkspaceSummaries()
        viewModel.loadWorkspaceSummaries()
        viewModel.loadWorkspaceSummaries()

        val summaries = viewModel.workspaceSummaries.value
        assertEquals(3, summaries.size) // Default, Project A, Project B
        val activeSummary = summaries.find { it.workspace.name == "Project B" }
        assertNotNull(activeSummary)
        assertTrue(activeSummary?.isActive == true)
    }
}


