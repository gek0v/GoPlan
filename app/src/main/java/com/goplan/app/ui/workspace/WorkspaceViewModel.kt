package com.goplan.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val isLoading: Boolean = false,
    val workspaces: List<Workspace> = emptyList(),
    val activeWorkspace: Workspace? = null,
    val ideas: List<Idea> = emptyList(),
    val allIdeas: List<Idea> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val selectedStatuses: Set<IdeaStatus> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
    val selectedStatus: IdeaStatus?
        get() = if (selectedStatuses.size == 1) selectedStatuses.first() else null
}

class WorkspaceViewModel(
    private val workspaceRepository: WorkspaceRepository,
    private val preferencesManager: PreferencesManager,
    private val parser: TodoMarkdownParser = TodoMarkdownParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState(isLoading = true))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val tagRegex = Regex("""(?<=^|\s)#([a-zA-Z0-9_-]+)""")

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch(ioDispatcher) {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                var workspaces = workspaceRepository.getWorkspaces()
                val savedActiveId = preferencesManager.getActiveWorkspace().first()

                val targetWorkspace = when {
                    savedActiveId != null && workspaces.any { it.id == savedActiveId } -> {
                        workspaces.first { it.id == savedActiveId }
                    }
                    workspaces.isNotEmpty() -> {
                        workspaces.first()
                    }
                    else -> {
                        val created = workspaceRepository.createWorkspace("Default")
                        workspaces = workspaceRepository.getWorkspaces()
                        created
                    }
                }

                preferencesManager.saveActiveWorkspace(targetWorkspace.id)
                _uiState.update { it.copy(workspaces = workspaces) }
                loadWorkspaceData(targetWorkspace)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Error al cargar workspace") }
            }
        }
    }

    fun switchWorkspace(workspace: Workspace) {
        viewModelScope.launch(ioDispatcher) {
            try {
                preferencesManager.saveActiveWorkspace(workspace.id)
                _uiState.update { it.copy(selectedTags = emptySet(), errorMessage = null) }
                loadWorkspaceData(workspace)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al cambiar de workspace") }
            }
        }
    }

    fun createWorkspace(name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val created = workspaceRepository.createWorkspace(cleanName)
                val allWorkspaces = workspaceRepository.getWorkspaces()
                preferencesManager.saveActiveWorkspace(created.id)
                _uiState.update { it.copy(workspaces = allWorkspaces, selectedTags = emptySet(), errorMessage = null) }
                loadWorkspaceData(created)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al crear workspace") }
            }
        }
    }

    fun addIdea(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val tags = tagRegex.findAll(text).map { it.groupValues[1] }.toSet()
                val cleanTitle = text.replace(Regex("""(?<=^|\s)#[a-zA-Z0-9_-]+"""), "").trim().replace(Regex("""\s+"""), " ")
                val titleToUse = if (cleanTitle.isNotBlank()) cleanTitle else text.trim()

                val doc = workspaceRepository.getTodoDocument(activeWs)
                val updatedDoc = parser.addIdea(doc, titleToUse, tags)
                workspaceRepository.saveTodoDocument(activeWs, updatedDoc)
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al agregar idea") }
            }
        }
    }

    fun cycleStatus(idea: Idea) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val doc = workspaceRepository.getTodoDocument(activeWs)
                val currentStatus = doc.lines
                    .filterIsInstance<TodoLine.Task>()
                    .find { it.idea.id == idea.id }
                    ?.idea?.status ?: idea.status

                val nextStatus = when (currentStatus) {
                    IdeaStatus.PENDING -> IdeaStatus.IN_PROGRESS
                    IdeaStatus.IN_PROGRESS -> IdeaStatus.COMPLETED
                    IdeaStatus.COMPLETED -> IdeaStatus.PENDING
                    IdeaStatus.DISCARDED -> IdeaStatus.PENDING
                }
                val updatedDoc = parser.updateIdeaStatus(doc, idea.id, nextStatus)
                workspaceRepository.saveTodoDocument(activeWs, updatedDoc)
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al actualizar estado") }
            }
        }
    }

    fun discardIdea(idea: Idea) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val doc = workspaceRepository.getTodoDocument(activeWs)
                val updatedDoc = parser.updateIdeaStatus(doc, idea.id, IdeaStatus.DISCARDED)
                workspaceRepository.saveTodoDocument(activeWs, updatedDoc)
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al descartar idea") }
            }
        }
    }

    fun toggleStatusFilter(status: IdeaStatus) {
        _uiState.update { current ->
            val newStatuses = if (current.selectedStatuses.contains(status)) {
                current.selectedStatuses - status
            } else {
                current.selectedStatuses + status
            }
            val filtered = filterIdeas(current.allIdeas, newStatuses, current.selectedTags)
            current.copy(selectedStatuses = newStatuses, ideas = filtered)
        }
    }

    fun clearStatusFilter() {
        _uiState.update { current ->
            val newStatuses = emptySet<IdeaStatus>()
            val filtered = filterIdeas(current.allIdeas, newStatuses, current.selectedTags)
            current.copy(selectedStatuses = newStatuses, ideas = filtered)
        }
    }

    fun toggleTagFilter(tag: String) {
        _uiState.update { current ->
            val newTags = if (current.selectedTags.contains(tag)) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            val filtered = filterIdeas(current.allIdeas, current.selectedStatuses, newTags)
            current.copy(selectedTags = newTags, ideas = filtered)
        }
    }

    fun clearTagFilters() {
        _uiState.update { current ->
            val newTags = emptySet<String>()
            val filtered = filterIdeas(current.allIdeas, current.selectedStatuses, newTags)
            current.copy(selectedTags = newTags, ideas = filtered)
        }
    }

    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val allWorkspaces = workspaceRepository.getWorkspaces()
                _uiState.update { it.copy(workspaces = allWorkspaces, errorMessage = null) }
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al refrescar") }
            }
        }
    }

    private fun loadWorkspaceData(workspace: Workspace) {
        val doc = workspaceRepository.getTodoDocument(workspace)
        val taskIdeas = doc.lines.filterIsInstance<TodoLine.Task>().map { it.idea }
        val tags = taskIdeas.flatMap { it.tags }.distinct().sorted()

        _uiState.update { current ->
            val filtered = filterIdeas(taskIdeas, current.selectedStatuses, current.selectedTags)
            current.copy(
                activeWorkspace = workspace,
                allIdeas = taskIdeas,
                ideas = filtered,
                availableTags = tags,
                isLoading = false,
                errorMessage = null
            )
        }
    }

    private fun filterIdeas(
        allIdeas: List<Idea>,
        selectedStatuses: Set<IdeaStatus>,
        selectedTags: Set<String>
    ): List<Idea> {
        return allIdeas.filter { idea ->
            val statusMatches = if (selectedStatuses.isEmpty()) {
                idea.status != IdeaStatus.DISCARDED
            } else {
                selectedStatuses.contains(idea.status)
            }

            val tagMatches = if (selectedTags.isEmpty()) {
                true
            } else {
                selectedTags.all { selectedTag ->
                    idea.tags.any { it.equals(selectedTag, ignoreCase = true) }
                }
            }

            statusMatches && tagMatches
        }
    }

    companion object {
        fun provideFactory(
            repository: WorkspaceRepository,
            preferencesManager: PreferencesManager,
            parser: TodoMarkdownParser = TodoMarkdownParser(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkspaceViewModel(repository, preferencesManager, parser, ioDispatcher) as T
            }
        }
    }
}
