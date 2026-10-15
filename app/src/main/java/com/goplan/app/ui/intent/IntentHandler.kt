package com.goplan.app.ui.intent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object IntentHandler {

    const val GEMINI_PACKAGE_NAME = "com.google.android.apps.bard"

    /**
     * Copies the prepared prompt to the clipboard, notifies the user, and opens Gemini.
     */
    fun sharePromptToGemini(context: Context, prompt: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("GoPlan Prompt", prompt)
        clipboard?.setPrimaryClip(clip)

        Toast.makeText(
            context,
            "Prompt copiado al portapapeles. Pégalo en Gemini",
            Toast.LENGTH_LONG
        ).show()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE_NAME)
        if (launchIntent != null) {
            if (context !is Activity) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(launchIntent)
                return
            } catch (_: Exception) {
                // fallback
            }
        }

        // Fallback: Open Gemini Web in browser
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com")).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
            }, "Expandir con Gemini")
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    /**
     * Checks if the received text is a web share link from Gemini (share.gemini.google.com)
     * rather than actual markdown content.
     */
    fun isGeminiShareLink(text: String): Boolean {
        return text.contains("share.gemini.google.com", ignoreCase = true)
    }

    /**
     * Shares an exported bundle (ZIP or Markdown) using FileProvider.
     */
    fun shareExportFile(context: Context, file: File, mimeType: String = "application/zip") {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = android.content.ClipData.newRawUri("", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Exportar para agente")
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Extracts incoming text from ACTION_SEND intents, checking EXTRA_TEXT first,
     * and EXTRA_STREAM (Uri) as a fallback.
     */
    fun extractIncomingText(context: Context, intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) {
            return null
        }

        val extraText = try {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } catch (_: Exception) {
            null
        }

        if (!extraText.isNullOrBlank()) {
            return extraText
        }

        val streamUri: Uri? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } catch (_: Exception) {
            null
        }

        if (streamUri != null) {
            return try {
                context.contentResolver.openInputStream(streamUri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                }
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /**
     * Pure testable extraction logic for unit testing without Android runtime stubs.
     */
    internal fun extractIncomingTextFromData(
        action: String?,
        extraText: String?,
        hasStreamUri: Boolean,
        streamReader: (() -> String?)? = null
    ): String? {
        if (action != Intent.ACTION_SEND) {
            return null
        }
        if (!extraText.isNullOrBlank()) {
            return extraText
        }
        if (hasStreamUri && streamReader != null) {
            return try {
                streamReader()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}
