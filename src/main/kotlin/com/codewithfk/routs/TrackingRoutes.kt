package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.TrackingService
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import com.codewithfk.services.AuthService
import com.codewithfk.services.OrderService
import com.codewithfk.model.UserRole

fun Route.trackingRoutes() {
    webSocket("/track/{orderId}") {
        try {
            val orderId = call.parameters["orderId"] ?: throw IllegalArgumentException("Order ID required")
            val actorId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?.let(UUID::fromString) ?: throw IllegalAccessException("Unauthorized")
            val role = AuthService.getRole(actorId) ?: throw IllegalAccessException("Unknown account role")
            val orderUuid = UUID.fromString(orderId)
            if (!OrderService.canAccessTracking(orderUuid, actorId, role)) {
                throw IllegalAccessException("Not allowed to track this order")
            }
            val sessionId = UUID.randomUUID().toString()

            // Start tracking session
            val session = TrackingService.Session(
                sessionId = sessionId,
                socket = this,
                role = role.name
            )
            TrackingService.startTracking(orderId, session)

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val locationUpdate = Json.decodeFromString<LocationUpdate>(frame.readText())
                        if (role != UserRole.RIDER || !OrderService.isAssignedActiveRider(orderUuid, actorId)) {
                            throw IllegalAccessException("Only the assigned active rider may publish location")
                        }
                        require(locationUpdate.orderId == orderId) { "Order ID mismatch" }
                        require(locationUpdate.latitude.isFinite() && locationUpdate.latitude in -90.0..90.0) { "Invalid latitude" }
                        require(locationUpdate.longitude.isFinite() && locationUpdate.longitude in -180.0..180.0) { "Invalid longitude" }
                        TrackingService.updateLocation(
                            locationUpdate.copy(riderId = actorId.toString(), orderId = orderId)
                        )
                    }
                }
            } finally {
                TrackingService.stopTracking(orderId, sessionId)
            }
        } catch (e: Exception) {
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, e.message ?: "Error"))
        }
    }
}
