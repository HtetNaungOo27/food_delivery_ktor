package com.codewithfk.routs


import com.codewithfk.model.MenuItem
import com.codewithfk.services.MenuItemService
import com.codewithfk.utils.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import kotlin.text.get

fun Route.menuItemRoutes() {
    route("/restaurants/{id}/menu") {
        /**
         * Fetch all menu items for a restaurant
         */
        get {
            val restaurantId = call.parameters["id"] ?: return@get call.respondError(
                "Restaurant ID is required.",
                HttpStatusCode.BadRequest
            )
            val menuItems = MenuItemService.getMenuItemsByRestaurant(UUID.fromString(restaurantId))
            call.respond(mapOf("foodItems" to menuItems))
        }

    }
}
