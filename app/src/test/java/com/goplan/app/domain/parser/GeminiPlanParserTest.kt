package com.goplan.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiPlanParserTest {

    private lateinit var parser: GeminiPlanParser

    @Before
    fun setUp() {
        parser = GeminiPlanParser()
    }

    @Test
    fun testParsePlansWithMultipleDelimiters() {
        val rawResponse = """
            ¡Hola! He preparado el plan técnico solicitado a continuación:

            === ARCHIVO: 01-architecture.md ===
            # Arquitectura del Sistema
            Definición de capas y módulos.

            === ARCHIVO: 02-database.md ===
            # Base de Datos
            Esquemas Room y migraciones.
        """.trimIndent()

        val files = parser.parsePlans(rawResponse, fallbackIdeaTitle = "Fallback")

        assertEquals(2, files.size)
        assertEquals("01-architecture.md", files[0].fileName)
        assertEquals("# Arquitectura del Sistema\nDefinición de capas y módulos.", files[0].content)

        assertEquals("02-database.md", files[1].fileName)
        assertEquals("# Base de Datos\nEsquemas Room y migraciones.", files[1].content)
    }

    @Test
    fun testParsePlansSanitizesPathTraversalInDelimiters() {
        val rawResponse = """
            === ARCHIVO: ../../etc/passwd.md ===
            malicious content 1
            === ARCHIVO: ..\..\windows\system32\hosts.md ===
            malicious content 2
        """.trimIndent()

        val files = parser.parsePlans(rawResponse, fallbackIdeaTitle = "Fallback")

        assertEquals(2, files.size)
        assertEquals("passwd.md", files[0].fileName)
        assertEquals("malicious content 1", files[0].content)
        assertEquals("hosts.md", files[1].fileName)
        assertEquals("malicious content 2", files[1].content)
    }

    @Test
    fun testSanitizeFileNamePathTraversalDefense() {
        assertEquals("passwd.md", parser.sanitizeFileName("../../passwd.md"))
        assertEquals("passwd.md", parser.sanitizeFileName("..\\..\\passwd.md"))
        assertEquals("secret.md", parser.sanitizeFileName("/etc/private/secret.md"))
        assertEquals("doc.md", parser.sanitizeFileName("C:\\Users\\Admin\\doc.md"))
        assertEquals("plan.md", parser.sanitizeFileName(".."))
        assertEquals("plan.md", parser.sanitizeFileName("../.."))
        assertEquals("plan.md", parser.sanitizeFileName(""))
        assertEquals("plan.md", parser.sanitizeFileName("   "))
    }

    @Test
    fun testSanitizeFileNameNormalizationToKebabCase() {
        assertEquals("mi-gran-plan.md", parser.sanitizeFileName("Mi Gran Plan.md"))
        assertEquals("plan-con-caracteres-especiales-v2-0.md", parser.sanitizeFileName("Plan con Caracteres Especiales! (v2.0).md"))
        assertEquals("uppercase-name.md", parser.sanitizeFileName("UPPERCASE_NAME.MD"))
        assertEquals("already-kebab.md", parser.sanitizeFileName("already-kebab.md"))
        assertEquals("sin-extension.md", parser.sanitizeFileName("Sin Extension"))
        assertEquals("acentos-y-ene.md", parser.sanitizeFileName("Acentos y Eñe.md"))
    }

    @Test
    fun testParsePlansFallbackWhenNoDelimiters() {
        val rawResponse = """
            # Plan Monolítico
            Este plan no incluye delimitadores específicos.
            Debe asignarse íntegro a un único archivo generado.
        """.trimIndent()

        val files = parser.parsePlans(rawResponse, fallbackIdeaTitle = "Diseño de Base de Datos")

        assertEquals(1, files.size)
        assertEquals("diseno-de-base-de-datos.md", files[0].fileName)
        assertEquals(rawResponse.trim(), files[0].content)
    }

    @Test
    fun testParsePlansDelimitersWithWhitespaceAndCaseVariations() {
        val rawResponse = """
            === ARCHIVO:   01-modulo-auth.md   ===
            Contenido Auth
            === archivo: 02-modulo-ui.md ===
            Contenido UI
        """.trimIndent()

        val files = parser.parsePlans(rawResponse, fallbackIdeaTitle = "Fallback")

        assertEquals(2, files.size)
        assertEquals("01-modulo-auth.md", files[0].fileName)
        assertEquals("Contenido Auth", files[0].content)
        assertEquals("02-modulo-ui.md", files[1].fileName)
        assertEquals("Contenido UI", files[1].content)
    }
}
