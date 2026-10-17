package com.goplan.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.model.WorkspaceSummary
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.goplan.app.data.sync.GoogleDriveAuthManager
import com.goplan.app.data.sync.GoogleDriveSyncEngine
import com.goplan.app.data.sync.model.SyncResult
import com.goplan.app.data.update.AppUpdateManager
import com.goplan.app.data.update.UpdateCheckResult
import com.goplan.app.data.update.UpdateDownloadState
import com.goplan.app.data.update.UpdateInfo
import com.goplan.app.domain.parser.GeminiPlanParser
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.server.KtorServerManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val ktorServerManager: KtorServerManager? = null,
    private val syncEngine: GoogleDriveSyncEngine? = null,
    private val authManager: GoogleDriveAuthManager? = null,
    private val appUpdateManager: AppUpdateManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState(isLoading = true))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    val isServerRunning: StateFlow<Boolean> = ktorServerManager?.isRunning ?: MutableStateFlow(false)
    val serverUrl: StateFlow<String?> = ktorServerManager?.serverUrl ?: MutableStateFlow(null)

    val signedInAccount: StateFlow<GoogleSignInAccount?> = authManager?.signedInAccount ?: MutableStateFlow(null)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)
    val lastSyncTimestamp: StateFlow<Long?> = _lastSyncTimestamp.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _syncSuccessMessage = MutableStateFlow<String?>(null)
    val syncSuccessMessage: StateFlow<String?> = _syncSuccessMessage.asStateFlow()

    private val _recoverableAuthIntent = MutableStateFlow<android.content.Intent?>(null)
    val recoverableAuthIntent: StateFlow<android.content.Intent?> = _recoverableAuthIntent.asStateFlow()

    fun clearRecoverableAuthIntent() {
        _recoverableAuthIntent.value = null
    }

    private val _showWorkspaceSheet = MutableStateFlow(false)
    val showWorkspaceSheet: StateFlow<Boolean> = _showWorkspaceSheet.asStateFlow()

    private val _workspaceSummaries = MutableStateFlow<List<WorkspaceSummary>>(emptyList())
    val workspaceSummaries: StateFlow<List<WorkspaceSummary>> = _workspaceSummaries.asStateFlow()

    fun openWorkspaceSheet() {
        _showWorkspaceSheet.value = true
        loadWorkspaceSummaries()
    }

    fun closeWorkspaceSheet() {
        _showWorkspaceSheet.value = false
    }

    private var loadSummariesJob: Job? = null

    fun loadWorkspaceSummaries() {
        loadSummariesJob?.cancel()
        loadSummariesJob = viewModelScope.launch(ioDispatcher) {
            val activeId = _uiState.value.activeWorkspace?.id
            val summaries = workspaceRepository.getAllWorkspaceSummaries(activeId)
            _workspaceSummaries.value = summaries
        }
    }

    // OTA & Update states
    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckResult: StateFlow<UpdateCheckResult?> = _updateCheckResult.asStateFlow()

    private val _updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadState> = _updateDownloadState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    fun toggleServer(enable: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            if (enable) {
                ktorServerManager?.start()
            } else {
                ktorServerManager?.stop()
            }
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        val manager = appUpdateManager ?: return
        viewModelScope.launch(ioDispatcher) {
            _isCheckingUpdate.value = true
            val result = manager.checkForUpdates()
            _updateCheckResult.value = result
            _isCheckingUpdate.value = false
            if (result is UpdateCheckResult.UpdateAvailable) {
                _showUpdateDialog.value = true
            } else if (!silent && result is UpdateCheckResult.UpToDate) {
                _showUpdateDialog.value = false
            }
        }
    }

    fun downloadAndInstallUpdate(updateInfo: UpdateInfo) {
        val manager = appUpdateManager ?: return
        viewModelScope.launch(ioDispatcher) {
            _updateDownloadState.value = UpdateDownloadState.Downloading(0f, 0L, updateInfo.apkSize)
            val result = manager.downloadUpdate(updateInfo) { progress, downloaded, total ->
                _updateDownloadState.value = UpdateDownloadState.Downloading(progress, downloaded, total)
            }
            if (result.isSuccess) {
                val apkFile = result.getOrThrow()
                _updateDownloadState.value = UpdateDownloadState.ReadyToInstall(apkFile.absolutePath)
                manager.promptInstall(apkFile)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Error al descargar la actualización"
                _updateDownloadState.value = UpdateDownloadState.Error(errorMsg)
            }
        }
    }

    fun installDownloadedApk(apkFile: File) {
        appUpdateManager?.promptInstall(apkFile)
    }

    fun openUpdateDialog() {
        if (_updateCheckResult.value is UpdateCheckResult.UpdateAvailable) {
            _showUpdateDialog.value = true
        } else {
            checkForUpdates(silent = false)
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
        if (_updateDownloadState.value is UpdateDownloadState.Error) {
            _updateDownloadState.value = UpdateDownloadState.Idle
        }
    }

    private val tagRegex = Regex("""(?<=^|\s)#([a-zA-Z0-9_-]+)""")

    init {
        loadInitialData()
        checkForUpdates(silent = true)
        viewModelScope.launch(ioDispatcher) {
            workspaceRepository.workspaceChangesFlow.collect { changedWorkspaceId ->
                val currentActive = _uiState.value.activeWorkspace
                if (currentActive != null && currentActive.id == changedWorkspaceId) {
                    loadWorkspace(currentActive)
                }
                loadWorkspaceSummaries()
            }
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState
                .map { it.activeWorkspace?.id to it.workspaces }
                .distinctUntilChanged()
                .collect {
                    loadWorkspaceSummaries()
                }
        }
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
                loadWorkspaceSummaries()
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

    fun createAndSwitchWorkspace(name: String): Result<Workspace> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("El nombre no puede estar vacío"))
        }
        val invalidCharsRegex = Regex("""[\\/:*?"<>|]""")
        if (invalidCharsRegex.containsMatchIn(cleanName)) {
            return Result.failure(IllegalArgumentException("El nombre contiene caracteres inválidos"))
        }
        val existing = workspaceRepository.getWorkspaces()
        if (existing.any { it.name.equals(cleanName, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Ya existe un espacio con ese nombre"))
        }

        return try {
            val created = workspaceRepository.createWorkspace(cleanName)
            val allWorkspaces = workspaceRepository.getWorkspaces()
            viewModelScope.launch(ioDispatcher) {
                preferencesManager.saveActiveWorkspace(created.id)
            }
            _uiState.update {
                it.copy(
                    workspaces = allWorkspaces,
                    selectedTags = emptySet(),
                    errorMessage = null
                )
            }
            loadWorkspaceData(created)
            loadWorkspaceSummaries()
            closeWorkspaceSheet()
            Result.success(created)
        } catch (e: Exception) {
            Result.failure(e)
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

    fun editIdea(idea: Idea, newTitle: String, newTags: Set<String>) {
        if (newTitle.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val doc = workspaceRepository.getTodoDocument(activeWs)
                val updatedDoc = parser.updateIdea(doc, idea.id, newTitle.trim(), newTags)
                workspaceRepository.saveTodoDocument(activeWs, updatedDoc)
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al editar idea") }
            }
        }
    }

    fun deleteIdeaPermanently(idea: Idea, deleteAssociatedPlans: Boolean = true) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                if (deleteAssociatedPlans) {
                    idea.plans.forEach { plan ->
                        try {
                            val planFile = File(activeWs.rootDir, plan.relativePath)
                            if (planFile.exists()) {
                                planFile.delete()
                            }
                        } catch (_: Exception) {}
                    }
                }
                val doc = workspaceRepository.getTodoDocument(activeWs)
                val updatedDoc = parser.deleteIdea(doc, idea.id)
                workspaceRepository.saveTodoDocument(activeWs, updatedDoc)
                loadWorkspaceData(activeWs)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al eliminar idea") }
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

    fun createPlanManually(idea: Idea, onCreated: (PlanLink) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val activeWs = _uiState.value.activeWorkspace ?: return@launch
                val geminiParser = GeminiPlanParser()
                val safeFileName = geminiParser.sanitizeFileName(idea.title)
                val initialContent = "# Plan: ${idea.title}\n\n## Descripción\n\n## Pasos de Implementación\n- [ ] "

                workspaceRepository.savePlanFile(activeWs, safeFileName, initialContent)

                val doc = workspaceRepository.getTodoDocument(activeWs)
                val planLink = PlanLink(label = safeFileName.removeSuffix(".md"), relativePath = "planes/$safeFileName")
                val updatedPlans = idea.plans + planLink
                val updatedDoc = parser.linkPlansToIdea(doc, idea.id, updatedPlans)
                val finalDoc = if (idea.status == IdeaStatus.PENDING) {
                    parser.updateIdeaStatus(updatedDoc, idea.id, IdeaStatus.IN_PROGRESS)
                } else {
                    updatedDoc
                }
                workspaceRepository.saveTodoDocument(activeWs, finalDoc)

                loadWorkspaceData(activeWs)

                withContext(Dispatchers.Main) {
                    onCreated(planLink)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Error al crear plan") }
            }
        }
    }

    fun syncAllWorkspaces() {
        syncWorkspaces(onlyActive = false)
    }

    fun syncActiveWorkspace() {
        syncWorkspaces(onlyActive = true)
    }

    fun syncWorkspaces(onlyActive: Boolean = false) {
        if (_isSyncing.value) return
        val activeWs = _uiState.value.activeWorkspace
        if (onlyActive && activeWs == null) {
            _syncError.value = "No hay workspace activo seleccionado"
            return
        }
        val engine = syncEngine
        if (engine == null) {
            _syncError.value = "Motor de sincronización no disponible"
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _isSyncing.value = true
            _syncError.value = null
            _syncSuccessMessage.value = null
            try {
                val result = if (onlyActive) {
                    engine.syncWorkspace(activeWs!!)
                } else {
                    engine.syncAllWorkspaces()
                }

                when (result) {
                    is SyncResult.Success -> {
                        _lastSyncTimestamp.value = System.currentTimeMillis()
                        val parts = mutableListOf<String>()
                        if (result.uploadedCount > 0) parts.add("${result.uploadedCount} subidos")
                        if (result.downloadedCount > 0) parts.add("${result.downloadedCount} descargados")
                        if (result.conflictsCount > 0) parts.add("${result.conflictsCount} conflictos")
                        val summary = if (parts.isNotEmpty()) " (${parts.joinToString(", ")})" else ""

                        val allWorkspaces = workspaceRepository.getWorkspaces()
                        _uiState.update { it.copy(workspaces = allWorkspaces) }

                        val prefix = if (onlyActive) {
                            "Sincronización completada para '${activeWs?.name}'"
                        } else {
                            "Sincronización completada (Global: ${allWorkspaces.size} espacios)"
                        }

                        _syncSuccessMessage.value = "$prefix$summary"
                        _syncError.value = null

                        val currentActive = _uiState.value.activeWorkspace
                        if (currentActive != null) {
                            loadWorkspaceData(currentActive)
                        }
                    }
                    is SyncResult.Error -> {
                        _syncError.value = result.message
                        _syncSuccessMessage.value = null
                        if (result.throwable is com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                            _recoverableAuthIntent.value = result.throwable.intent
                        }
                    }
                }
            } catch (e: Exception) {
                val detail = e.localizedMessage ?: e.message ?: e.cause?.message ?: e.javaClass.simpleName
                _syncError.value = "Error al sincronizar: $detail"
                _syncSuccessMessage.value = null
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun signOutGoogle() {
        authManager?.signOut()
        _syncError.value = null
        _syncSuccessMessage.value = null
    }

    fun clearSyncMessages() {
        _syncError.value = null
        _syncSuccessMessage.value = null
    }

    fun setSyncError(error: String?) {
        _syncError.value = error
    }

    fun getAppSha1Fingerprint(): String {
        return authManager?.getSha1Fingerprint() ?: "No disponible"
    }

    fun loadWorkspace(workspace: Workspace) {
        loadWorkspaceData(workspace)
    }

    private fun loadWorkspaceData(workspace: Workspace) {
        val syncStateFile = File(workspace.rootDir, ".sync_state.json")
        if (syncStateFile.exists()) {
            try {
                val text = syncStateFile.readText(Charsets.UTF_8)
                val millis = Regex(""""lastSyncMillis"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                if (millis != null && millis > 0) {
                    _lastSyncTimestamp.value = millis
                }
            } catch (_: Exception) {}
        } else {
            _lastSyncTimestamp.value = null
        }

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
            ktorServerManager: KtorServerManager? = null,
            syncEngine: GoogleDriveSyncEngine? = null,
            authManager: GoogleDriveAuthManager? = null,
            appUpdateManager: AppUpdateManager? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkspaceViewModel(
                    workspaceRepository = repository,
                    preferencesManager = preferencesManager,
                    parser = parser,
                    ktorServerManager = ktorServerManager,
                    syncEngine = syncEngine,
                    authManager = authManager,
                    appUpdateManager = appUpdateManager,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
