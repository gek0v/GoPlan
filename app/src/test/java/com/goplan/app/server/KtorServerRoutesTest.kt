package com.goplan.app.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.export.AgentExportManager
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.server.dto.ApiResponse
import com.goplan.app.server.dto.ContextDto
import com.goplan.app.server.dto.CreateIdeaRequest
import com.goplan.app.server.dto.CreateWorkspaceRequest
import com.goplan.app.server.dto.IdeaDto
import com.goplan.app.server.dto.IngestPlanRequest
import com.goplan.app.server.dto.PlanFileDto
import com.goplan.app.server.dto.ServerStatusResponse
import com.goplan.app.server.dto.TodoResponseDto
import com.goplan.app.server.dto.UpdateIdeaRequest
import com.goplan.app.server.dto.WorkspaceDto
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class KtorServerRoutesTest {

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

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("workspaces")
        fileManager = WorkspaceFileManager(baseDir)
        todoParser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, todoParser)

        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_server.preferences_pb") }
        )
        preferencesManager = PreferencesManager(dataStore)
        agentExportManager = AgentExportManager(repository)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun ApplicationTestBuilder.setupServer() {
        application {
            configureServer(
                repository = repository,
                preferencesManager = preferencesManager,
                agentExportManager = agentExportManager,
                getIpAddress = { "192.168.1.150" },
                port = 8080
            )
        }
    }

    @Test
    fun testGetStatusReturns200AndJson() = testApplication {
        preferencesManager.saveActiveWorkspace("TestWorkspace")
        setupServer()

        val response = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val statusDto = json.decodeFromString<ServerStatusResponse>(body)

        assertEquals("online", statusDto.status)
        assertEquals("GoPlan", statusDto.app)
        assertEquals("1.0.0", statusDto.version)
        assertEquals("TestWorkspace", statusDto.activeWorkspace)
        assertEquals("192.168.1.150", statusDto.ip)
        assertEquals(8080, statusDto.port)
    }

    @Test
    fun testWorkspaceAndTodoLifecycle() = testApplication {
        setupServer()

        // 1. Create workspace
        val createWsResponse = client.post("/api/workspaces") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(CreateWorkspaceRequest(name = "Alpha")))
        }
        assertEquals(HttpStatusCode.Created, createWsResponse.status)
        val createdWsDto = json.decodeFromString<WorkspaceDto>(createWsResponse.bodyAsText())
        assertEquals("Alpha", createdWsDto.id)
        assertEquals("Alpha", createdWsDto.name)
        assertEquals(0, createdWsDto.stats.total)

        // 2. List workspaces
        val listWsResponse = client.get("/api/workspaces")
        assertEquals(HttpStatusCode.OK, listWsResponse.status)
        val workspaces = json.decodeFromString<List<WorkspaceDto>>(listWsResponse.bodyAsText())
        assertEquals(1, workspaces.size)
        assertEquals("Alpha", workspaces[0].id)

        // 3. Get context JSON
        val getContextResponse = client.get("/api/workspaces/Alpha/context")
        assertEquals(HttpStatusCode.OK, getContextResponse.status)
        val contextDto = json.decodeFromString<ContextDto>(getContextResponse.bodyAsText())
        assertEquals("Alpha", contextDto.workspaceId)
        assertTrue(contextDto.content.contains("Stack Tecnológico"))

        // 4. Update context
        val updateContextResponse = client.put("/api/workspaces/Alpha/context") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(ContextDto("Alpha", "# Updated Project Context")))
        }
        assertEquals(HttpStatusCode.OK, updateContextResponse.status)

        // 5. Get context raw
        val rawContextResponse = client.get("/api/workspaces/Alpha/context?format=raw")
        assertEquals(HttpStatusCode.OK, rawContextResponse.status)
        assertTrue(rawContextResponse.bodyAsText().contains("# Updated Project Context"))

        // 6. Add Idea
        val addIdeaResponse = client.post("/api/workspaces/Alpha/ideas") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(CreateIdeaRequest(text = "Build Ktor Server #backend #v2")))
        }
        assertEquals(HttpStatusCode.Created, addIdeaResponse.status)
        val ideaDto = json.decodeFromString<IdeaDto>(addIdeaResponse.bodyAsText())
        assertEquals("Build Ktor Server", ideaDto.title)
        assertEquals("PENDING", ideaDto.status)
        assertTrue(ideaDto.tags.contains("backend"))
        assertTrue(ideaDto.tags.contains("v2"))

        // 7. Get TODO
        val getTodoResponse = client.get("/api/workspaces/Alpha/todo")
        assertEquals(HttpStatusCode.OK, getTodoResponse.status)
        val todoResponse = json.decodeFromString<TodoResponseDto>(getTodoResponse.bodyAsText())
        assertEquals(1, todoResponse.ideas.size)
        assertEquals("Build Ktor Server", todoResponse.ideas[0].title)

        // Verify stats before patch
        val wsListBeforePatch = json.decodeFromString<List<WorkspaceDto>>(client.get("/api/workspaces").bodyAsText())
        assertEquals(1, wsListBeforePatch[0].stats.total)
        assertEquals(1, wsListBeforePatch[0].stats.pending)
        assertEquals(0, wsListBeforePatch[0].stats.completed)

        // 8. Patch Idea to COMPLETED
        val patchIdeaResponse = client.patch("/api/workspaces/Alpha/ideas/${ideaDto.id}") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(UpdateIdeaRequest(status = "COMPLETED")))
        }
        assertEquals(HttpStatusCode.OK, patchIdeaResponse.status)
        val patchResult = json.decodeFromString<ApiResponse>(patchIdeaResponse.bodyAsText())
        assertTrue(patchResult.success)
        assertEquals("COMPLETED", patchResult.idea?.status)

        // Verify stats after patch
        val wsListAfterPatch = json.decodeFromString<List<WorkspaceDto>>(client.get("/api/workspaces").bodyAsText())
        assertEquals(1, wsListAfterPatch[0].stats.total)
        assertEquals(0, wsListAfterPatch[0].stats.pending)
        assertEquals(1, wsListAfterPatch[0].stats.completed)

        // 9. Delete Idea
        val deleteIdeaResponse = client.delete("/api/workspaces/Alpha/ideas/${ideaDto.id}")
        assertEquals(HttpStatusCode.OK, deleteIdeaResponse.status)
        val deleteResult = json.decodeFromString<ApiResponse>(deleteIdeaResponse.bodyAsText())
        assertTrue(deleteResult.success)

        val todoAfterDelete = json.decodeFromString<TodoResponseDto>(client.get("/api/workspaces/Alpha/todo").bodyAsText())
        assertEquals(0, todoAfterDelete.ideas.size)
    }

    @Test
    fun testIngestPlanAndPathTraversalDefense() = testApplication {
        setupServer()
        repository.createWorkspace("Alpha")
        val ws = repository.getWorkspaces().first { it.id == "Alpha" }
        val doc = repository.getTodoDocument(ws)
        val updatedDoc = todoParser.addIdea(doc, "Feature Authentication", emptySet())
        repository.saveTodoDocument(ws, updatedDoc)
        val ideaId = updatedDoc.lines.filterIsInstance<com.goplan.app.domain.model.TodoLine.Task>().first().idea.id

        // 1. Ingest plan with files
        val ingestResponse = client.post("/api/workspaces/Alpha/plans") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                json.encodeToString(
                    IngestPlanRequest(
                        ideaId = ideaId,
                        files = listOf(
                            PlanFileDto(fileName = "auth-spec.md", content = "# Auth Spec\nOAuth details here.")
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, ingestResponse.status)
        val ingestResult = json.decodeFromString<ApiResponse>(ingestResponse.bodyAsText())
        assertTrue(ingestResult.success)
        assertEquals(listOf("auth-spec.md"), ingestResult.createdFiles)

        // Verify idea transitioned to IN_PROGRESS and linked
        val todoResponse = json.decodeFromString<TodoResponseDto>(client.get("/api/workspaces/Alpha/todo").bodyAsText())
        val linkedIdea = todoResponse.ideas.first { it.id == ideaId }
        assertEquals("IN_PROGRESS", linkedIdea.status)
        assertEquals(1, linkedIdea.plans.size)
        assertEquals("planes/auth-spec.md", linkedIdea.plans[0].relativePath)

        // 2. GET valid plan file
        val getPlanResponse = client.get("/api/workspaces/Alpha/plans/auth-spec.md")
        assertEquals(HttpStatusCode.OK, getPlanResponse.status)
        assertTrue(getPlanResponse.bodyAsText().contains("# Auth Spec"))

        // 3. Path traversal defense
        val traversalResponse1 = client.get("/api/workspaces/Alpha/plans/..%2F..%2Fsecret.txt")
        assertEquals(HttpStatusCode.BadRequest, traversalResponse1.status)

        val traversalResponse2 = client.get("/api/workspaces/Alpha/plans/invalid-file.txt")
        assertEquals(HttpStatusCode.BadRequest, traversalResponse2.status)

        // 4. Ingest plan with rawText
        val rawIngestResponse = client.post("/api/workspaces/Alpha/plans") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                json.encodeToString(
                    IngestPlanRequest(
                        ideaId = ideaId,
                        rawText = """
                            === ARCHIVO: session-cache.md ===
                            # Session Cache
                            Redis cache implementation.
                        """.trimIndent()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, rawIngestResponse.status)
        val rawIngestResult = json.decodeFromString<ApiResponse>(rawIngestResponse.bodyAsText())
        assertTrue(rawIngestResult.success)
        assertEquals(listOf("session-cache.md"), rawIngestResult.createdFiles)

        val getSessionCacheResponse = client.get("/api/workspaces/Alpha/plans/session-cache.md")
        assertEquals(HttpStatusCode.OK, getSessionCacheResponse.status)
        assertTrue(getSessionCacheResponse.bodyAsText().contains("Redis cache implementation"))
    }

    @Test
    fun testExportZipAndMarkdown() = testApplication {
        setupServer()
        repository.createWorkspace("Alpha")
        val ws = repository.getWorkspaces().first { it.id == "Alpha" }
        repository.saveContextContent(ws, "# Alpha Architecture\nOverview...")
        val doc = repository.getTodoDocument(ws)
        val updatedDoc = todoParser.addIdea(doc, "Task 1", emptySet())
        repository.saveTodoDocument(ws, updatedDoc)

        // 1. Export as ZIP
        val zipResponse = client.get("/api/workspaces/Alpha/export?type=zip")
        assertEquals(HttpStatusCode.OK, zipResponse.status)
        assertEquals("application/zip", zipResponse.headers[HttpHeaders.ContentType])
        assertEquals("attachment; filename=\"Alpha-export.zip\"", zipResponse.headers[HttpHeaders.ContentDisposition])
        val zipBytes = zipResponse.readRawBytes()
        assertTrue(zipBytes.isNotEmpty())

        // 2. Export as Markdown
        val mdResponse = client.get("/api/workspaces/Alpha/export?type=md")
        assertEquals(HttpStatusCode.OK, mdResponse.status)
        assertTrue(mdResponse.headers[HttpHeaders.ContentType]?.contains("text/markdown") == true)
        val mdText = mdResponse.bodyAsText()
        assertTrue(mdText.contains("# CONTEXTO DEL PROYECTO"))
        assertTrue(mdText.contains("Alpha Architecture"))

        // 3. Invalid export type returns 400
        val invalidExportResponse = client.get("/api/workspaces/Alpha/export?type=invalid")
        assertEquals(HttpStatusCode.BadRequest, invalidExportResponse.status)
    }

    @Test
    fun testNotFoundReturns404() = testApplication {
        setupServer()

        val response = client.get("/api/workspaces/NonExistentWorkspace/todo")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val errorDto = json.decodeFromString<ApiResponse>(response.bodyAsText())
        assertFalse(errorDto.success)
        assertNotNull(errorDto.message)
    }

    @Test
    fun testWebDashboardServedAtRootAndIndexHtml() = testApplication {
        setupServer()

        val rootResponse = client.get("/")
        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertTrue(rootResponse.headers[HttpHeaders.ContentType]?.contains("text/html") == true)
        val rootHtml = rootResponse.bodyAsText()
        assertTrue(rootHtml.contains("GoPlan - Panel de Control Local"))
        assertTrue(rootHtml.contains("Captura Rápida desde el PC"))

        val indexResponse = client.get("/index.html")
        assertEquals(HttpStatusCode.OK, indexResponse.status)
        assertTrue(indexResponse.headers[HttpHeaders.ContentType]?.contains("text/html") == true)
        val indexHtml = indexResponse.bodyAsText()
        assertTrue(indexHtml.contains("GoPlan - Panel de Control Local"))
    }
}
