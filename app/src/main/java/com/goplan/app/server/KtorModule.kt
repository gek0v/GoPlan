package com.goplan.app.server

import com.goplan.app.data.preferences.PreferencesManager
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.domain.export.AgentExportManager
import com.goplan.app.server.dto.ApiResponse
import com.goplan.app.server.routes.registerServerRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException

fun Application.configureServer(
    repository: WorkspaceRepository,
    preferencesManager: PreferencesManager,
    agentExportManager: AgentExportManager? = null,
    getIpAddress: () -> String = { "127.0.0.1" },
    port: Int = 8080
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Requested-With")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(success = false, message = cause.message ?: "Bad request")
            )
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(success = false, message = cause.message ?: "Resource not found")
            )
        }
        exception<FileNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(success = false, message = cause.message ?: "File not found")
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is io.ktor.server.plugins.BadRequestException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(success = false, message = cause.message ?: "Bad request")
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse(success = false, message = cause.message ?: "Internal server error")
                )
            }
        }
    }

    val exportManager = agentExportManager ?: AgentExportManager(repository)
    routing {
        registerServerRoutes(
            repository = repository,
            preferencesManager = preferencesManager,
            agentExportManager = exportManager,
            getIpAddress = getIpAddress,
            port = port
        )
    }
}
