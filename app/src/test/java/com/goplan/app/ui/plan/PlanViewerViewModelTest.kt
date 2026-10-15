package com.goplan.app.ui.plan

import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.GeminiPlanParser
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class PlanViewerViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var baseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var geminiParser: GeminiPlanParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        parser = TodoMarkdownParser()
        geminiParser = GeminiPlanParser()
        repository = WorkspaceRepository(fileManager, parser)
        workspace = fileManager.createWorkspace("MiProyecto")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlanViewerViewModel {
        return PlanViewerViewModel(
            workspaceRepository = repository,
            parser = parser,
            geminiParser = geminiParser,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun load_existingPlan_loadsCorrectly() {
        val todoContent = """
            # Tareas
            
            - [/] Implementar UI ([Plan Principal](planes/plan-principal.md)) <!-- id:idea-ui -->
        """.trimIndent()
        fileManager.writeTodoFile(workspace, todoContent)

        val planContent = "# Plan Principal\n\nContenido de prueba."
        fileManager.savePlanFile(workspace, "plan-principal.md", planContent)

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-ui", "planes/plan-principal.md")

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertNotNull(state.workspace)
        assertEquals(workspace.id, state.workspace?.id)
        assertEquals("idea-ui", state.idea?.id)
        assertEquals(1, state.plans.size)
        assertEquals("Plan Principal", state.plans[0].label)
        assertEquals(0, state.activePlanIndex)
        assertEquals(planContent, state.planContent)
        assertFalse(state.isEditMode)
    }

    @Test
    fun load_nonExistentPlanFile_initializesWithFallback() {
        val todoContent = """
            # Tareas
            
            - [ ] Nueva Idea <!-- id:idea-nueva -->
        """.trimIndent()
        fileManager.writeTodoFile(workspace, todoContent)

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-nueva", null)

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertNotNull(state.workspace)
        assertEquals("idea-nueva", state.idea?.id)
        assertEquals(1, state.plans.size)
        assertEquals("# Plan", state.planContent)
    }

    @Test
    fun toggleEditMode_togglesCorrectly() {
        val viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isEditMode)

        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)

        viewModel.toggleEditMode()
        assertFalse(viewModel.uiState.value.isEditMode)
    }

    @Test
    fun updateContent_and_saveCurrentPlan_writesToDisk() {
        val todoContent = """
            # Tareas
            
            - [/] Feature X ([Plan X](planes/plan-x.md)) <!-- id:idea-x -->
        """.trimIndent()
        fileManager.writeTodoFile(workspace, todoContent)
        fileManager.savePlanFile(workspace, "plan-x.md", "# Original")

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-x", "planes/plan-x.md")

        val newContent = "# Actualizado\n\nTexto modificado por el usuario."
        viewModel.updateContent(newContent)
        assertEquals(newContent, viewModel.uiState.value.planContent)

        viewModel.saveCurrentPlan()

        val savedDiskContent = repository.readPlanFile(workspace, "planes/plan-x.md")
        assertEquals(newContent, savedDiskContent)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun selectPlan_savesPendingEditsAndSwitchesTabs() {
        val todoContent = """
            # Tareas
            
            - [/] Multi Plan ([Tab 1](planes/tab1.md), [Tab 2](planes/tab2.md)) <!-- id:idea-multi -->
        """.trimIndent()
        fileManager.writeTodoFile(workspace, todoContent)
        fileManager.savePlanFile(workspace, "tab1.md", "# Contenido Tab 1")
        fileManager.savePlanFile(workspace, "tab2.md", "# Contenido Tab 2")

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-multi", "planes/tab1.md")

        assertEquals(0, viewModel.uiState.value.activePlanIndex)
        assertEquals("# Contenido Tab 1", viewModel.uiState.value.planContent)

        // Make edit on Tab 1
        viewModel.updateContent("# Contenido Tab 1 con edición pendiente")

        // Switch to Tab 2
        viewModel.selectPlan(1)

        val state = viewModel.uiState.value
        assertEquals(1, state.activePlanIndex)
        assertEquals("# Contenido Tab 2", state.planContent)

        // Verify Tab 1 edits were saved to disk automatically
        val diskTab1 = repository.readPlanFile(workspace, "planes/tab1.md")
        assertEquals("# Contenido Tab 1 con edición pendiente", diskTab1)
    }

    @Test
    fun splitPlanFromCursor_truncatesCurrent_createsNewPlan_and_updatesTodo() {
        val originalPlan = """
            # Plan Completo
            
            ## Fase 1
            Pasos de la fase 1.
            
            ## Fase 2
            Pasos de la fase 2.
        """.trimIndent()

        val todoContent = """
            # Tareas
            
            - [/] Gran Tarea ([Plan Original](planes/plan-original.md)) <!-- id:idea-split -->
        """.trimIndent()
        fileManager.writeTodoFile(workspace, todoContent)
        fileManager.savePlanFile(workspace, "plan-original.md", originalPlan)

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-split", "planes/plan-original.md")

        val splitMarker = "## Fase 2"
        val cursorIndex = originalPlan.indexOf(splitMarker)
        assertTrue("Marker must exist in original plan", cursorIndex > 0)

        // Split with custom raw name
        viewModel.splitPlanFromCursor(cursorIndex, "fase-2-plan")

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertEquals(2, state.plans.size)
        assertEquals("Plan Original", state.plans[0].label)
        assertEquals("fase-2-plan", state.plans[1].label)
        assertEquals("planes/fase-2-plan.md", state.plans[1].relativePath)
        assertEquals(1, state.activePlanIndex)

        // 1. Verify new active content is the split portion
        val expectedNewContent = originalPlan.substring(cursorIndex)
        assertEquals(expectedNewContent, state.planContent)

        // 2. Verify new file was written to disk
        val diskNewContent = repository.readPlanFile(workspace, "planes/fase-2-plan.md")
        assertEquals(expectedNewContent, diskNewContent)

        // 3. Verify original file on disk was truncated
        val expectedOriginalTruncated = originalPlan.substring(0, cursorIndex)
        val diskOriginalContent = repository.readPlanFile(workspace, "planes/plan-original.md")
        assertEquals(expectedOriginalTruncated, diskOriginalContent)

        // 4. Verify TODO.md was updated with both plan links
        val updatedTodoDoc = repository.getTodoDocument(workspace)
        val updatedIdea = updatedTodoDoc.lines
            .filterIsInstance<com.goplan.app.domain.model.TodoLine.Task>()
            .find { it.idea.id == "idea-split" }
            ?.idea

        assertNotNull(updatedIdea)
        assertEquals(2, updatedIdea?.plans?.size)
        assertEquals("Plan Original", updatedIdea?.plans?.get(0)?.label)
        assertEquals("fase-2-plan", updatedIdea?.plans?.get(1)?.label)
        assertTrue(updatedIdea!!.rawLine.contains("[Plan Original](planes/plan-original.md)"))
        assertTrue(updatedIdea.rawLine.contains("[fase-2-plan](planes/fase-2-plan.md)"))
    }

    @Test
    fun suggestPlanFileName_extractsHeadingOrDefaults() {
        val withHeading = "## Detalle del Sub-Módulo\nTexto explicativo"
        assertEquals("detalle-del-sub-modulo", com.goplan.app.ui.plan.components.suggestPlanFileName(withHeading))

        val withoutHeading = "Texto simple sin títulos ni encabezados"
        assertEquals("sub-plan", com.goplan.app.ui.plan.components.suggestPlanFileName(withoutHeading))
    }

    @Test
    fun splitPlanFromCursor_normalizesAccentedName() {
        val originalPlan = "# Principal\n\nContenido A\n\nContenido B"
        val todoContent = "- [/] Tarea ([Plan](planes/plan.md)) <!-- id:idea-acc -->"
        fileManager.writeTodoFile(workspace, todoContent)
        fileManager.savePlanFile(workspace, "plan.md", originalPlan)

        val viewModel = createViewModel()
        viewModel.load(workspace.id, "idea-acc", "planes/plan.md")

        val cursor = originalPlan.indexOf("Contenido B")
        viewModel.splitPlanFromCursor(cursor, "Sección Crítica: Detalle")

        val state = viewModel.uiState.value
        assertEquals("seccion-critica-detalle", state.plans.last().label)
        assertEquals("planes/seccion-critica-detalle.md", state.plans.last().relativePath)
        assertTrue(File(workspace.rootDir, "planes/seccion-critica-detalle.md").exists())
    }
}
