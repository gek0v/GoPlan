package com.goplan.app.server.routes

import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.export.AgentExportManager
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.parser.GeminiPlanParser
import com.goplan.app.domain.parser.TodoMarkdownParser
import com.goplan.app.server.dto.ApiResponse
import com.goplan.app.server.dto.ContextDto
import com.goplan.app.server.dto.CreateIdeaRequest
import com.goplan.app.server.dto.CreateWorkspaceRequest
import com.goplan.app.server.dto.IdeaDto
import com.goplan.app.server.dto.IngestPlanRequest
import com.goplan.app.server.dto.PlanLinkDto
import com.goplan.app.server.dto.ServerStatusResponse
import com.goplan.app.server.dto.TodoResponseDto
import com.goplan.app.server.dto.UpdateIdeaRequest
import com.goplan.app.server.dto.WorkspaceDto
import com.goplan.app.server.dto.WorkspaceStatsDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.firstOrNull
import com.goplan.app.data.update.AppUpdateManager
import com.goplan.app.server.ui.getWebDashboardHtml
import java.io.File

fun Route.registerServerRoutes(
    repository: WorkspaceRepository,
    preferencesManager: PreferencesManager,
    agentExportManager: AgentExportManager,
    appUpdateManager: AppUpdateManager? = null,
    getIpAddress: () -> String,
    port: Int
) {
    val todoParser = TodoMarkdownParser()

    // 0. GET / and GET /index.html - Web Dashboard
    get("/") {
        call.respondText(getWebDashboardHtml(), ContentType.Text.Html)
    }
    get("/index.html") {
        call.respondText(getWebDashboardHtml(), ContentType.Text.Html)
    }

    // 1. GET /api/status
    get("/api/status") {
        val activeWorkspace = preferencesManager.getActiveWorkspace().firstOrNull()
        call.respond(
            ServerStatusResponse(
                status = "online",
                app = "GoPlan",
                version = "1.0.0",
                activeWorkspace = activeWorkspace,
                ip = getIpAddress(),
                port = port
            )
        )
    }

    // 2. GET /api/workspaces
    get("/api/workspaces") {
        val workspaces = repository.getWorkspaces().map { ws ->
            val doc = repository.getTodoDocument(ws)
            val tasks = doc.lines.filterIsInstance<TodoLine.Task>().map { it.idea }
            val stats = WorkspaceStatsDto(
                total = tasks.size,
                pending = tasks.count { it.status == IdeaStatus.PENDING },
                inProgress = tasks.count { it.status == IdeaStatus.IN_PROGRESS },
                completed = tasks.count { it.status == IdeaStatus.COMPLETED },
                discarded = tasks.count { it.status == IdeaStatus.DISCARDED }
            )
            WorkspaceDto(
                id = ws.id,
                name = ws.name,
                stats = stats
            )
        }
        call.respond(workspaces)
    }

    // 3. POST /api/workspaces
    post("/api/workspaces") {
        val request = call.receive<CreateWorkspaceRequest>()
        if (request.name.isBlank()) {
            throw IllegalArgumentException("Workspace name cannot be empty")
        }
        val workspace = repository.createWorkspace(request.name)
        val dto = WorkspaceDto(
            id = workspace.id,
            name = workspace.name,
            stats = WorkspaceStatsDto(0, 0, 0, 0, 0)
        )
        call.respond(HttpStatusCode.Created, dto)
    }

    // 4. GET /api/workspaces/{id}/context
    get("/api/workspaces/{id}/context") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        val content = repository.getContextContent(ws)
        if (call.request.queryParameters["format"] == "raw") {
            call.respondText(content, ContentType.parse("text/markdown; charset=utf-8"))
        } else {
            call.respond(ContextDto(workspaceId = id, content = content))
        }
    }

    // 5. PUT /api/workspaces/{id}/context
    put("/api/workspaces/{id}/context") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val request = call.receive<ContextDto>()
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        repository.saveContextContent(ws, request.content)
        call.respond(HttpStatusCode.OK, ApiResponse(success = true))
    }

    // 6. GET /api/workspaces/{id}/todo
    get("/api/workspaces/{id}/todo") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        val doc = repository.getTodoDocument(ws)
        val rawMarkdown = todoParser.serialize(doc)
        val ideas = doc.lines.filterIsInstance<TodoLine.Task>().map { it.idea.toDto() }
        call.respond(TodoResponseDto(rawMarkdown = rawMarkdown, ideas = ideas))
    }

    // 7. POST /api/workspaces/{id}/ideas
    post("/api/workspaces/{id}/ideas") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val request = call.receive<CreateIdeaRequest>()
        if (request.text.isBlank()) {
            throw IllegalArgumentException("Idea text cannot be blank")
        }
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")

        val tagRegex = Regex("""(?<=^|\s)#([a-zA-Z0-9_-]+)""")
        val tags = tagRegex.findAll(request.text).map { it.groupValues[1] }.toSet()
        val cleanTitle = request.text.replace(tagRegex, " ").trim().replace(Regex("""\s+"""), " ")

        val doc = repository.getTodoDocument(ws)
        val updatedDoc = todoParser.addIdea(doc, cleanTitle, tags)
        repository.saveTodoDocument(ws, updatedDoc)

        val createdIdea = updatedDoc.lines.filterIsInstance<TodoLine.Task>().lastOrNull()?.idea
            ?: throw IllegalStateException("Failed to add idea")
        call.respond(HttpStatusCode.Created, createdIdea.toDto())
    }

    // 8. PATCH /api/workspaces/{id}/ideas/{ideaId}
    patch("/api/workspaces/{id}/ideas/{ideaId}") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val ideaId = call.parameters["ideaId"] ?: throw IllegalArgumentException("Missing ideaId")
        val request = call.receive<UpdateIdeaRequest>()

        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        val doc = repository.getTodoDocument(ws)
        val currentTask = doc.lines.filterIsInstance<TodoLine.Task>().find { it.idea.id == ideaId }
            ?: throw NoSuchElementException("Idea '$ideaId' not found")

        var updatedDoc = doc
        if (request.status != null) {
            val newStatus = parseIdeaStatus(request.status)
            updatedDoc = todoParser.updateIdeaStatus(updatedDoc, ideaId, newStatus)
        }
        if (request.title != null || request.tags != null) {
            val title = request.title ?: currentTask.idea.title
            val tags = request.tags?.toSet() ?: currentTask.idea.tags
            updatedDoc = todoParser.updateIdea(updatedDoc, ideaId, title, tags)
        }

        repository.saveTodoDocument(ws, updatedDoc)
        val updatedIdea = updatedDoc.lines.filterIsInstance<TodoLine.Task>().find { it.idea.id == ideaId }?.idea
        call.respond(HttpStatusCode.OK, ApiResponse(success = true, idea = updatedIdea?.toDto()))
    }

    // 9. DELETE /api/workspaces/{id}/ideas/{ideaId}
    delete("/api/workspaces/{id}/ideas/{ideaId}") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val ideaId = call.parameters["ideaId"] ?: throw IllegalArgumentException("Missing ideaId")

        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        val doc = repository.getTodoDocument(ws)
        val currentTask = doc.lines.filterIsInstance<TodoLine.Task>().find { it.idea.id == ideaId }
            ?: throw NoSuchElementException("Idea '$ideaId' not found")

        for (plan in currentTask.idea.plans) {
            val cleanFileName = File(plan.relativePath).name
            val planFile = File(File(ws.rootDir, "planes"), cleanFileName)
            if (planFile.exists()) {
                planFile.delete()
            }
        }

        val updatedDoc = todoParser.deleteIdea(doc, ideaId)
        repository.saveTodoDocument(ws, updatedDoc)
        call.respond(HttpStatusCode.OK, ApiResponse(success = true, message = "Idea deleted"))
    }

    // 10. POST /api/workspaces/{id}/plans
    post("/api/workspaces/{id}/plans") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val request = call.receive<IngestPlanRequest>()
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")
        val doc = repository.getTodoDocument(ws)
        val task = doc.lines.filterIsInstance<TodoLine.Task>().find { it.idea.id == request.ideaId }
            ?: throw NoSuchElementException("Idea '${request.ideaId}' not found")

        val filesToSave = mutableListOf<Pair<String, String>>()
        if (!request.files.isNullOrEmpty()) {
            for (f in request.files) {
                filesToSave.add(f.fileName to f.content)
            }
        } else if (!request.rawText.isNullOrBlank()) {
            val geminiParser = GeminiPlanParser()
            val parsed = geminiParser.parsePlans(request.rawText, task.idea.title)
            for (p in parsed) {
                filesToSave.add(p.fileName to p.content)
            }
        } else {
            throw IllegalArgumentException("Request must contain either files or rawText")
        }

        val createdFiles = mutableListOf<String>()
        val planLinks = mutableListOf<PlanLink>()
        for ((fileName, content) in filesToSave) {
            val cleanName = if (fileName.endsWith(".md", ignoreCase = true)) fileName else "$fileName.md"
            val savedFile = repository.savePlanFile(ws, cleanName, content)
            createdFiles.add(savedFile.name)
            val label = savedFile.name.removeSuffix(".md")
            planLinks.add(PlanLink(label = label, relativePath = "planes/${savedFile.name}"))
        }

        val combinedPlans = (task.idea.plans + planLinks).distinctBy { it.relativePath }
        val updatedDoc = todoParser.linkPlansToIdea(doc, task.idea.id, combinedPlans)
        repository.saveTodoDocument(ws, updatedDoc)

        call.respond(
            HttpStatusCode.Created,
            ApiResponse(success = true, createdFiles = createdFiles)
        )
    }

    // 11. GET /api/workspaces/{id}/plans/{fileName...}
    get("/api/workspaces/{id}/plans/{fileName...}") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val rawFileName = call.parameters.getAll("fileName")?.joinToString("/") ?: ""

        // Strict path traversal defense
        if (rawFileName.contains("..") || !rawFileName.endsWith(".md", ignoreCase = true)) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "Invalid plan file name"))
            return@get
        }

        val cleanName = File(rawFileName).name
        if (cleanName.contains("..") || !cleanName.endsWith(".md", ignoreCase = true)) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "Invalid plan file name"))
            return@get
        }

        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")

        val content = try {
            repository.readPlanFile(ws, "planes/$cleanName")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound, ApiResponse(success = false, message = e.message ?: "Plan file not found"))
            return@get
        }

        call.respondText(content, ContentType.parse("text/markdown; charset=utf-8"))
    }

    // 12. GET /api/workspaces/{id}/export
    get("/api/workspaces/{id}/export") {
        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing workspace id")
        val exportType = call.request.queryParameters["type"]
        val ws = repository.getWorkspaces().find { it.id == id }
            ?: throw NoSuchElementException("Workspace '$id' not found")

        val doc = repository.getTodoDocument(ws)
        val ideas = doc.lines.filterIsInstance<TodoLine.Task>().map { it.idea }

        when (exportType?.lowercase()) {
            "zip" -> {
                val tempZip = File.createTempFile("export-$id-", ".zip")
                try {
                    agentExportManager.createZipBundle(ws, ideas, tempZip)
                    val bytes = tempZip.readBytes()
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$id-export.zip\"")
                    call.respondBytes(bytes, ContentType.parse("application/zip"))
                } finally {
                    tempZip.delete()
                }
            }
            "md" -> {
                val markdown = agentExportManager.createUnifiedMarkdown(ws, ideas)
                call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
            }
            else -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(success = false, message = "Invalid export type. Supported types: zip, md")
                )
            }
        }
    }

    // 8. POST /api/system/update - Direct APK upload via Wi-Fi / Local PC
    post("/api/system/update") {
        val bytes = try {
            call.receive<ByteArray>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(success = false, message = "Error leyendo payload binario del APK: ${e.message}")
            )
            return@post
        }

        if (bytes.size < 4 || !AppUpdateManager.isValidApkHeader(bytes)) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(
                    success = false,
                    message = "El archivo recibido no es un APK válido (cabecera ZIP/APK inválida)."
                )
            )
            return@post
        }

        val result = appUpdateManager?.handleIncomingApk(bytes)
        if (result != null && result.isFailure) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse(
                    success = false,
                    message = "Error al procesar el archivo APK: ${result.exceptionOrNull()?.message}"
                )
            )
        } else {
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    message = "APK recibido con éxito (${bytes.size} bytes). Abriendo instalador en el dispositivo..."
                )
            )
        }
    }
}

private fun Idea.toDto(): IdeaDto = IdeaDto(
    id = id,
    title = title,
    status = status.name,
    tags = tags.toList(),
    plans = plans.map { PlanLinkDto(label = it.label, relativePath = it.relativePath) }
)

private fun parseIdeaStatus(statusStr: String): IdeaStatus {
    return try {
        IdeaStatus.valueOf(statusStr.trim().uppercase())
    } catch (e: Exception) {
        IdeaStatus.entries.find { it.marker == statusStr.trim() }
            ?: throw IllegalArgumentException("Invalid idea status: $statusStr")
    }
}
