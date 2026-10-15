package com.goplan.app.domain.usecase

import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrepareGeminiPromptUseCaseTest {

    private lateinit var useCase: PrepareGeminiPromptUseCase

    @Before
    fun setUp() {
        useCase = PrepareGeminiPromptUseCase()
    }

    @Test
    fun testPromptIncludesContextIdeaTitleTagsAndStrictFormat() {
        val context = """
            # Reglas del Proyecto
            - Usar Jetpack Compose y Material 3.
            - Kotlin 2.0 y Clean Architecture.
        """.trimIndent()

        val idea = Idea(
            id = "idea-123",
            status = IdeaStatus.PENDING,
            title = "Implementar Sistema de Autenticación",
            tags = linkedSetOf("auth", "security"),
            plans = emptyList(),
            rawLine = "- [ ] Implementar Sistema de Autenticación #auth #security"
        )

        val prompt = useCase(contextContent = context, idea = idea)

        assertTrue("Should include CONTEXT section header", prompt.contains("# CONTEXTO DEL PROYECTO"))
        assertTrue("Should include context content", prompt.contains(context))
        assertTrue("Should include IDEA section header", prompt.contains("# IDEA A DESARROLLAR"))
        assertTrue("Should include idea title", prompt.contains("- Implementar Sistema de Autenticación"))
        assertTrue("Should include idea tags", prompt.contains("Etiquetas: auth, security"))
        assertTrue("Should include STRICT FORMAT header", prompt.contains("# FORMATO DE SALIDA ESTRICTO"))
        assertTrue("Should include delimiter instruction for multiple files", prompt.contains("=== ARCHIVO: nombre-archivo-en-kebab-case.md ==="))
        assertTrue("Should include delimiter instruction for single file", prompt.contains("=== ARCHIVO: <nombre-sugerido>.md ==="))
    }

    @Test
    fun testPromptWithEmptyTags() {
        val idea = Idea(
            id = "idea-456",
            status = IdeaStatus.PENDING,
            title = "Idea sin etiquetas",
            tags = emptySet(),
            plans = emptyList(),
            rawLine = "- [ ] Idea sin etiquetas"
        )

        val prompt = useCase(contextContent = "Contexto breve", idea = idea)

        assertTrue(prompt.contains("- Idea sin etiquetas"))
        assertTrue(prompt.contains("Etiquetas: \n") || prompt.contains("Etiquetas:\n") || prompt.contains("Etiquetas: \r\n"))
    }
}
