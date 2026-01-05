package com.devil.phoenixproject.portal.routes

import com.devil.phoenixproject.portal.auth.AuthService
import com.devil.phoenixproject.portal.models.*
import com.devil.phoenixproject.portal.sync.SyncService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.syncRoutes(authService: AuthService, syncService: SyncService) {
    route("/api/sync") {

        // Get sync status
        get("/status") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@get
            }

            try {
                val status = syncService.getStatus(userId)
                call.respond(status)
            } catch (e: Exception) {
                call.application.log.error("Sync status error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get sync status"))
            }
        }

        // Push changes to server
        post("/push") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            try {
                val request = call.receive<SyncPushRequest>()
                val response = syncService.push(userId, request)
                call.respond(response)
            } catch (e: Exception) {
                call.application.log.error("Sync push error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to push sync data"))
            }
        }

        // Pull changes from server
        post("/pull") {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            try {
                val request = call.receive<SyncPullRequest>()
                val response = syncService.pull(userId, request)
                call.respond(response)
            } catch (e: Exception) {
                call.application.log.error("Sync pull error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to pull sync data"))
            }
        }
    }

    // MetricSamples endpoints (lazy load)
    route("/api/sessions/{sessionId}/metrics") {

        get {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@get
            }

            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@get
            }

            // TODO: Implement metric sample retrieval
            call.respond(MetricSamplesResponse(sessionId, emptyList()))
        }

        post {
            val userId = authService.extractUserId(call) ?: run {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing token"))
                return@post
            }

            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@post
            }

            try {
                val request = call.receive<MetricSamplesUploadRequest>()
                // TODO: Implement metric sample upload
                call.respond(HttpStatusCode.Created, mapOf("uploaded" to request.samples.size))
            } catch (e: Exception) {
                call.application.log.error("Metrics upload error", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to upload metrics"))
            }
        }
    }
}
