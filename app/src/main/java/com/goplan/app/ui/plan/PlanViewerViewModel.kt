package com.goplan.app.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.GeminiPlanParser
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlanViewerUiState(
    val workspace: Workspace? = null,
    val idea: Idea? = null,
    val plans: List<PlanLink> = emptyList(),
    val activePlanIndex: Int = 0,
    val planContent: String = "",
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val activePlan: PlanLink?
        get() = plans.getOrNull(activePlanIndex)
}

class PlanViewerViewModel(
    private val workspaceRepository: WorkspaceRepository,
    private val parser: TodoMarkdownParser = TodoMarkdownParser(),
    private val geminiParser: GeminiPlanParser = GeminiPlanParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanViewerUiState())
    val uiState: StateFlow<PlanViewerUiState> = _uiState.asStateFlow()

    fun load(workspaceId: String, ideaId: String, initialPlanPath: String?) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
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

            val todoDoc = workspaceRepository.getTodoDocument(workspace)
            val idea = todoDoc.lines
                .filterIsInstance<TodoLine.Task>()
                .find { it.idea.id == ideaId }
                ?.idea

            if (idea == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Idea no encontrada: $ideaId"
                    )
                }
                return@launch
            }

            val plans = if (idea.plans.isNotEmpty()) {
                idea.plans
            } else if (!initialPlanPath.isNullOrBlank()) {
                val fileName = File(initialPlanPath).name
                val label = fileName.removeSuffix(".md").ifEmpty { "Plan" }
                listOf(PlanLink(label = label, relativePath = initialPlanPath))
            } else {
                val cleanTitle = geminiParser.sanitizeFileName(idea.title)
                listOf(PlanLink(label = cleanTitle.removeSuffix(".md"), relativePath = "planes/$cleanTitle"))
            }

            val activeIndex = if (!initialPlanPath.isNullOrBlank()) {
                val targetName = File(initialPlanPath).name
                val idx = plans.indexOfFirst {
                    it.relativePath == initialPlanPath || File(it.relativePath).name == targetName
                }
                if (idx >= 0) idx else 0
            } else {
                0
            }

            val targetPlan = plans.getOrNull(activeIndex) ?: plans.first()
            val content = try {
                workspaceRepository.readPlanFile(workspace, targetPlan.relativePath)
            } catch (e: Exception) {
                "# Plan"
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    workspace = workspace,
                    idea = idea,
                    plans = plans,
                    activePlanIndex = activeIndex,
                    planContent = content,
                    errorMessage = null
                )
            }
        }
    }

    fun selectPlan(index: Int) {
        val currentState = _uiState.value
        if (index < 0 || index >= currentState.plans.size || index == currentState.activePlanIndex) {
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val workspace = currentState.workspace ?: return@launch
            val currentPlan = currentState.plans.getOrNull(currentState.activePlanIndex)
            if (currentPlan != null) {
                try {
                    workspaceRepository.savePlanFile(
                        workspace,
                        File(currentPlan.relativePath).name,
                        currentState.planContent
                    )
                } catch (e: Exception) {
                    // Ignora o mantiene contenido
                }
            }

            val targetPlan = currentState.plans[index]
            val content = try {
                workspaceRepository.readPlanFile(workspace, targetPlan.relativePath)
            } catch (e: Exception) {
                "# Plan"
            }

            _uiState.update {
                it.copy(
                    activePlanIndex = index,
                    planContent = content,
                    errorMessage = null
                )
            }
        }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    fun updateContent(newContent: String) {
        _uiState.update { it.copy(planContent = newContent) }
    }

    fun saveCurrentPlan() {
        val currentState = _uiState.value
        val workspace = currentState.workspace ?: return
        val currentPlan = currentState.plans.getOrNull(currentState.activePlanIndex) ?: return
        val fileName = File(currentPlan.relativePath).name

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                workspaceRepository.savePlanFile(workspace, fileName, currentState.planContent)
                _uiState.update { it.copy(isSaving = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Error al guardar el plan: ${e.message}"
                    )
                }
            }
        }
    }

    fun splitPlanFromCursor(cursorIndex: Int, rawNewFileName: String) {
        val currentState = _uiState.value
        val workspace = currentState.workspace ?: return
        val idea = currentState.idea ?: return

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val content = currentState.planContent
                val safeCursor = cursorIndex.coerceIn(0, content.length)
                val updatedCurrentContent = content.substring(0, safeCursor)
                val newPlanContent = content.substring(safeCursor)

                // 1. Guarda el plan actual con el contenido truncado hasta el cursor
                val currentPlan = currentState.plans.getOrNull(currentState.activePlanIndex)
                val currentFileName = if (currentPlan != null) File(currentPlan.relativePath).name else "plan.md"
                workspaceRepository.savePlanFile(workspace, currentFileName, updatedCurrentContent)

                // 2. Sanitiza el nuevo nombre a kebab-case terminado en .md
                val inputName = if (rawNewFileName.trim().isEmpty()) "sub-plan" else rawNewFileName.trim()
                val cleanFileName = geminiParser.sanitizeFileName(inputName)

                // 3. Guarda el nuevo archivo en planes/ con el contenido extraído
                workspaceRepository.savePlanFile(workspace, cleanFileName, newPlanContent)

                // 4. Deriva una etiqueta desde el nombre del archivo (removiendo .md)
                val label = cleanFileName.removeSuffix(".md")

                // 5. Agrega el nuevo enlace a los planes de la idea
                val newPlanLink = PlanLink(label = label, relativePath = "planes/$cleanFileName")
                val updatedPlans = currentState.plans + newPlanLink

                // 6. Vincula los planes a la idea en TODO.md y guarda
                val todoDoc = workspaceRepository.getTodoDocument(workspace)
                val updatedDoc = parser.linkPlansToIdea(todoDoc, idea.id, updatedPlans)
                workspaceRepository.saveTodoDocument(workspace, updatedDoc)

                val updatedIdea = updatedDoc.lines
                    .filterIsInstance<TodoLine.Task>()
                    .find { it.idea.id == idea.id }
                    ?.idea ?: idea.copy(plans = updatedPlans)

                val newActiveIndex = updatedPlans.size - 1

                _uiState.update {
                    it.copy(
                        idea = updatedIdea,
                        plans = updatedPlans,
                        activePlanIndex = newActiveIndex,
                        planContent = newPlanContent,
                        isSaving = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Error al dividir el plan: ${e.message}"
                    )
                }
            }
        }
    }

    fun deleteActivePlan(onDeleted: (hasRemainingPlans: Boolean) -> Unit) {
        val currentState = _uiState.value
        val workspace = currentState.workspace ?: return
        val idea = currentState.idea ?: return
        val currentPlan = currentState.activePlan ?: return

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                // Delete plan file from disk
                val planFile = File(workspace.rootDir, currentPlan.relativePath)
                if (planFile.exists()) {
                    planFile.delete()
                }

                val remainingPlans = currentState.plans.filter { it.relativePath != currentPlan.relativePath }
                val todoDoc = workspaceRepository.getTodoDocument(workspace)
                var updatedDoc = parser.linkPlansToIdea(todoDoc, idea.id, remainingPlans)

                if (remainingPlans.isEmpty() && idea.status == IdeaStatus.IN_PROGRESS) {
                    updatedDoc = parser.updateIdeaStatus(updatedDoc, idea.id, IdeaStatus.PENDING)
                }

                workspaceRepository.saveTodoDocument(workspace, updatedDoc)

                val updatedIdea = updatedDoc.lines
                    .filterIsInstance<TodoLine.Task>()
                    .find { it.idea.id == idea.id }
                    ?.idea ?: idea.copy(plans = remainingPlans)

                if (remainingPlans.isNotEmpty()) {
                    val nextIndex = 0.coerceAtMost(remainingPlans.size - 1)
                    val nextPlan = remainingPlans[nextIndex]
                    val nextContent = try {
                        workspaceRepository.readPlanFile(workspace, nextPlan.relativePath)
                    } catch (_: Exception) {
                        "# Plan"
                    }

                    _uiState.update {
                        it.copy(
                            idea = updatedIdea,
                            plans = remainingPlans,
                            activePlanIndex = nextIndex,
                            planContent = nextContent,
                            isSaving = false,
                            errorMessage = null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            idea = updatedIdea,
                            plans = emptyList(),
                            activePlanIndex = 0,
                            planContent = "",
                            isSaving = false,
                            errorMessage = null
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    onDeleted(remainingPlans.isNotEmpty())
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Error al eliminar plan: ${e.message}"
                    )
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: WorkspaceRepository,
            parser: TodoMarkdownParser = TodoMarkdownParser(),
            geminiParser: GeminiPlanParser = GeminiPlanParser(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlanViewerViewModel(repository, parser, geminiParser, ioDispatcher) as T
            }
        }
    }
}
