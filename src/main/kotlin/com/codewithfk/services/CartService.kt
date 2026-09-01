package com.codewithfk.services

import com.codewithfk.database.CartTable
import com.codewithfk.database.MenuItemsTable
import com.codewithfk.database.RestaurantsTable
import com.codewithfk.model.CartItem
import com.codewithfk.model.MenuItem
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import com.codewithfk.model.SelectedModifier
import com.codewithfk.model.MenuModifierGroup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object CartService {

    fun getCartItems(userId: UUID): List<CartItem> {
        return transaction {
            (CartTable innerJoin MenuItemsTable)
                .select { CartTable.userId eq userId }
                .map {
                    CartItem(
                        id = it[CartTable.id].toString(),
                        userId = it[CartTable.userId].toString(),
                        restaurantId = it[CartTable.restaurantId].toString(),
                        menuItemId = MenuItem(
                            id = it[MenuItemsTable.id].toString(),
                            name = it[MenuItemsTable.name],
                            description = it[MenuItemsTable.description],
                            price = it[MenuItemsTable.price],
                            restaurantId = it[MenuItemsTable.restaurantId].toString(),
                            imageUrl = it[MenuItemsTable.imageUrl]
                        ),
                        quantity = it[CartTable.quantity],
                        addedAt = it[CartTable.addedAt].toString(),
                        selectedModifiers = runCatching { Json.decodeFromString<List<SelectedModifier>>(it[CartTable.selectedModifiersJson]) }.getOrDefault(emptyList())
                    )
                }
        }
    }

    fun addToCart(userId: UUID, restaurantId: UUID, menuItemId: UUID, quantity: Int, selectedModifiers: List<SelectedModifier> = emptyList()): UUID {
        return transaction {
            require(quantity in 1..99) { "Quantity must be between 1 and 99" }
            val menuItem = (MenuItemsTable innerJoin RestaurantsTable).select {
                (MenuItemsTable.id eq menuItemId) and
                    (MenuItemsTable.restaurantId eq restaurantId) and
                    (MenuItemsTable.isAvailable eq true) and
                    (RestaurantsTable.isOpen eq true)
            }.singleOrNull() ?: throw IllegalStateException("Menu item is unavailable or does not belong to this restaurant")
            val existingQuantity = CartTable.select { (CartTable.userId eq userId) and (CartTable.menuItemId eq menuItemId) }
                .singleOrNull()?.get(CartTable.quantity) ?: 0
            require(existingQuantity + quantity <= menuItem[MenuItemsTable.inventoryQuantity]) { "Only ${menuItem[MenuItemsTable.inventoryQuantity]} portions are available" }
            val groups = runCatching { Json.decodeFromString<List<MenuModifierGroup>>(menuItem[MenuItemsTable.modifiersJson]) }.getOrDefault(emptyList())
            groups.forEach { group ->
                val chosen = selectedModifiers.filter { it.group.equals(group.name, true) }
                require(!group.required || chosen.isNotEmpty()) { "Choose ${group.name}" }
                require(chosen.size <= group.maxSelections) { "Choose no more than ${group.maxSelections} for ${group.name}" }
                require(chosen.all { selection -> group.options.any { it.name.equals(selection.option, true) } }) { "Invalid modifier selection" }
            }

            val otherRestaurant = CartTable.select {
                (CartTable.userId eq userId) and (CartTable.restaurantId neq restaurantId)
            }.any()
            check(!otherRestaurant) { "Your cart already contains items from another restaurant" }

            // Atomic increment avoids lost updates when the user double-taps.
            val incremented = CartTable.update({
                (CartTable.userId eq userId) and (CartTable.menuItemId eq menuItemId)
            }) {
                it[CartTable.quantity] = CartTable.quantity + quantity
            }
            if (incremented == 0) {
                val inserted = CartTable.insertIgnore {
                    it[this.userId] = userId
                    it[this.restaurantId] = restaurantId
                    it[this.menuItemId] = menuItemId
                    it[this.quantity] = quantity
                    it[this.selectedModifiersJson] = Json.encodeToString(selectedModifiers)
                }
                // A concurrent request may have inserted between our UPDATE and INSERT.
                if (inserted.insertedCount == 0) {
                    CartTable.update({
                        (CartTable.userId eq userId) and (CartTable.menuItemId eq menuItemId)
                    }) { it[CartTable.quantity] = CartTable.quantity + quantity }
                }
            }
            CartTable.select {
                (CartTable.userId eq userId) and (CartTable.menuItemId eq menuItemId)
            }.single()[CartTable.id]
        }
    }

    fun updateCartItemQuantity(userId: UUID, cartItemId: UUID, quantity: Int): Boolean {
        return transaction {
            require(quantity in 1..99) { "Quantity must be between 1 and 99" }
            val cart = CartTable.select { (CartTable.id eq cartItemId) and (CartTable.userId eq userId) }.singleOrNull() ?: return@transaction false
            val stock = MenuItemsTable.select { MenuItemsTable.id eq cart[CartTable.menuItemId] }.single()[MenuItemsTable.inventoryQuantity]
            require(quantity <= stock) { "Only $stock portions are available" }
            CartTable.update({ (CartTable.id eq cartItemId) and (CartTable.userId eq userId) }) {
                it[this.quantity] = quantity
            } > 0
        }
    }

    fun removeCartItem(userId: UUID, cartItemId: UUID): Boolean {
        return transaction {
            CartTable.deleteWhere { (CartTable.id eq cartItemId) and (CartTable.userId eq userId) } > 0
        }
    }

    fun clearCart(userId: UUID): Boolean {
        return transaction {
            CartTable.deleteWhere { CartTable.userId eq userId } > 0
        }
    }
}
