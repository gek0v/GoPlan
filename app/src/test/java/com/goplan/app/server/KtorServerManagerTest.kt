package com.goplan.app.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.export.AgentExportManager
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class)
class KtorServerManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var baseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var todoParser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var agentExportManager: AgentExportManager
    private lateinit var serverManager: KtorServerManager

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        todoParser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, todoParser)

        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_server_manager.preferences_pb") }
        )
        preferencesManager = PreferencesManager(dataStore)
        agentExportManager = AgentExportManager(repository)

        serverManager = KtorServerManager(
            repository = repository,
            preferencesManager = preferencesManager,
            agentExportManager = agentExportManager,
            ipProvider = { "127.0.0.1" }
        )
    }

    @After
    fun tearDown() {
        serverManager.stop()
        testScope.cancel()
    }

    @Test
    fun testStartAndStop_lifecycleTransitions() {
        assertFalse(serverManager.isRunning.value)
        assertNull(serverManager.serverUrl.value)

        val port = 18080
        val result = serverManager.start(port = port)
        assertTrue("Start should succeed", result.isSuccess)
        val url = result.getOrNull()
        assertNotNull(url)
        assertEquals("http://127.0.0.1:$port", url)
        assertTrue(serverManager.isRunning.value)
        assertEquals("http://127.0.0.1:$port", serverManager.serverUrl.value)

        serverManager.stop()
        assertFalse(serverManager.isRunning.value)
        assertNull(serverManager.serverUrl.value)
    }

    @Test
    fun testStart_whenAlreadyRunning_returnsSameUrl() {
        val port = 18082
        val firstResult = serverManager.start(port = port)
        assertTrue(firstResult.isSuccess)
        val firstUrl = firstResult.getOrNull()

        val secondResult = serverManager.start(port = port)
        assertTrue(secondResult.isSuccess)
        val secondUrl = secondResult.getOrNull()

        assertEquals(firstUrl, secondUrl)
        assertTrue(serverManager.isRunning.value)
    }

    @Test
    fun testStop_whenNotRunning_isSafeNoOp() {
        assertFalse(serverManager.isRunning.value)
        serverManager.stop()
        assertFalse(serverManager.isRunning.value)
        assertNull(serverManager.serverUrl.value)
    }

    @Test
    fun testStart_portOccupied_fallsBackToNextPort() {
        val primaryPort = 18084
        val fallbackPort = primaryPort + 1

        val occupyingSocket = ServerSocket(primaryPort)
        try {
            val result = serverManager.start(port = primaryPort)
            assertTrue("Server should start on fallback port", result.isSuccess)
            assertEquals("http://127.0.0.1:$fallbackPort", result.getOrNull())
            assertTrue(serverManager.isRunning.value)
            assertEquals("http://127.0.0.1:$fallbackPort", serverManager.serverUrl.value)
        } finally {
            occupyingSocket.close()
        }
    }

    @Test
    fun testStart_bothPortsOccupied_failsGracefully() {
        val primaryPort = 18086
        val fallbackPort = primaryPort + 1

        val socket1 = ServerSocket(primaryPort)
        val socket2 = ServerSocket(fallbackPort)
        try {
            val result = serverManager.start(port = primaryPort)
            assertTrue("Server should fail when both ports are occupied", result.isFailure)
            assertFalse(serverManager.isRunning.value)
            assertNull(serverManager.serverUrl.value)
        } finally {
            socket1.close()
            socket2.close()
        }
    }

    @Test
    fun testServerRunning_servesStatusEndpoint() {
        val port = 18088
        val result = serverManager.start(port = port)
        assertTrue(result.isSuccess)

        val url = URL("http://127.0.0.1:$port/api/status")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        try {
            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            assertEquals(200, responseCode)
            assertTrue("Response should contain GoPlan app status. Got: $responseBody", responseBody.contains(Regex("\"app\"\\s*:\\s*\"GoPlan\"")))
            assertTrue("Response should contain port. Got: $responseBody", responseBody.contains(Regex("\"port\"\\s*:\\s*$port")))
        } finally {
            connection.disconnect()
        }
    }
}
