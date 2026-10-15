package com.goplan.app.data.repository

import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceRepositoryTest {

    private lateinit var tempBaseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("goplan_test").toFile()
        fileManager = WorkspaceFileManager(tempBaseDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, parser)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testCreateWorkspaceInitializesStructure() {
        val ws = repository.createWorkspace("Alpha")

        assertEquals("Alpha", ws.id)
        assertEquals("Alpha", ws.name)
        assertTrue(ws.rootDir.exists())
        assertTrue(ws.rootDir.isDirectory)

        val contextFile = File(ws.rootDir, "CONTEXT.md")
        assertTrue(contextFile.exists())
        val contextContent = contextFile.readText()
        assertTrue(contextContent.contains("# Contexto"))

        val todoFile = File(ws.rootDir, "TODO.md")
        assertTrue(todoFile.exists())
        val todoContent = todoFile.readText()
        assertTrue(todoContent.contains("# Tareas"))

        val planesDir = File(ws.rootDir, "planes")
        assertTrue(planesDir.exists())
        assertTrue(planesDir.isDirectory)

        val allWorkspaces = repository.getWorkspaces()
        assertEquals(1, allWorkspaces.size)
        assertEquals(ws.id, allWorkspaces[0].id)
    }

    @Test
    fun testSaveAndGetTodoDocumentWithAtomicWrites() {
        val ws = repository.createWorkspace("Alpha")

        val initialDoc = repository.getTodoDocument(ws)
        assertNotNull(initialDoc)

        val updatedDoc = parser.addIdea(initialDoc, "Nueva idea de prueba", setOf("mobile", "kotlin"))
        repository.saveTodoDocument(ws, updatedDoc)

        // Verify .tmp file does not linger
        val lingeringTmpFiles = ws.rootDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("Temporary .tmp file should not remain after atomic move", lingeringTmpFiles.isEmpty())

        // Verify content on disk
        val rawTodo = File(ws.rootDir, "TODO.md").readText()
        assertTrue(rawTodo.contains("Nueva idea de prueba"))
        assertTrue(rawTodo.contains("#mobile"))

        // Verify reloading parsed document
        val reloadedDoc = repository.getTodoDocument(ws)
        val hasIdea = reloadedDoc.lines.any {
            it is TodoLine.Task && it.idea.title == "Nueva idea de prueba" && it.idea.tags.contains("mobile")
        }
        assertTrue(hasIdea)
    }

    @Test
    fun testSaveAndReadPlanFiles() {
        val ws = repository.createWorkspace("Alpha")

        val planContent = "# Plan OAuth\nEspecificación técnica para OAuth Google"
        val savedFile = repository.savePlanFile(ws, "oauth-google.md", planContent)

        assertTrue(savedFile.exists())
        val parent = savedFile.parentFile
        assertNotNull(parent)
        assertEquals("planes", parent!!.name)
        assertEquals("oauth-google.md", savedFile.name)

        // Verify no .tmp file left behind in planes directory
        val lingeringTmpFiles = parent.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("No temporary .tmp file should linger in planes directory", lingeringTmpFiles.isEmpty())

        // Read using "planes/oauth-google.md"
        val readWithSubdir = repository.readPlanFile(ws, "planes/oauth-google.md")
        assertEquals(planContent, readWithSubdir)

        // Read using just "oauth-google.md"
        val readDirect = repository.readPlanFile(ws, "oauth-google.md")
        assertEquals(planContent, readDirect)
    }

    @Test
    fun testSaveAndGetContextContent() {
        val ws = repository.createWorkspace("Alpha")

        val initialContext = repository.getContextContent(ws)
        assertTrue(initialContext.isNotBlank())

        val newContext = "# Custom Stack\n- Kotlin\n- Jetpack Compose"
        repository.saveContextContent(ws, newContext)

        val updatedContext = repository.getContextContent(ws)
        assertEquals(newContext, updatedContext)
        assertEquals(newContext, File(ws.rootDir, "CONTEXT.md").readText())
    }

    @Test
    fun testGetWorkspacesListsMultipleAlphabetically() {
        repository.createWorkspace("Beta")
        repository.createWorkspace("Alpha")
        repository.createWorkspace("Gamma")

        val workspaces = repository.getWorkspaces()
        assertEquals(3, workspaces.size)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), workspaces.map { it.name })
    }

    @Test
    fun testPathTraversalDefenseInPlanFiles() {
        val ws = repository.createWorkspace("Alpha")

        // Attempt path traversal when saving plan file
        val savedFile = repository.savePlanFile(ws, "../../evil.md", "Malicious content")

        // Must be saved inside ws.rootDir/planes, not in root or tempBaseDir
        val outsideFile = File(tempBaseDir, "evil.md")
        assertFalse("File must not be created outside workspace", outsideFile.exists())

        val parent = savedFile.parentFile
        assertNotNull(parent)
        assertEquals(File(ws.rootDir, "planes").canonicalPath, parent!!.canonicalPath)
        assertEquals("evil.md", savedFile.name)
        assertEquals("Malicious content", repository.readPlanFile(ws, "evil.md"))
    }

    @Test(expected = SecurityException::class)
    fun testPathTraversalDefenseInReadPlanFile() {
        val ws = repository.createWorkspace("Alpha")
        repository.readPlanFile(ws, "../../evil.md")
    }

    @Test
    fun testCreateWorkspaceSanitizesDotsAndAvoidsDirectoryEscaping() {
        val wsDots = repository.createWorkspace("..")
        assertEquals("workspace", wsDots.name)
        assertEquals("workspace", wsDots.id)
        assertTrue(wsDots.rootDir.canonicalPath.startsWith(tempBaseDir.canonicalPath))
        assertEquals(tempBaseDir.resolve("workspace").canonicalPath, wsDots.rootDir.canonicalPath)

        val wsSingleDot = repository.createWorkspace(".")
        assertEquals("workspace", wsSingleDot.name)
        assertEquals("workspace", wsSingleDot.id)
    }

    @Test(expected = SecurityException::class)
    fun testPathTraversalPrefixCollisionDefense() {
        val ws = repository.createWorkspace("Alpha")
        val siblingDir = tempBaseDir.resolve("AlphaEvil")
        siblingDir.mkdirs()
        val evilPlan = siblingDir.resolve("plan.md")
        evilPlan.writeText("evil")

        repository.readPlanFile(ws, "../AlphaEvil/plan.md")
    }

    @Test
    fun testWorkspaceChangesFlowEmitsOnSave() = runTest {
        val tempDir = Files.createTempDirectory("goplan_repo_test").toFile()
        val fm = WorkspaceFileManager(tempDir)
        val p = TodoMarkdownParser()
        val repo = WorkspaceRepository(fm, p)
        val ws = repo.createWorkspace("Reactivo")

        val emittedList = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.workspaceChangesFlow.collect { emittedList.add(it) }
        }

        repo.saveContextContent(ws, "# Nuevo Contexto")
        assertEquals(listOf("Reactivo"), emittedList)

        val doc = repo.getTodoDocument(ws)
        val updatedDoc = p.addIdea(doc, "Idea de prueba", setOf("tag1"))
        repo.saveTodoDocument(ws, updatedDoc)
        assertEquals(listOf("Reactivo", "Reactivo"), emittedList)

        repo.savePlanFile(ws, "plan1.md", "# Plan 1")
        assertEquals(listOf("Reactivo", "Reactivo", "Reactivo"), emittedList)

        repo.notifyWorkspaceChanged(ws.id)
        assertEquals(listOf("Reactivo", "Reactivo", "Reactivo", "Reactivo"), emittedList)

        job.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun testMutexSerializesConcurrentWrites() = runTest {
        val ws = repository.createWorkspace("Concurrente")
        val coroutineCount = 10
        val jobs = (1..coroutineCount).map { i ->
            launch(Dispatchers.Default) {
                repository.savePlanFile(ws, "plan-$i.md", "# Contenido del plan $i")
                repository.saveContextContent(ws, "# Contexto por corrutina $i")
            }
        }
        jobs.joinAll()

        for (i in 1..coroutineCount) {
            val content = repository.readPlanFile(ws, "plan-$i.md")
            assertEquals("# Contenido del plan $i", content)
        }
        val finalContext = repository.getContextContent(ws)
        assertTrue(finalContext.startsWith("# Contexto por corrutina"))
    }
}
