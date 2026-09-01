package com.codewithfk.routs

import com.codewithfk.model.ChangePasswordRequest
import com.codewithfk.model.UpdateAccountRequest
import com.codewithfk.services.AccountService
import com.codewithfk.utils.requireRole
import com.codewithfk.utils.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.accountRoutes() {
    route("/account") {
        get {
            val userId = call.requireRole() ?: return@get
            runCatching { AccountService.get(userId) }
                .onSuccess { call.respond(it) }
                .onFailure { call.respondError(HttpStatusCode.NotFound, it.message ?: "Account not found") }
        }
        put {
            val userId = call.requireRole() ?: return@put
            try {
                call.respond(AccountService.update(userId, call.receive<UpdateAccountRequest>()))
            } catch (error: IllegalArgumentException) {
                call.respondError(HttpStatusCode.BadRequest, error.message ?: "Invalid account details")
            }
        }
        put("/password") {
            val userId = call.requireRole() ?: return@put
            try {
                AccountService.changePassword(userId, call.receive<ChangePasswordRequest>())
                call.respond(mapOf("message" to "Password changed"))
            } catch (error: IllegalArgumentException) {
                call.respondError(HttpStatusCode.BadRequest, error.message ?: "Password could not be changed")
            }
        }
    }
}
