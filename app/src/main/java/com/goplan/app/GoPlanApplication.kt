package com.goplan.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.export.AgentExportManager
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.server.KtorServerManager

private val Context.dataStore by preferencesDataStore(name = "goplan_preferences")

class GoPlanApplication : Application() {

    val workspaceFileManager: WorkspaceFileManager by lazy {
        val baseDir = getExternalFilesDir(null)?.resolve("GoPlan") ?: filesDir.resolve("GoPlan")
        WorkspaceFileManager(baseDir)
    }

    val todoMarkdownParser: TodoMarkdownParser by lazy {
        TodoMarkdownParser()
    }

    val workspaceRepository: WorkspaceRepository by lazy {
        WorkspaceRepository(workspaceFileManager, todoMarkdownParser)
    }

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(dataStore)
    }

    val agentExportManager: AgentExportManager by lazy {
        AgentExportManager(workspaceRepository)
    }

    val ktorServerManager: KtorServerManager by lazy {
        KtorServerManager(workspaceRepository, preferencesManager, agentExportManager)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GoPlanApplication
            private set
    }
}
