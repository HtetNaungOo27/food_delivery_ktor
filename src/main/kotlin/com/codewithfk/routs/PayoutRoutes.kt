package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.PayoutService
import com.codewithfk.utils.requireRole
import com.codewithfk.utils.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.payoutRoutes() = route("/payouts") {
    get { val id = call.requireRole(UserRole.OWNER, UserRole.RIDER) ?: return@get; call.respond(PayoutService.overview(id)) }
    put("/account") { val id = call.requireRole(UserRole.OWNER, UserRole.RIDER) ?: return@put; runCatching { PayoutService.saveAccount(id, call.receive()) }.onSuccess { call.respond(mapOf("success" to true)) }.onFailure { call.respondError(HttpStatusCode.BadRequest, it.message ?: "Invalid bank account") } }
    post { val id = call.requireRole(UserRole.OWNER, UserRole.RIDER) ?: return@post; runCatching { PayoutService.request(id, call.receive<RequestPayout>().amount) }.onSuccess { call.respond(it) }.onFailure { call.respondError(HttpStatusCode.BadRequest, it.message ?: "Payout unavailable") } }
}
