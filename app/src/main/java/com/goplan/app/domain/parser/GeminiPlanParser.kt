package com.goplan.app.domain.parser

import java.io.File
import java.text.Normalizer

data class ParsedPlanFile(
    val fileName: String,
    val content: String
)

class GeminiPlanParser {

    private val delimiterRegex = Regex(
        """===\s*ARCHIVO:\s*([^\r\n=]+?\.md)\s*===""",
        RegexOption.IGNORE_CASE
    )

    fun parsePlans(rawText: String, fallbackIdeaTitle: String): List<ParsedPlanFile> {
        val matches = delimiterRegex.findAll(rawText).toList()
        if (matches.isEmpty()) {
            val fileName = sanitizeFileName(fallbackIdeaTitle)
            return listOf(ParsedPlanFile(fileName = fileName, content = rawText.trim()))
        }

        val result = mutableListOf<ParsedPlanFile>()
        for (i in matches.indices) {
            val currentMatch = matches[i]
            val rawFileName = currentMatch.groupValues[1]
            val fileName = sanitizeFileName(rawFileName)
            val contentStart = currentMatch.range.last + 1
            val contentEnd = if (i + 1 < matches.size) {
                matches[i + 1].range.first
            } else {
                rawText.length
            }
            val content = rawText.substring(contentStart, contentEnd).trim()
            result.add(ParsedPlanFile(fileName = fileName, content = content))
        }
        return result
    }

    fun sanitizeFileName(rawFileName: String): String {
        val trimmed = rawFileName.trim()
        // Neutralize directory prefixes and path traversal slashes across platforms
        val normalizedSlashes = trimmed.replace('\\', '/')
        val simpleName = normalizedSlashes.substringAfterLast('/')
        val fileBase = File(simpleName).name

        // Strip .md extension (case-insensitive) if already present
        val withoutExt = if (fileBase.endsWith(".md", ignoreCase = true)) {
            fileBase.substring(0, fileBase.length - 3)
        } else {
            fileBase
        }

        // Normalize unicode accents/diacritics to basic Latin
        val normalizedAscii = Normalizer.normalize(withoutExt, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // Normalize to safe kebab-case [a-z0-9_-]+
        val slug = normalizedAscii.lowercase()
            .replace('_', '-')
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

        val finalSlug = if (slug.isEmpty()) "plan" else slug
        return "$finalSlug.md"
    }
}
