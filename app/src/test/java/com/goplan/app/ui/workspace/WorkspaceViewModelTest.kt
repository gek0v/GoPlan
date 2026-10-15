package com.goplan.app.ui.workspace

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.parser.TodoMarkdownParser
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

        // Switch to Workspace 2
        viewModel.switchWorkspace(ws2)
        val state = viewModel.uiState.value

        assertEquals("Workspace 2", state.activeWorkspace?.name)
        assertEquals(1, state.ideas.size)
        assertEquals("Idea en ws2", state.ideas[0].title)
        assertTrue(state.availableTags.contains("ws2tag"))
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
}
