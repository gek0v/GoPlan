package com.goplan.app.domain.parser

import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.TodoLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TodoMarkdownParserTest {

    private lateinit var parser: TodoMarkdownParser

    @Before
    fun setUp() {
        parser = TodoMarkdownParser()
    }

    @Test
    fun testParseTaskStatuses() {
        val markdown = """
            - [ ] Tarea pendiente
            - [/] Tarea en progreso
            - [x] Tarea completada
            - [-] Tarea descartada
              - [ ] Tarea con sangría
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertEquals(5, doc.lines.size)

        val task1 = (doc.lines[0] as TodoLine.Task).idea
        assertEquals(IdeaStatus.PENDING, task1.status)
        assertEquals("Tarea pendiente", task1.title)

        val task2 = (doc.lines[1] as TodoLine.Task).idea
        assertEquals(IdeaStatus.IN_PROGRESS, task2.status)
        assertEquals("Tarea en progreso", task2.title)

        val task3 = (doc.lines[2] as TodoLine.Task).idea
        assertEquals(IdeaStatus.COMPLETED, task3.status)
        assertEquals("Tarea completada", task3.title)

        val task4 = (doc.lines[3] as TodoLine.Task).idea
        assertEquals(IdeaStatus.DISCARDED, task4.status)
        assertEquals("Tarea descartada", task4.title)

        val task5 = (doc.lines[4] as TodoLine.Task).idea
        assertEquals(IdeaStatus.PENDING, task5.status)
        assertEquals("Tarea con sangría", task5.title)
    }

    @Test
    fun testExtractSimpleLink() {
        val markdown = """
            - [ ] [Título](planes/p1.md)
            - [/] [Implementar OAuth Google](planes/oauth-google.md) #auth #backend
            - [ ] Tarea con enlace [Plan de Acción](planes/action.md)
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertEquals(3, doc.lines.size)

        val task1 = (doc.lines[0] as TodoLine.Task).idea
        assertEquals("Título", task1.title)
        assertEquals(listOf(PlanLink("Título", "planes/p1.md")), task1.plans)

        val task2 = (doc.lines[1] as TodoLine.Task).idea
        assertEquals("Implementar OAuth Google", task2.title)
        assertEquals(listOf(PlanLink("Implementar OAuth Google", "planes/oauth-google.md")), task2.plans)
        assertEquals(setOf("auth", "backend"), task2.tags)

        val task3 = (doc.lines[2] as TodoLine.Task).idea
        assertEquals("Tarea con enlace", task3.title)
        assertEquals(listOf(PlanLink("Plan de Acción", "planes/action.md")), task3.plans)
    }

    @Test
    fun testExtractMultiLinks() {
        val markdown = "- [ ] Crear módulo de concursos ([UI](planes/concurso-ui.md), [API](planes/concurso-api.md)) #feature #ui"

        val doc = parser.parse(markdown)
        assertEquals(1, doc.lines.size)

        val task = (doc.lines[0] as TodoLine.Task).idea
        assertEquals("Crear módulo de concursos", task.title)
        assertEquals(
            listOf(
                PlanLink("UI", "planes/concurso-ui.md"),
                PlanLink("API", "planes/concurso-api.md")
            ),
            task.plans
        )
        assertEquals(setOf("feature", "ui"), task.tags)
    }

    @Test
    fun testExtractTags() {
        val markdown = "- [ ] Autenticación con Firebase #auth #backend #firebase"

        val doc = parser.parse(markdown)
        val task = (doc.lines[0] as TodoLine.Task).idea

        assertEquals("Autenticación con Firebase", task.title)
        assertEquals(setOf("auth", "backend", "firebase"), task.tags)
    }

    @Test
    fun testExtractExplicitIdAndFallbackSlug() {
        val markdown = """
            - [ ] Tarea con ID explícito #tag <!-- id:custom_id-123 -->
            - [ ] Tarea para Fallback Slug
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertEquals(2, doc.lines.size)

        val task1 = (doc.lines[0] as TodoLine.Task).idea
        assertEquals("custom_id-123", task1.id)
        assertEquals("Tarea con ID explícito", task1.title)

        val task2 = (doc.lines[1] as TodoLine.Task).idea
        val expectedSlug = "tarea para fallback slug".trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")
        assertEquals(expectedSlug, task2.id)
        assertEquals("tarea-para-fallback-slug", task2.id)
    }

    @Test
    fun testNonDestructivePreservationAndSerialization() {
        val original = """
            # Backlog Sprint 1

            Nota introductoria sobre el proyecto.
            > Cita o regla importante

            - [ ] Tarea pendiente #dev
            - [/] [Tarea en curso](planes/curso.md) #wip <!-- id:wip-1 -->

            ## Sección Secundaria

            Texto final.
        """.trimIndent()

        val doc = parser.parse(original)
        val serialized = parser.serialize(doc)

        assertEquals(original, serialized)
        assertEquals(11, doc.lines.size)
        assertTrue(doc.lines[0] is TodoLine.Header)
        assertEquals(1, (doc.lines[0] as TodoLine.Header).level)
        assertTrue(doc.lines[1] is TodoLine.Other)
        assertTrue(doc.lines[5] is TodoLine.Task)
        assertTrue(doc.lines[8] is TodoLine.Header)
        assertEquals(2, (doc.lines[8] as TodoLine.Header).level)
    }

    @Test
    fun testUpdateIdeaStatusPreservesFormatting() {
        val original = """
            # Tareas
            - [ ] Tarea pendiente con nota #tag <!-- id:task-1 -->
            - [ ] Otra tarea <!-- id:task-2 -->
        """.trimIndent()

        val doc = parser.parse(original)
        val updated = parser.updateIdeaStatus(doc, "task-1", IdeaStatus.COMPLETED)

        val task1 = (updated.lines[1] as TodoLine.Task).idea
        assertEquals(IdeaStatus.COMPLETED, task1.status)

        val serialized = parser.serialize(updated)
        val expected = """
            # Tareas
            - [x] Tarea pendiente con nota #tag <!-- id:task-1 -->
            - [ ] Otra tarea <!-- id:task-2 -->
        """.trimIndent()

        assertEquals(expected, serialized)
    }

    @Test
    fun testLinkPlansToIdeaTransitionsPendingToInProgress() {
        val original = """
            - [ ] Diseñar pantalla principal #ui <!-- id:ui-1 -->
        """.trimIndent()

        val doc = parser.parse(original)
        val plans = listOf(
            PlanLink("Mockup", "planes/ui-mockup.md"),
            PlanLink("Specs", "planes/ui-specs.md")
        )

        val updated = parser.linkPlansToIdea(doc, "ui-1", plans)
        val task = (updated.lines[0] as TodoLine.Task).idea

        assertEquals(IdeaStatus.IN_PROGRESS, task.status)
        assertEquals(plans, task.plans)

        val serialized = parser.serialize(updated)
        val expected = "- [/] Diseñar pantalla principal ([Mockup](planes/ui-mockup.md), [Specs](planes/ui-specs.md)) #ui <!-- id:ui-1 -->"
        assertEquals(expected, serialized)
    }

    @Test
    fun testLinkSinglePlanMatchesIdeaTitle() {
        val original = "- [ ] Implementar Base de Datos #db <!-- id:db-1 -->"
        val doc = parser.parse(original)
        val plans = listOf(PlanLink("Implementar Base de Datos", "planes/db.md"))

        val updated = parser.linkPlansToIdea(doc, "db-1", plans)
        val serialized = parser.serialize(updated)

        val expected = "- [/] [Implementar Base de Datos](planes/db.md) #db <!-- id:db-1 -->"
        assertEquals(expected, serialized)
    }

    @Test
    fun testAddIdeaInsertsBeforeTrailingBlankLines() {
        val original = """
            # Backlog
            - [ ] Tarea 1

        """.trimIndent()

        val doc = parser.parse(original)
        val updated = parser.addIdea(doc, "Tarea Nueva", setOf("nueva", "feature"))

        val serialized = parser.serialize(updated)
        val expected = """
            # Backlog
            - [ ] Tarea 1
            - [ ] Tarea Nueva #nueva #feature

        """.trimIndent()

        assertEquals(expected, serialized)
    }
}
