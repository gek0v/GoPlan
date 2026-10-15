package com.goplan.app.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class ServerStatusResponse(
    val status: String,
    val app: String,
    val version: String,
    val activeWorkspace: String?,
    val ip: String?,
    val port: Int
)

@Serializable
data class WorkspaceDto(
    val id: String,
    val name: String,
    val stats: WorkspaceStatsDto
)

@Serializable
data class WorkspaceStatsDto(
    val total: Int,
    val pending: Int,
    val inProgress: Int,
    val completed: Int,
    val discarded: Int
)

@Serializable
data class ContextDto(
    val workspaceId: String,
    val content: String
)

@Serializable
data class TodoResponseDto(
    val rawMarkdown: String,
    val ideas: List<IdeaDto>
)

@Serializable
data class IdeaDto(
    val id: String,
    val title: String,
    val status: String,
    val tags: List<String>,
    val plans: List<PlanLinkDto>
)

@Serializable
data class PlanLinkDto(
    val label: String,
    val relativePath: String
)

@Serializable
data class CreateWorkspaceRequest(
    val name: String
)

@Serializable
data class CreateIdeaRequest(
    val text: String
)

@Serializable
data class UpdateIdeaRequest(
    val status: String? = null,
    val title: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class IngestPlanRequest(
    val ideaId: String,
    val files: List<PlanFileDto>? = null,
    val rawText: String? = null
)

@Serializable
data class PlanFileDto(
    val fileName: String,
    val content: String
)

@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
    val createdFiles: List<String>? = null,
    val idea: IdeaDto? = null
)
