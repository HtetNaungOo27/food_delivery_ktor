package com.codewithfk.routs

import com.codewithfk.services.CustomerProfileService
import com.codewithfk.utils.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID

fun Route.customerProfileRoutes() {
    get("/customer/profile") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
        try {
            call.respond(CustomerProfileService.getProfile(UUID.fromString(userId)))
        } catch (error: IllegalStateException) {
            call.respondError(HttpStatusCode.NotFound, error.message ?: "Customer not found")
        }
    }
}
