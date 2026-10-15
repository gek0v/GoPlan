package com.goplan.app.domain.export

import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.storage.WorkspaceFileManager
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.parser.TodoMarkdownParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class AgentExportManagerTest {

    private lateinit var tempBaseDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var parser: TodoMarkdownParser
    private lateinit var repository: WorkspaceRepository
    private lateinit var exportManager: AgentExportManager

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("goplan_export_test").toFile()
        fileManager = WorkspaceFileManager(tempBaseDir)
        parser = TodoMarkdownParser()
        repository = WorkspaceRepository(fileManager, parser)
        exportManager = AgentExportManager(repository)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testCreateZipBundleWithIdeasAndPlans() {
        val ws = repository.createWorkspace("MyWorkspace")
        repository.saveContextContent(ws, "# Custom Project Context\n- Tech: Kotlin, Compose")

        repository.savePlanFile(ws, "auth-spec.md", "# Plan Auth\nOAuth details here.")
        repository.savePlanFile(ws, "db-spec.md", "# Plan DB\nRoom entities and DAOs.")

        val idea1 = Idea(
            id = "idea-1",
            status = IdeaStatus.PENDING,
            title = "Implement Authentication",
            tags = setOf("auth", "security"),
            plans = listOf(PlanLink("Auth Spec", "planes/auth-spec.md")),
            rawLine = "- [ ] Implement Authentication ( [Auth Spec](planes/auth-spec.md) ) #auth #security"
        )
        val idea2 = Idea(
            id = "idea-2",
            status = IdeaStatus.IN_PROGRESS,
            title = "Design Database",
            tags = setOf("database"),
            plans = listOf(PlanLink("DB Spec", "planes/db-spec.md")),
            rawLine = "- [/] Design Database ( [DB Spec](planes/db-spec.md) ) #database"
        )

        val outputZip = File(tempBaseDir, "exports/bundle.zip")
        val resultFile = exportManager.createZipBundle(ws, listOf(idea1, idea2), outputZip)

        assertEquals(outputZip.absolutePath, resultFile.absolutePath)
        assertTrue("Output zip file should exist", outputZip.exists())
        assertTrue("Output zip should be a file", outputZip.isFile)

        ZipFile(outputZip).use { zip ->
            val contextContent = readZipEntry(zip, "CONTEXT.md")
            assertEquals("# Custom Project Context\n- Tech: Kotlin, Compose", contextContent)

            val todoExtractContent = readZipEntry(zip, "TODO_EXTRACT.md")
            val expectedTodoExtract = "${idea1.rawLine}\n${idea2.rawLine}\n"
            assertEquals(expectedTodoExtract, todoExtractContent)

            val authPlanContent = readZipEntry(zip, "planes/auth-spec.md")
            assertEquals("# Plan Auth\nOAuth details here.", authPlanContent)

            val dbPlanContent = readZipEntry(zip, "planes/db-spec.md")
            assertEquals("# Plan DB\nRoom entities and DAOs.", dbPlanContent)
        }
    }

    @Test
    fun testCreateZipBundleCreatesParentDirectoriesIfMissing() {
        val ws = repository.createWorkspace("NestedDirWorkspace")
        val nestedZip = File(tempBaseDir, "a/b/c/nested_bundle.zip")

        val resultFile = exportManager.createZipBundle(ws, emptyList(), nestedZip)

        assertTrue(resultFile.exists())
        ZipFile(resultFile).use { zip ->
            assertNotNull(zip.getEntry("CONTEXT.md"))
            assertNotNull(zip.getEntry("TODO_EXTRACT.md"))
        }
    }

    @Test
    fun testCreateZipBundleDeduplicatesSharedPlans() {
        val ws = repository.createWorkspace("DeduplicateWorkspace")
        repository.savePlanFile(ws, "shared-plan.md", "# Shared Plan Content")

        val idea1 = Idea(
            id = "idea-1",
            status = IdeaStatus.PENDING,
            title = "Task One",
            tags = setOf("core"),
            plans = listOf(PlanLink("Shared Plan", "planes/shared-plan.md")),
            rawLine = "- [ ] Task One ( [Shared Plan](planes/shared-plan.md) ) #core"
        )
        val idea2 = Idea(
            id = "idea-2",
            status = IdeaStatus.COMPLETED,
            title = "Task Two",
            tags = setOf("core"),
            plans = listOf(PlanLink("Shared Plan Alias", "planes/shared-plan.md")),
            rawLine = "- [x] Task Two ( [Shared Plan Alias](planes/shared-plan.md) ) #core"
        )

        val outputZip = File(tempBaseDir, "shared_bundle.zip")
        exportManager.createZipBundle(ws, listOf(idea1, idea2), outputZip)

        ZipFile(outputZip).use { zip ->
            var sharedCount = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "planes/shared-plan.md") {
                    sharedCount++
                }
            }
            assertEquals("Should have exactly 1 entry for planes/shared-plan.md", 1, sharedCount)
            assertEquals("# Shared Plan Content", readZipEntry(zip, "planes/shared-plan.md"))
        }
    }

    @Test
    fun testCreateZipBundleWithEmptyIdeas() {
        val ws = repository.createWorkspace("EmptyWorkspace")
        val outputZip = File(tempBaseDir, "empty_bundle.zip")

        exportManager.createZipBundle(ws, emptyList(), outputZip)

        ZipFile(outputZip).use { zip ->
            val contextEntry = zip.getEntry("CONTEXT.md")
            assertNotNull("CONTEXT.md should be in zip", contextEntry)

            val todoEntry = zip.getEntry("TODO_EXTRACT.md")
            assertNotNull("TODO_EXTRACT.md should be in zip", todoEntry)
            val todoText = readZipEntry(zip, "TODO_EXTRACT.md")
            assertTrue("TODO_EXTRACT.md should be empty", todoText.isEmpty())

            var planCount = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("planes/")) {
                    planCount++
                }
            }
            assertEquals("Should not contain plan files", 0, planCount)
        }
    }

    @Test
    fun testCreateUnifiedMarkdownContainsContextTasksTagsAndPlans() {
        val ws = repository.createWorkspace("UnifiedWorkspace")
        repository.saveContextContent(ws, "# Workspace Context\nArquitectura: Clean Architecture")

        repository.savePlanFile(ws, "payment-flow.md", "# Pago con Tarjeta\nValidar token y cobrar.")

        val idea = Idea(
            id = "idea-pay",
            status = IdeaStatus.IN_PROGRESS,
            title = "Integrar Pasarela de Pagos",
            tags = setOf("pagos", "stripe"),
            plans = listOf(PlanLink("Flujo Pago", "planes/payment-flow.md")),
            rawLine = "- [/] Integrar Pasarela de Pagos ( [Flujo Pago](planes/payment-flow.md) ) #pagos #stripe"
        )

        val markdown = exportManager.createUnifiedMarkdown(ws, listOf(idea))

        assertTrue("Should contain CONTEXT header", markdown.contains("# CONTEXTO DEL PROYECTO"))
        assertTrue("Should contain context body", markdown.contains("Arquitectura: Clean Architecture"))
        assertTrue("Should contain TAREAS Y PLANES header", markdown.contains("## TAREAS Y PLANES"))
        assertTrue("Should contain idea title header", markdown.contains("### Integrar Pasarela de Pagos"))
        assertTrue("Should contain status", markdown.contains("- Estado: [/]"))
        assertTrue("Should contain tags", markdown.contains("pagos") && markdown.contains("stripe"))
        assertTrue("Should contain plan label header", markdown.contains("#### Plan: Flujo Pago"))
        assertTrue("Should contain plan content", markdown.contains("# Pago con Tarjeta\nValidar token y cobrar."))
    }

    @Test
    fun testCreateUnifiedMarkdownWithEmptyIdeas() {
        val ws = repository.createWorkspace("UnifiedEmptyWorkspace")
        val markdown = exportManager.createUnifiedMarkdown(ws, emptyList())

        assertTrue("Should contain CONTEXT header", markdown.contains("# CONTEXTO DEL PROYECTO"))
        assertTrue("Should contain TAREAS Y PLANES header", markdown.contains("## TAREAS Y PLANES"))
    }

    @Test
    fun testCreateUnifiedMarkdownWithIdeaWithoutPlans() {
        val ws = repository.createWorkspace("NoPlansWorkspace")
        val idea = Idea(
            id = "simple-idea",
            status = IdeaStatus.PENDING,
            title = "Refactor Utils",
            tags = setOf("refactor"),
            plans = emptyList(),
            rawLine = "- [ ] Refactor Utils #refactor"
        )

        val markdown = exportManager.createUnifiedMarkdown(ws, listOf(idea))

        assertTrue(markdown.contains("### Refactor Utils"))
        assertTrue(markdown.contains("refactor"))
        org.junit.Assert.assertFalse("Should not have any plan headers", markdown.contains("#### Plan:"))
    }

    private fun readZipEntry(zip: ZipFile, entryName: String): String {
        val entry = zip.getEntry(entryName) ?: throw AssertionError("Entry $entryName not found in zip")
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
