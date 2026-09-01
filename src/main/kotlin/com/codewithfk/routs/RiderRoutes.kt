package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.RiderService
import com.codewithfk.utils.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import com.codewithfk.utils.requireRole

fun Route.riderRoutes() {
    route("/rider") {
        authenticate {
            get("/availability") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@get
                call.respond(mapOf("available" to RiderService.isAvailable(riderId)))
            }

            put("/availability") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@put
                val available = call.receive<Map<String, Boolean>>()["available"]
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "Availability is required")
                try {
                    RiderService.setAvailability(riderId, available)
                    call.respond(mapOf("message" to if (available) "You are online" else "You are offline"))
                } catch (e: IllegalStateException) {
                    call.respondError(HttpStatusCode.Conflict, e.message ?: "Availability could not be updated")
                }
            }
            // Update rider location
            post("/location") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@post

                val location = call.receive<Location>()
                RiderService.updateRiderLocation(
                    riderId,
                    location.latitude,
                    location.longitude
                )
                call.respond(mapOf("message" to "Location updated successfully"))
            }

            // Accept delivery request
            post("/deliveries/{orderId}/accept") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@post

                val orderId = call.parameters["orderId"] ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )

                val accepted = RiderService.acceptDeliveryRequest(
                    riderId,
                    UUID.fromString(orderId)
                )

                if (accepted) {
                    call.respond(mapOf("message" to "Delivery request accepted"))
                } else {
                    call.respondError(HttpStatusCode.BadRequest, "Failed to accept delivery request. Order may have been taken by another rider.")
                }
            }

            // Get delivery path
            get("/deliveries/{orderId}/path") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@get

                val orderId = call.parameters["orderId"] ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )

                val path = RiderService.getDeliveryPath(
                    riderId,
                    UUID.fromString(orderId)
                )
                call.respond(path)
            }

            // Get available deliveries
            get("/deliveries/available") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@get

                val deliveries = RiderService.getAvailableDeliveries(riderId)
                call.respond(mapOf("data" to  deliveries))
            }

            // Reject delivery request
            post("/deliveries/{orderId}/reject") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@post

                val orderId = call.parameters["orderId"] ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )

                val rejected = RiderService.rejectDeliveryRequest(
                    riderId,
                    UUID.fromString(orderId)
                )

                if (rejected) {
                    call.respond(mapOf("message" to "Delivery request rejected"))
                } else {
                    call.respondError(HttpStatusCode.BadRequest, "Failed to reject delivery request. Order may not be available anymore.")
                }
            }

            // Update delivery status
            post("/deliveries/{orderId}/status") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@post

                val orderId = call.parameters["orderId"] ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )

                val statusUpdate = call.receive<DeliveryStatusUpdate>()
                
                try {
                    val updated = RiderService.updateDeliveryStatus(
                        riderId,
                        UUID.fromString(orderId),
                        statusUpdate
                    )

                    if (updated) {
                        call.respond(mapOf("message" to "Delivery status updated successfully"))
                    } else {
                        call.respondError(HttpStatusCode.BadRequest, "Failed to update delivery status")
                    }
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid status update")
                } catch (e: IllegalStateException) {
                    call.respondError(HttpStatusCode.NotFound, e.message ?: "Order not found")
                }
            }

            // Get active deliveries (assigned to this rider)
            get("/deliveries/active") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@get

                val activeDeliveries = RiderService.getActiveDeliveries(riderId)
                call.respond(mapOf("data" to activeDeliveries))
            }

            get("/wallet") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@get
                call.respond(RiderService.getWallet(riderId))
            }

            post("/wallet/settle") {
                val riderId = call.requireRole(UserRole.RIDER) ?: return@post
                val settled = RiderService.settleWallet(riderId)
                call.respond(mapOf("message" to if (settled) "Wallet settled" else "No cash to settle"))
            }
        }
    }
}
