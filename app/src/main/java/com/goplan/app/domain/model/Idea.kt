package com.goplan.app.domain.model

enum class IdeaStatus(val marker: String) {
    PENDING("[ ]"),
    IN_PROGRESS("[/]"),
    COMPLETED("[x]"),
    DISCARDED("[-]")
}

data class PlanLink(
    val label: String,
    val relativePath: String
)

data class Idea(
    val id: String,
    val status: IdeaStatus,
    val title: String,
    val tags: Set<String>,
    val plans: List<PlanLink>,
    val rawLine: String
)
