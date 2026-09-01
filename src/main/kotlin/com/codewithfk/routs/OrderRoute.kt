package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.OrderService
import com.codewithfk.utils.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import kotlin.text.get

fun Route.orderRoutes() {
    route("/orders") {

        /**
         * Place an order
         */
        post {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")

            try {
                val request = call.receive<PlaceOrderRequest>()
                if (!request.paymentMethod.equals("COD", ignoreCase = true)) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "Card orders must be completed through verified payment confirmation")
                }
                if (request.idempotencyKey.isNullOrBlank()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "Idempotency key is required")
                }
                val orderId = OrderService.placeOrder(UUID.fromString(userId), request)
                call.respond(mapOf("id" to orderId.toString(), "message" to "Order placed successfully"))
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.BadRequest, e.message ?: "Error placing order")
            }
        }

        /**
         * Fetch all orders for the logged-in user
         */
        get {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Unauthorized.")
            val orders = OrderService.getOrdersByUser(UUID.fromString(userId))
            call.respond(mapOf("orders" to orders))
        }

        /**
         * Fetch details of a specific order
         */
        get("/{id}") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
            val orderId = call.parameters["id"] ?: return@get call.respondError(
                HttpStatusCode.BadRequest,
                "Order ID is required."
            )
            try {
                val order = OrderService.getOrderDetailsForCustomer(UUID.fromString(orderId), UUID.fromString(userId))
                call.respond(order)
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.NotFound, e.message ?: "Order not found")
            }
        }

        post("/{id}/cancel") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
            val orderId = call.parameters["id"]
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "Order ID is required")
            try {
                call.respond(OrderService.cancelCustomerOrder(UUID.fromString(orderId), UUID.fromString(userId)))
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.Conflict, e.message ?: "Order cannot be cancelled")
            }
        }

        post("/{id}/reorder") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
            val orderId = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "Order ID is required")
            try {
                val count = OrderService.reorder(UUID.fromString(orderId), UUID.fromString(userId))
                call.respond(mapOf("message" to "$count items added to cart"))
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.Conflict, e.message ?: "Unable to reorder")
            } catch (e: IllegalArgumentException) {
                call.respondError(HttpStatusCode.Conflict, e.message ?: "Unable to reorder")
            }
        }

        get("/{id}/issue") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
            val orderId = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "Order ID is required")
            try {
                call.respond(OrderIssueResponse(OrderService.getCustomerIssue(UUID.fromString(orderId), UUID.fromString(userId))))
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.NotFound, e.message ?: "Order not found")
            }
        }

        post("/{id}/issue") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "Unauthorized")
            val orderId = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "Order ID is required")
            try {
                val request = call.receive<CreateOrderIssueRequest>()
                call.respond(HttpStatusCode.Created, OrderService.createCustomerIssue(UUID.fromString(orderId), UUID.fromString(userId), request.type, request.description))
            } catch (e: IllegalArgumentException) {
                call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid issue")
            } catch (e: IllegalStateException) {
                call.respondError(HttpStatusCode.NotFound, e.message ?: "Order not found")
            }
        }

    }
}
