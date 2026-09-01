package com.codewithfk.routs

import com.codewithfk.services.CustomerProfileService
import com.codewithfk.model.UpdateCustomerProfileRequest
import com.codewithfk.model.FavoriteUpdateRequest
import com.codewithfk.model.FavoriteIdsResponse
import com.codewithfk.utils.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.request.receive
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
    put("/customer/profile") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@put call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
        try {
            CustomerProfileService.updateProfile(UUID.fromString(userId), call.receive<UpdateCustomerProfileRequest>().name)
            call.respond(mapOf("message" to "Profile updated"))
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, error.message ?: "Invalid profile")
        }
    }
    get("/customer/favorites") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
        call.respond(FavoriteIdsResponse(CustomerProfileService.favoriteIds(UUID.fromString(userId))))
    }
    put("/customer/favorites/{menuItemId}") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@put call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
        val menuItemId = runCatching { UUID.fromString(call.parameters["menuItemId"]) }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "Invalid menu item")
        try {
            CustomerProfileService.setFavorite(UUID.fromString(userId), menuItemId, call.receive<FavoriteUpdateRequest>().favorite)
            call.respond(mapOf("success" to true))
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.NotFound, error.message ?: "Menu item not found")
        }
    }
}
