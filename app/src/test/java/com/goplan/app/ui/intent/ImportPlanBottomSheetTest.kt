package com.goplan.app.ui.intent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.parser.ParsedPlanFile
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
class ImportPlanBottomSheetTest {

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
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, parser)

        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_prefs_${System.nanoTime()}.preferences_pb") }
        )
        preferencesManager = PreferencesManager(dataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun testIsContextContent_detectsContextHeaders() {
        assertTrue(isContextContent("# Contexto del Proyecto\n\nStack: Android"))
        assertTrue(isContextContent("# CONTEXTO\n\nDirectrices de arquitectura"))
        assertTrue(isContextContent("# Context\n\nProject details"))
        assertTrue(isContextContent("## Contexto\n\nDetalles"))
        assertTrue(isContextContent("  # Contexto con espacios previos  "))

        assertFalse(isContextContent("# Plan de Implementación de Login"))
        assertFalse(isContextContent("=== ARCHIVO: auth-plan.md ===\nContenido"))
        assertFalse(isContextContent("- [ ] Tarea simple sin contexto"))
        assertFalse(isContextContent(""))
    }

    @Test
    fun testExecuteImportPlans_savesFiles_linksInTodo_advancesStatus_andClearsRecentExpansion() = runTest {
        val workspace = repository.createWorkspace("MiProyecto")
        val initialDoc = repository.getTodoDocument(workspace)
        val docWithIdea = parser.addIdea(initialDoc, "Implementar autenticación biométrica", setOf("seguridad"))
        repository.saveTodoDocument(workspace, docWithIdea)

        val idea = docWithIdea.lines.filterIsInstance<TodoLine.Task>().first().idea
        assertEquals(IdeaStatus.PENDING, idea.status)

        // Record a recent expansion for loopback
        preferencesManager.recordRecentExpansion(workspace.id, idea.id)
        assertNotNull(preferencesManager.getRecentExpansion())

        val planFiles = listOf(
            ParsedPlanFile(
                fileName = "biometria-core.md",
                content = "# Biometría Core\nDetalle técnico fase 1"
            ),
            ParsedPlanFile(
                fileName = "biometria-ui.md",
                content = "# Biometría UI\nComponentes Compose fase 2"
            )
        )

        // Execute import
        executeImportPlans(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            todoParser = parser,
            workspace = workspace,
            idea = idea,
            plans = planFiles
        )

        // 1. Verify plan files exist on disk
        val planesDir = File(workspace.rootDir, "planes")
        val file1 = File(planesDir, "biometria-core.md")
        val file2 = File(planesDir, "biometria-ui.md")
        assertTrue("biometria-core.md must exist on disk", file1.exists())
        assertTrue("biometria-ui.md must exist on disk", file2.exists())
        assertEquals("# Biometría Core\nDetalle técnico fase 1", file1.readText(Charsets.UTF_8))
        assertEquals("# Biometría UI\nComponentes Compose fase 2", file2.readText(Charsets.UTF_8))

        // 2. Verify TODO.md has updated status and links
        val updatedDoc = repository.getTodoDocument(workspace)
        val updatedIdea = updatedDoc.lines.filterIsInstance<TodoLine.Task>().first().idea
        assertEquals(IdeaStatus.IN_PROGRESS, updatedIdea.status)
        assertEquals(2, updatedIdea.plans.size)
        assertEquals("biometria-core", updatedIdea.plans[0].label)
        assertEquals("planes/biometria-core.md", updatedIdea.plans[0].relativePath)
        assertEquals("biometria-ui", updatedIdea.plans[1].label)
        assertEquals("planes/biometria-ui.md", updatedIdea.plans[1].relativePath)

        val serialized = parser.serialize(updatedDoc)
        assertTrue(serialized.contains("[/] Implementar autenticación biométrica"))
        assertTrue(serialized.contains("[biometria-core](planes/biometria-core.md)"))
        assertTrue(serialized.contains("[biometria-ui](planes/biometria-ui.md)"))

        // 3. Verify recent expansion was cleared
        assertNull(preferencesManager.getRecentExpansion())
    }

    @Test
    fun testExecuteImportContext_savesContextFile_andClearsRecentExpansion() = runTest {
        val workspace = repository.createWorkspace("ContextTest")
        preferencesManager.recordRecentExpansion(workspace.id, "some-idea-id")
        assertNotNull(preferencesManager.getRecentExpansion())

        val newContext = """
            # Contexto del Proyecto Actualizado
            - Nuevo stack: KMP y Compose
            - Base de datos: SQLite
        """.trimIndent()

        executeImportContext(
            workspaceRepository = repository,
            preferencesManager = preferencesManager,
            workspace = workspace,
            contextContent = newContext
        )

        // 1. Verify context file on disk
        val contextOnDisk = repository.getContextContent(workspace)
        assertEquals(newContext, contextOnDisk)

        // 2. Verify recent expansion was cleared
        assertNull(preferencesManager.getRecentExpansion())
    }
}
