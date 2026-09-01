package com.codewithfk.services

import com.codewithfk.database.AddressesTable
import com.codewithfk.database.OrdersTable
import com.codewithfk.database.UsersTable
import com.codewithfk.database.CustomerFavoritesTable
import com.codewithfk.database.MenuItemsTable
import com.codewithfk.model.CustomerProfile
import com.codewithfk.model.OrderStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.math.floor

object CustomerProfileService {
    fun favoriteIds(userId: UUID): List<String> = transaction {
        CustomerFavoritesTable.select { CustomerFavoritesTable.userId eq userId }
            .map { it[CustomerFavoritesTable.menuItemId].toString() }
    }

    fun setFavorite(userId: UUID, menuItemId: UUID, favorite: Boolean) = transaction {
        require(MenuItemsTable.select { MenuItemsTable.id eq menuItemId }.any()) { "Menu item not found" }
        if (favorite) CustomerFavoritesTable.insertIgnore {
            it[CustomerFavoritesTable.userId] = userId
            it[CustomerFavoritesTable.menuItemId] = menuItemId
        } else CustomerFavoritesTable.deleteWhere {
            (CustomerFavoritesTable.userId eq userId) and (CustomerFavoritesTable.menuItemId eq menuItemId)
        }
    }

    fun updateProfile(userId: UUID, name: String) = transaction {
        val normalized = name.trim()
        require(normalized.length in 2..80) { "Name must contain 2 to 80 characters" }
        check(UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.name] = normalized } == 1) {
            "Customer not found"
        }
    }

    fun getProfile(userId: UUID): CustomerProfile = transaction {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: throw IllegalStateException("Customer not found")

        val delivered = OrdersTable.select {
            (OrdersTable.userId eq userId) and (OrdersTable.status eq OrderStatus.DELIVERED.name)
        }.toList()
        val totalSpent = delivered.sumOf { it[OrdersTable.totalAmount] }
        val addressCount = AddressesTable.select { AddressesTable.userId eq userId }.count().toInt()

        CustomerProfile(
            id = user[UsersTable.id].toString(),
            name = user[UsersTable.name],
            email = user[UsersTable.email],
            memberSince = user[UsersTable.createdAt].toString(),
            completedOrders = delivered.size,
            totalSpent = totalSpent,
            bitePoints = floor(totalSpent).toInt(),
            savedAddresses = addressCount
        )
    }
}
