package com.goplan.app.domain.model

sealed interface TodoLine {
    data class Header(val rawText: String, val level: Int) : TodoLine
    data class Task(val idea: Idea) : TodoLine
    data class Other(val rawText: String) : TodoLine
}

data class TodoDocument(
    val lines: List<TodoLine>,
    val lineSeparator: String = "\n"
)
