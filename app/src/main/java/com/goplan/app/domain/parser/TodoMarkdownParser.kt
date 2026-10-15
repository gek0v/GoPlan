package com.goplan.app.domain.parser

import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.TodoLine

class TodoMarkdownParser {

    private val taskRegex = Regex("""^(\s*-\s*\[([ xX/\-])\])(?:\s+(.*))?$""")
    private val headerRegex = Regex("""^(#{1,6})\s+(.*)$""")
    private val explicitIdRegex = Regex("""<!--\s*id:([a-zA-Z0-9_-]+)\s*-->""")
    private val groupedLinksRegex = Regex("""\(\s*(\[[^\]]+\]\([^)]+\)(?:\s*,\s*\[[^\]]+\]\([^)]+\))*)\s*\)""")
    private val linkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
    private val tagRegex = Regex("""(?<=^|\s)#([a-zA-Z0-9_-]+)""")
    private val markerReplacementRegex = Regex("""^(\s*-\s*\[)[ xX/\-](\])""")

    fun parse(content: String): TodoDocument {
        if (content.isEmpty()) {
            return TodoDocument(emptyList())
        }
        val lineSeparator = if (content.contains("\r\n")) "\r\n" else "\n"
        val lines = content.lines().map { line ->
            parseLine(line)
        }
        return TodoDocument(lines = lines, lineSeparator = lineSeparator)
    }

    fun serialize(document: TodoDocument): String {
        return document.lines.joinToString(document.lineSeparator) { line ->
            when (line) {
                is TodoLine.Header -> line.rawText
                is TodoLine.Task -> line.idea.rawLine
                is TodoLine.Other -> line.rawText
            }
        }
    }

    fun updateIdeaStatus(document: TodoDocument, ideaId: String, newStatus: IdeaStatus): TodoDocument {
        val newLines = document.lines.map { line ->
            if (line is TodoLine.Task && line.idea.id == ideaId) {
                val updatedRawLine = replaceTaskMarker(line.idea.rawLine, newStatus)
                val updatedIdea = line.idea.copy(status = newStatus, rawLine = updatedRawLine)
                TodoLine.Task(updatedIdea)
            } else {
                line
            }
        }
        return document.copy(lines = newLines)
    }

    fun linkPlansToIdea(document: TodoDocument, ideaId: String, plans: List<PlanLink>): TodoDocument {
        val newLines = document.lines.map { line ->
            if (line is TodoLine.Task && line.idea.id == ideaId) {
                val newStatus = if (line.idea.status == IdeaStatus.PENDING) {
                    IdeaStatus.IN_PROGRESS
                } else {
                    line.idea.status
                }

                val indent = line.idea.rawLine.takeWhile { it.isWhitespace() }
                val markerPrefix = "$indent- ${newStatus.marker}"

                val body = if (plans.size == 1 && plans[0].label.equals(line.idea.title, ignoreCase = true)) {
                    "[${line.idea.title}](${plans[0].relativePath})"
                } else if (plans.size == 1) {
                    "${line.idea.title} ([${plans[0].label}](${plans[0].relativePath}))"
                } else if (plans.size > 1) {
                    val linksString = plans.joinToString(", ") { "[${it.label}](${it.relativePath})" }
                    "${line.idea.title} ($linksString)"
                } else {
                    line.idea.title
                }

                val tagsPart = if (line.idea.tags.isNotEmpty()) {
                    " " + line.idea.tags.joinToString(" ") { "#$it" }
                } else {
                    ""
                }

                val hasExplicitId = explicitIdRegex.containsMatchIn(line.idea.rawLine)
                val idPart = if (hasExplicitId) {
                    " <!-- id:${line.idea.id} -->"
                } else {
                    ""
                }

                val newRawLine = "$markerPrefix $body$tagsPart$idPart"
                val updatedIdea = line.idea.copy(
                    status = newStatus,
                    plans = plans,
                    rawLine = newRawLine
                )
                TodoLine.Task(updatedIdea)
            } else {
                line
            }
        }
        return document.copy(lines = newLines)
    }

