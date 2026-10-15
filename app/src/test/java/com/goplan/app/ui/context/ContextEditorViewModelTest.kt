package com.goplan.app.ui.context

import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.Workspace
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
class ContextEditorViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var baseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, parser)
        workspace = fileManager.createWorkspace("TestProject")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ContextEditorViewModel {
        return ContextEditorViewModel(
            workspaceRepository = repository,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun load_existingWorkspace_loadsContextContent() {
        val initialContent = "# Contexto Custom\n\nStack: Android, Compose."
        fileManager.writeContextFile(workspace, initialContent)

        val viewModel = createViewModel()
        viewModel.load(workspace.id)

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertNotNull(state.workspace)
        assertEquals(workspace.id, state.workspace?.id)
        assertEquals(initialContent, state.content)
        assertEquals(initialContent, state.originalContent)
        assertFalse(state.hasUnsavedChanges)
        assertTrue(state.isEditMode)
        assertFalse(state.isLoading)
    }

    @Test
    fun load_nonExistentWorkspace_setsErrorMessage() {
        val viewModel = createViewModel()
        viewModel.load("non-existent-id")

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("non-existent-id"))
        assertFalse(state.isLoading)
        assertNull(state.workspace)
    }

    @Test
    fun updateContent_updatesStateAndMarksUnsavedChanges() {
        val viewModel = createViewModel()
        viewModel.load(workspace.id)

        val newContent = "# Nuevo Contexto Modificado"
        viewModel.updateContent(newContent)

        val state = viewModel.uiState.value
        assertEquals(newContent, state.content)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun saveContext_writesContentToDiskAndClearsUnsavedChanges() {
        val viewModel = createViewModel()
        viewModel.load(workspace.id)

        val modifiedText = "# Contexto de Arquitectura\n\n- Repositorio limpio."
        viewModel.updateContent(modifiedText)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)

        viewModel.saveContext()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertFalse(state.hasUnsavedChanges)
        assertEquals("Contexto guardado correctamente", state.successMessage)
        assertNull(state.errorMessage)

        // Verify content on disk
        val diskContent = repository.getContextContent(workspace)
        assertEquals(modifiedText, diskContent)
    }

    @Test
    fun saveContext_withoutLoadedWorkspace_setsErrorMessage() {
        val viewModel = createViewModel()
        viewModel.saveContext()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertEquals("No hay un workspace seleccionado", state.errorMessage)
    }

    @Test
    fun importContent_overwritesCurrentContent() {
        val viewModel = createViewModel()
        viewModel.load(workspace.id)

        val importedText = "# Contexto Importado desde PC\n\nGenerado por Orca."
        viewModel.importContent(importedText)

        val state = viewModel.uiState.value
        assertEquals(importedText, state.content)
        assertTrue(state.hasUnsavedChanges)
        assertEquals("Contenido importado", state.successMessage)
    }

    @Test
    fun resetToDefault_resetsContentToDefaultTemplate() {
        val viewModel = createViewModel()
        viewModel.load(workspace.id)

        viewModel.updateContent("Texto temporal arbitrario")
        assertEquals("Texto temporal arbitrario", viewModel.uiState.value.content)

        viewModel.resetToDefault()

        val state = viewModel.uiState.value
        assertEquals(WorkspaceFileManager.DEFAULT_CONTEXT_TEMPLATE, state.content)
    }

    @Test
    fun toggleEditMode_togglesCorrectly() {
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.isEditMode)

        viewModel.toggleEditMode()
        assertFalse(viewModel.uiState.value.isEditMode)

        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)
    }

    @Test
    fun clearMessages_clearsErrorAndSuccessMessages() {
        val viewModel = createViewModel()
        viewModel.load("invalid-ws")
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.clearMessages()
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.successMessage)
    }
}
