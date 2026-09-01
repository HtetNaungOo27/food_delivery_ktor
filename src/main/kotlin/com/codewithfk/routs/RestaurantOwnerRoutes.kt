package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.OrderService
import com.codewithfk.services.RestaurantOwnerService
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

fun Route.restaurantOwnerRoutes() {
    route("/restaurant-owner") {
        authenticate {
            // Get restaurant orders
            get("/orders") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                
                val status = call.request.queryParameters["status"]
                val orders = RestaurantOwnerService.getRestaurantOrders(
                    ownerId,
                    status
                )
                call.respond(mapOf("orders" to orders))
            }

            // Get restaurant statistics
            get("/statistics") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                
                val stats = RestaurantOwnerService.getRestaurantStatistics(ownerId)
                call.respond(stats)
            }

            // Get restaurant profile
            get("/profile") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                
                val restaurant = RestaurantOwnerService.getRestaurantDetails(ownerId)
                if (restaurant != null) {
                    call.respond(restaurant)
                } else {
                    call.respondError(HttpStatusCode.NotFound, "Restaurant not found")
                }
            }

            // Update restaurant profile
            put("/profile") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@put
                
                val request = call.receive<UpdateRestaurantRequest>()
                val success = RestaurantOwnerService.updateRestaurantProfile(
                    ownerId,
                    request
                )
                
                if (success) {
                    call.respond(mapOf("message" to "Restaurant profile updated successfully"))
                } else {
                    call.respondError(HttpStatusCode.NotFound, "Restaurant not found")
                }
            }

            get("/hours") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                call.respond(UpdateRestaurantHoursRequest(RestaurantOwnerService.getRestaurantHours(ownerId)))
            }

            put("/hours") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@put
                try {
                    val updated = RestaurantOwnerService.updateRestaurantHours(ownerId, call.receive())
                    if (updated) call.respond(mapOf("message" to "Weekly hours updated"))
                    else call.respondError(HttpStatusCode.NotFound, "Restaurant not found")
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid weekly hours")
                }
            }

            get("/menu") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                call.respond(mapOf("foodItems" to RestaurantOwnerService.getOwnedMenuItems(ownerId)))
            }

            post("/menu") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@post
                val request = call.receive<MenuItem>()
                if (request.name.isBlank() || request.description.isNullOrBlank() || request.price <= 0.0) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "Enter a valid name, description, and price")
                }
                val itemId = RestaurantOwnerService.addOwnedMenuItem(ownerId, request)
                call.respond(HttpStatusCode.Created, mapOf("id" to itemId.toString(), "message" to "Menu item added successfully"))
            }

            patch("/menu/{itemId}") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@patch
                val itemId = call.parameters["itemId"]
                    ?: return@patch call.respondError(HttpStatusCode.BadRequest, "Menu item ID is required")
                val request = call.receive<UpdateMenuItemRequest>()
                if (request.name?.isBlank() == true || request.description?.isBlank() == true || request.price?.let { it <= 0 } == true) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "Enter a valid name, description, and price")
                }
                val updated = RestaurantOwnerService.updateOwnedMenuItem(
                    ownerId, UUID.fromString(itemId), request
                )
                if (updated) call.respond(mapOf("message" to "Menu item updated successfully"))
                else call.respondError(HttpStatusCode.NotFound, "Menu item not found for this restaurant")
            }

            delete("/menu/{itemId}") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@delete
                val itemId = call.parameters["itemId"]
                    ?: return@delete call.respondError(HttpStatusCode.BadRequest, "Menu item ID is required")
                val deleted = RestaurantOwnerService.deleteOwnedMenuItem(ownerId, UUID.fromString(itemId))
                if (deleted) call.respond(mapOf("message" to "Menu item deleted successfully"))
                else call.respondError(HttpStatusCode.NotFound, "Menu item not found for this restaurant")
            }

            // Accept/Reject order
            post("/orders/{orderId}/action") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@post
                
                val orderId = call.parameters["orderId"] ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )
                
                val request = call.receive<OrderActionRequest>()
                
                try {
                    OrderService.handleOrderAction(
                        orderId = UUID.fromString(orderId),
                        ownerId = ownerId,
                        action = request.action,
                        reason = request.reason
                    )
                    call.respond(mapOf("message" to "Order ${request.action.lowercase()} successfully"))
                } catch (e: IllegalStateException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Error processing order action")
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid order action")
                }
            }

            // Update order status
            get("/orders/{orderId}") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@get
                val orderId = call.parameters["orderId"]
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "Order ID is required")
                try {
                    call.respond(OrderService.getOrderDetailsForOwner(UUID.fromString(orderId), ownerId))
                } catch (e: IllegalStateException) {
                    call.respondError(HttpStatusCode.NotFound, e.message ?: "Order not found")
                }
            }

            patch("/orders/{orderId}/status") {
                val ownerId = call.requireRole(UserRole.OWNER) ?: return@patch
                
                val orderId = call.parameters["orderId"] ?: return@patch call.respondError(
                    HttpStatusCode.BadRequest,
                    "Order ID is required"
                )
                
                val request = call.receive<UpdateOrderStatusRequest>()
                
                try {
                    OrderService.transitionOwnerOrder(ownerId, UUID.fromString(orderId), request.status, request.preparationMinutes)
                    call.respond(mapOf("message" to "Order status updated successfully"))
                } catch (e: IllegalStateException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Error updating order status")
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid order transition")
                }
            }
        }
    }
}
