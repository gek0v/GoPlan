package com.goplan.app.server

import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.update.AppUpdateManager
import com.goplan.app.domain.export.AgentExportManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.BindException
import java.net.ServerSocket

class KtorServerManager(
    val repository: WorkspaceRepository,
    val preferencesManager: PreferencesManager,
    val agentExportManager: AgentExportManager? = null,
    val appUpdateManager: AppUpdateManager? = null,
    private val ipProvider: () -> String? = { NetworkUtils.getLocalIpv4Address() }
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private var serverEngine: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start(port: Int = 8080): Result<String> {
        if (_isRunning.value) {
            return Result.success(_serverUrl.value ?: "http://localhost:$port")
        }

        val ip = ipProvider() ?: "127.0.0.1"
        val portsToTry = listOf(port, port + 1)
        var lastException: Throwable? = null

        for (targetPort in portsToTry) {
            if (!isPortAvailable(targetPort)) {
                lastException = BindException("Address already in use: $targetPort")
                if (targetPort == port) {
                    continue
                } else {
                    break
                }
            }

            var engine: EmbeddedServer<*, *>? = null
            try {
                engine = embeddedServer(CIO, port = targetPort, host = "0.0.0.0") {
                    configureServer(
                        repository = repository,
                        preferencesManager = preferencesManager,
                        agentExportManager = agentExportManager,
                        appUpdateManager = appUpdateManager,
                        getIpAddress = { ipProvider() ?: "127.0.0.1" },
                        port = targetPort
                    )
                }
                engine.start(wait = false)
                serverEngine = engine
                _isRunning.value = true
                _serverUrl.value = "http://$ip:$targetPort"
                return Result.success(_serverUrl.value!!)
            } catch (e: Throwable) {
                lastException = e
                try {
                    engine?.stop(gracePeriodMillis = 50L, timeoutMillis = 100L)
                } catch (_: Throwable) {}

                if (targetPort == port && isPortOccupied(e)) {
                    continue
                } else {
                    break
                }
            }
        }

        _isRunning.value = false
        _serverUrl.value = null
        serverEngine = null
        return Result.failure(lastException ?: RuntimeException("Server failed to start"))
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.value && serverEngine == null) {
            return
        }
        try {
            serverEngine?.stop(gracePeriodMillis = 200L, timeoutMillis = 1000L)
        } finally {
            serverEngine = null
            _isRunning.value = false
            _serverUrl.value = null
        }
    }

    private fun isPortOccupied(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is BindException) return true
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("address already in use") ||
                msg.contains("bind failed") ||
                msg.contains("failed to bind") ||
                msg.contains("already in use") ||
                msg.contains("bind")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).apply {
                reuseAddress = true
            }.close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
