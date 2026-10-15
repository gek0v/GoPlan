package com.goplan.app.ui.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentHandlerTest {

    private val actionSend = "android.intent.action.SEND"

    @Test
    fun testGeminiPackageNameConstant() {
        assertEquals("com.google.android.apps.bard", IntentHandler.GEMINI_PACKAGE_NAME)
    }

    @Test
    fun testExtractIncomingText_nullAction_returnsNull() {
        val result = IntentHandler.extractIncomingTextFromData(
            action = null,
            extraText = "Some text",
            hasStreamUri = false
        )
        assertNull(result)
    }

    @Test
    fun testExtractIncomingText_nonSendAction_returnsNull() {
        val result = IntentHandler.extractIncomingTextFromData(
            action = "android.intent.action.VIEW",
            extraText = "Some text",
            hasStreamUri = false
        )
        assertNull(result)
    }

    @Test
    fun testExtractIncomingText_withExtraText_returnsText() {
        val expected = "=== ARCHIVO: feature.md ===\nContenido del plan"
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = expected,
            hasStreamUri = false
        )
        assertEquals(expected, result)
    }

    @Test
    fun testExtractIncomingText_withBlankExtraTextAndStream_readsStream() {
        val streamContent = "# Contexto importado desde stream"
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = "   ",
            hasStreamUri = true,
            streamReader = { streamContent }
        )
        assertEquals(streamContent, result)
    }

    @Test
    fun testExtractIncomingText_withNullExtraTextAndStream_readsStream() {
        val streamContent = "Contenido leído desde archivo stream"
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = null,
            hasStreamUri = true,
            streamReader = { streamContent }
        )
        assertEquals(streamContent, result)
    }

    @Test
    fun testExtractIncomingText_withBlankExtraTextAndNoStream_returnsNull() {
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = "",
            hasStreamUri = false
        )
        assertNull(result)
    }

    @Test
    fun testExtractIncomingText_withNullExtraTextAndNoStream_returnsNull() {
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = null,
            hasStreamUri = false
        )
        assertNull(result)
    }

    @Test
    fun testExtractIncomingText_whenStreamReaderThrows_returnsNullSafely() {
        val result = IntentHandler.extractIncomingTextFromData(
            action = actionSend,
            extraText = null,
            hasStreamUri = true,
            streamReader = { throw SecurityException("Permiso denegado al leer URI") }
        )
        assertNull(result)
    }
}
