package com.goplan.app.domain.usecase

import com.goplan.app.domain.model.Idea

class PrepareGeminiPromptUseCase {

    operator fun invoke(contextContent: String, idea: Idea): String {
        val tagsString = idea.tags.joinToString(", ")
        return """
            # CONTEXTO DEL PROYECTO
            $contextContent

            # IDEA A DESARROLLAR
            - ${idea.title}
            Etiquetas: $tagsString

            # FORMATO DE SALIDA ESTRICTO
            Transforma esta idea en un plan técnico detallado, modular y accionable para un agente de código.
            Si el plan requiere dividirse en varios archivos, separa cada archivo usando estrictamente la línea:
            === ARCHIVO: nombre-archivo-en-kebab-case.md ===

            Si cabe en un solo archivo, inicia con:
            === ARCHIVO: <nombre-sugerido>.md ===
        """.trimIndent()
    }

    fun prepareBatchPrompt(contextContent: String, ideas: List<Idea>): String {
        val ideasListString = ideas.joinToString("\n") { idea ->
            val tags = if (idea.tags.isNotEmpty()) " " + idea.tags.joinToString(" ") { "#$it" } else ""
            "- ${idea.title}$tags"
        }

        return """
            # CONTEXTO DEL PROYECTO
            $contextContent

            # IDEAS A DESARROLLAR
            $ideasListString

            # FORMATO DE SALIDA ESTRICTO
            Genera un plan técnico detallado, modular y accionable para cada una de las ideas anteriores.
            Separa CADA archivo de plan usando estrictamente la línea delimitadora:
            === ARCHIVO: nombre-archivo-en-kebab-case.md ===

            Ejemplo:
            === ARCHIVO: plan-modulo-auth.md ===
            (contenido técnico)

            === ARCHIVO: plan-api-usuarios.md ===
            (contenido técnico)
        """.trimIndent()
    }
}
