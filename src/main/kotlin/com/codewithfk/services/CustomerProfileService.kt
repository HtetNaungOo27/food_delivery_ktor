package com.codewithfk.services

import com.codewithfk.database.AddressesTable
import com.codewithfk.database.OrdersTable
import com.codewithfk.database.UsersTable
import com.codewithfk.model.CustomerProfile
import com.codewithfk.model.OrderStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.math.floor

object CustomerProfileService {
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