    fun addIdea(document: TodoDocument, title: String, tags: Set<String>): TodoDocument {
        val cleanTitle = title.trim()
        val tagsPart = if (tags.isNotEmpty()) " " + tags.joinToString(" ") { "#$it" } else ""
        val rawLine = "- [ ] $cleanTitle$tagsPart"
        val id = cleanTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val idea = Idea(
            id = id,
            status = IdeaStatus.PENDING,
            title = cleanTitle,
            tags = tags,
            plans = emptyList(),
            rawLine = rawLine
        )
        val newTaskLine = TodoLine.Task(idea)

        val lastNonBlankIndex = document.lines.indexOfLast { line ->
            when (line) {
                is TodoLine.Other -> line.rawText.isNotBlank()
                else -> true
            }
        }
        val insertIndex = if (lastNonBlankIndex == -1) document.lines.size else lastNonBlankIndex + 1
        val newLines = document.lines.toMutableList().apply {
            add(insertIndex, newTaskLine)
        }
        return document.copy(lines = newLines)
    }

    private fun parseLine(line: String): TodoLine {
        val taskMatch = taskRegex.find(line)
        if (taskMatch != null) {
            val markerChar = taskMatch.groupValues[2]
            val status = when (markerChar) {
                " " -> IdeaStatus.PENDING
                "/" -> IdeaStatus.IN_PROGRESS
                "x", "X" -> IdeaStatus.COMPLETED
                "-" -> IdeaStatus.DISCARDED
                else -> IdeaStatus.PENDING
            }
            val rest = taskMatch.groupValues[3]
            val idMatch = explicitIdRegex.find(rest)
            val explicitId = idMatch?.groupValues?.get(1)
            val withoutId = rest.replace(explicitIdRegex, "").trim()

            val groupedMatch = groupedLinksRegex.find(withoutId)
            val title: String
            val plans: List<PlanLink>
            val tags: Set<String>

            if (groupedMatch != null) {
                val groupContent = groupedMatch.groupValues[1]
                plans = linkRegex.findAll(groupContent).map {
                    PlanLink(label = it.groupValues[1].trim(), relativePath = it.groupValues[2].trim())
                }.toList()
                val textWithoutGroup = withoutId.removeRange(groupedMatch.range).trim()
                tags = tagRegex.findAll(textWithoutGroup).map { it.groupValues[1] }.toSet()
                title = textWithoutGroup.replace(tagRegex, " ").trim().replace(Regex("""\s+"""), " ")
            } else {
                val standaloneLinks = linkRegex.findAll(withoutId).toList()
                if (standaloneLinks.isNotEmpty()) {
                    val firstLink = standaloneLinks.first()
                    val textBefore = withoutId.substring(0, firstLink.range.first).trim()
                    if (textBefore.isEmpty()) {
                        title = firstLink.groupValues[1].trim()
                        plans = standaloneLinks.map {
                            PlanLink(label = it.groupValues[1].trim(), relativePath = it.groupValues[2].trim())
                        }
                        val textAfter = withoutId.substring(firstLink.range.last + 1).trim()
                        val textAfterWithoutLinks = textAfter.replace(linkRegex, "")
                        tags = tagRegex.findAll(textAfterWithoutLinks).map { it.groupValues[1] }.toSet()
                    } else {
                        plans = standaloneLinks.map {
                            PlanLink(label = it.groupValues[1].trim(), relativePath = it.groupValues[2].trim())
                        }
                        val textWithoutLinks = withoutId.let { text ->
                            var temp = text
                            for (l in standaloneLinks) {
                                temp = temp.replace(l.value, " ")
                            }
                            temp
                        }
                        tags = tagRegex.findAll(textWithoutLinks).map { it.groupValues[1] }.toSet()
                        title = textBefore.replace(tagRegex, " ").trim().replace(Regex("""\s+"""), " ")
                    }
                } else {
                    plans = emptyList()
                    tags = tagRegex.findAll(withoutId).map { it.groupValues[1] }.toSet()
                    title = withoutId.replace(tagRegex, " ").trim().replace(Regex("""\s+"""), " ")
                }
            }

            val id = explicitId ?: title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            val idea = Idea(
                id = id,
                status = status,
                title = title,
                tags = tags,
                plans = plans,
                rawLine = line
            )
            return TodoLine.Task(idea)
        }

        val headerMatch = headerRegex.find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            return TodoLine.Header(rawText = line, level = level)
        }

        return TodoLine.Other(rawText = line)
    }

    private fun replaceTaskMarker(rawLine: String, newStatus: IdeaStatus): String {
        return if (markerReplacementRegex.containsMatchIn(rawLine)) {
            val markerChar = newStatus.marker.substring(1, 2)
            markerReplacementRegex.replaceFirst(rawLine, "$1$markerChar$2")
        } else {
            rawLine
        }
    }
}
