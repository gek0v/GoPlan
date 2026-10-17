package com.goplan.app.domain.model

data class WorkspaceSummary(
    val workspace: Workspace,
    val isActive: Boolean,
    val pendingIdeasCount: Int,
    val inProgressIdeasCount: Int,
    val plansCount: Int
)
