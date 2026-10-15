package com.goplan.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goplan.app.data.preferences.RecentExpansion
import com.goplan.app.domain.usecase.PrepareGeminiPromptUseCase
import com.goplan.app.ui.context.ContextEditorScreen
import com.goplan.app.ui.context.ContextEditorViewModel
import com.goplan.app.ui.intent.ImportPlanBottomSheet
import com.goplan.app.ui.intent.IntentHandler
import com.goplan.app.ui.intent.executeImportContext
import com.goplan.app.ui.intent.executeImportPlans
import com.goplan.app.ui.plan.PlanViewerScreen
import com.goplan.app.ui.plan.PlanViewerViewModel
import com.goplan.app.ui.theme.GoPlanTheme
import com.goplan.app.ui.workspace.WorkspaceScreen
import com.goplan.app.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val app by lazy { application as GoPlanApplication }
    private val prepareGeminiPromptUseCase by lazy { PrepareGeminiPromptUseCase() }

    private var incomingSharedText by mutableStateOf<String?>(null)
    private var recentExpansion by mutableStateOf<RecentExpansion?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            GoPlanTheme {
                MainContent()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val extractedText = IntentHandler.extractIncomingText(this, intent)
        if (!extractedText.isNullOrBlank()) {
            intent?.action = null
            setIntent(Intent(Intent.ACTION_MAIN))
            lifecycleScope.launch {
                val expansion = app.preferencesManager.getRecentExpansion(maxAgeMinutes = 30)
                recentExpansion = expansion
                incomingSharedText = extractedText
            }
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun MainContent() {
        val navController = rememberNavController()

        val workspaceViewModel: WorkspaceViewModel = viewModel(
            factory = WorkspaceViewModel.provideFactory(
                repository = app.workspaceRepository,
                preferencesManager = app.preferencesManager,
                parser = app.todoMarkdownParser
            )
        )
        val workspaceUiState by workspaceViewModel.uiState.collectAsState()

        var showExportDialog by remember { mutableStateOf(false) }

        if (showExportDialog) {
            val activeWs = workspaceUiState.activeWorkspace
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Exportar para agentes") },
                text = {
                    Text("Selecciona el formato de exportación para ${activeWs?.name ?: "el workspace"}:")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            if (activeWs != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                                    val zipFile = File(exportDir, "${activeWs.name}_export.zip")
                                    app.agentExportManager.createZipBundle(activeWs, workspaceUiState.allIdeas, zipFile)
                                    withContext(Dispatchers.Main) {
                                        IntentHandler.shareExportFile(this@MainActivity, zipFile, "application/zip")
                                    }
                                }
                            }
                        }
                    ) {
                        Text("ZIP (.zip)")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            if (activeWs != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                                    val mdFile = File(exportDir, "${activeWs.name}_bundle.md")
                                    val markdown = app.agentExportManager.createUnifiedMarkdown(activeWs, workspaceUiState.allIdeas)
                                    mdFile.writeText(markdown, Charsets.UTF_8)
                                    withContext(Dispatchers.Main) {
                                        IntentHandler.shareExportFile(this@MainActivity, mdFile, "text/markdown")
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Markdown (.md)")
                    }
                }
            )
        }

        NavHost(
            navController = navController,
            startDestination = "workspace",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("workspace") {
                WorkspaceScreen(
                    viewModel = workspaceViewModel,
                    onNavigateToContext = { wsId ->
                        navController.navigate("context/$wsId")
                    },
                    onNavigateToPlan = { wsId, ideaId, planPath ->
                        val encodedPath = Uri.encode(planPath)
                        navController.navigate("plan/$wsId/$ideaId?planPath=$encodedPath")
                    },
                    onExpandWithGemini = { idea ->
                        val activeWs = workspaceUiState.activeWorkspace
                        if (activeWs != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val contextContent = app.workspaceRepository.getContextContent(activeWs)
                                val prompt = prepareGeminiPromptUseCase(contextContent, idea)
                                app.preferencesManager.recordRecentExpansion(activeWs.id, idea.id)
                                withContext(Dispatchers.Main) {
                                    IntentHandler.sharePromptToGemini(this@MainActivity, prompt)
                                }
                            }
                        }
                    },
                    onExport = {
                        showExportDialog = true
                    }
                )
            }

            composable(
                route = "context/{workspaceId}",
                arguments = listOf(
                    navArgument("workspaceId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val workspaceId = backStackEntry.arguments?.getString("workspaceId") ?: ""
                val contextViewModel: ContextEditorViewModel = viewModel(
                    factory = ContextEditorViewModel.provideFactory(
                        repository = app.workspaceRepository
                    )
                )

                ContextEditorScreen(
                    viewModel = contextViewModel,
                    workspaceId = workspaceId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "plan/{workspaceId}/{ideaId}?planPath={planPath}",
                arguments = listOf(
                    navArgument("workspaceId") { type = NavType.StringType },
                    navArgument("ideaId") { type = NavType.StringType },
                    navArgument("planPath") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val workspaceId = backStackEntry.arguments?.getString("workspaceId") ?: ""
                val ideaId = backStackEntry.arguments?.getString("ideaId") ?: ""
                val rawPlanPath = backStackEntry.arguments?.getString("planPath")
                val decodedPlanPath = rawPlanPath?.let { Uri.decode(it) }

                val planViewModel: PlanViewerViewModel = viewModel(
                    factory = PlanViewerViewModel.provideFactory(
                        repository = app.workspaceRepository,
                        parser = app.todoMarkdownParser
                    )
                )

                PlanViewerScreen(
                    viewModel = planViewModel,
                    workspaceId = workspaceId,
                    ideaId = ideaId,
                    initialPlanPath = decodedPlanPath,
                    onNavigateBack = {
                        navController.popBackStack()
                        workspaceViewModel.refresh()
                    }
                )
            }
        }

        // Host ImportPlanBottomSheet whenever incoming shared text is present
        incomingSharedText?.let { incomingText ->
            val workspaces = workspaceUiState.workspaces.ifEmpty {
                app.workspaceRepository.getWorkspaces()
            }
            ImportPlanBottomSheet(
                incomingText = incomingText,
                workspaces = workspaces,
                recentExpansion = recentExpansion,
                ideasProvider = { ws ->
                    withContext(Dispatchers.IO) {
                        try {
                            app.workspaceRepository.getTodoDocument(ws).lines
                                .filterIsInstance<com.goplan.app.domain.model.TodoLine.Task>()
                                .map { it.idea }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                },
                onDismiss = {
                    incomingSharedText = null
                },
                onConfirmImportPlans = { ws, idea, plans ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        executeImportPlans(
                            workspaceRepository = app.workspaceRepository,
                            preferencesManager = app.preferencesManager,
                            todoParser = app.todoMarkdownParser,
                            workspace = ws,
                            idea = idea,
                            plans = plans
                        )
                        withContext(Dispatchers.Main) {
                            incomingSharedText = null
                            workspaceViewModel.refresh()
                        }
                    }
                },
                onConfirmImportContext = { ws, contextContent ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        executeImportContext(
                            workspaceRepository = app.workspaceRepository,
                            preferencesManager = app.preferencesManager,
                            workspace = ws,
                            contextContent = contextContent
                        )
                        withContext(Dispatchers.Main) {
                            incomingSharedText = null
                            workspaceViewModel.refresh()
                        }
                    }
                }
            )
        }
    }
}
