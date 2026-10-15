package com.goplan.app.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.Workspace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextEditorUiState(
    val workspace: Workspace? = null,
    val content: String = "",
    val originalContent: String = "",
    val isEditMode: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    val hasUnsavedChanges: Boolean
        get() = content != originalContent
}

class ContextEditorViewModel(
    private val workspaceRepository: WorkspaceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContextEditorUiState())
    val uiState: StateFlow<ContextEditorUiState> = _uiState.asStateFlow()

    fun load(workspaceId: String) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            val workspaces = workspaceRepository.getWorkspaces()
            val workspace = workspaces.find { it.id == workspaceId }
            if (workspace == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Workspace no encontrado: $workspaceId"
                    )
                }
                return@launch
            }

            try {
                val contextContent = workspaceRepository.getContextContent(workspace)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        workspace = workspace,
                        content = contextContent,
                        originalContent = contextContent,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error al cargar contexto: ${e.message}"
                    )
                }
            }
        }
    }

    fun updateContent(newContent: String) {
        _uiState.update { it.copy(content = newContent) }
    }

    fun saveContext() {
        val currentState = _uiState.value
        val workspace = currentState.workspace ?: run {
            _uiState.update { it.copy(errorMessage = "No hay un workspace seleccionado") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
            try {
                workspaceRepository.saveContextContent(workspace, currentState.content)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        originalContent = currentState.content,
                        successMessage = "Contexto guardado correctamente"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Error al guardar contexto: ${e.message}"
                    )
                }
            }
        }
    }

    fun importContent(newContent: String) {
        _uiState.update {
            it.copy(
                content = newContent,
                successMessage = "Contenido importado"
            )
        }
    }

    fun resetToDefault() {
        _uiState.update {
            it.copy(content = WorkspaceFileManager.DEFAULT_CONTEXT_TEMPLATE)
        }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    companion object {
        fun provideFactory(
            repository: WorkspaceRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ContextEditorViewModel(repository, ioDispatcher) as T
            }
        }
    }
}
