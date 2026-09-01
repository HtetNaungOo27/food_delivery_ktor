package com.codewithfk.services

import com.codewithfk.database.*
import com.codewithfk.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object PayoutService {
    private fun mask(value: String) = "•••• ${value.takeLast(4)}"
    private fun fingerprint(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun overview(userId: UUID): PayoutOverview = transaction {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: error("Account not found")
        require(user[UsersTable.role].uppercase() in setOf("OWNER", "RESTAURANT", "RIDER")) { "Payouts are not available for this account" }
        val accountRow = PayoutAccountsTable.select { PayoutAccountsTable.userId eq userId }.singleOrNull()
        val history = PayoutsTable.select { PayoutsTable.userId eq userId }.orderBy(PayoutsTable.requestedAt, SortOrder.DESC).map(::toItem)
        val available = calculateAvailableBalance(userId, user, history)
        PayoutOverview(
            accountRow?.let { PayoutAccount(it[PayoutAccountsTable.bankName], it[PayoutAccountsTable.accountName], it[PayoutAccountsTable.accountNumberMasked]) },
            available, history
        )
    }

    private fun calculateAvailableBalance(userId: UUID, user: ResultRow, history: List<PayoutItem>): Double {
        val earned = if (user[UsersTable.role].equals("RIDER", true)) {
            OrdersTable.select { (OrdersTable.riderId eq userId) and (OrdersTable.status eq "DELIVERED") }.sumOf { 1.5 + it[OrdersTable.totalAmount] * .03 }
        } else {
            val restaurantId = RestaurantsTable.select { RestaurantsTable.ownerId eq userId }.singleOrNull()?.get(RestaurantsTable.id)
            if (restaurantId == null) 0.0 else OrdersTable.select { (OrdersTable.restaurantId eq restaurantId) and (OrdersTable.status eq "DELIVERED") }
                .sumOf { it[OrdersTable.totalAmount] - it[OrdersTable.commissionAmount] }
        }
        val reserved = history.filter { it.status in setOf("PENDING", "PROCESSING", "PAID") }.sumOf { it.amount }
        return (earned - reserved).coerceAtLeast(0.0)
    }

    fun saveAccount(userId: UUID, request: SavePayoutAccountRequest) = transaction {
        val number = request.accountNumber.filterNot(Char::isWhitespace)
        require(request.bankName.trim().length >= 2 && request.accountName.trim().length >= 2 && number.length in 6..32) { "Enter valid bank account details" }
        PayoutAccountsTable.deleteWhere { PayoutAccountsTable.userId eq userId }
        PayoutAccountsTable.insert { it[this.userId] = userId; it[bankName] = request.bankName.trim(); it[accountName] = request.accountName.trim(); it[accountNumberMasked] = mask(number); it[accountFingerprint] = fingerprint(number) }
    }

    fun request(userId: UUID, amount: Double): PayoutItem = transaction {
        require(PayoutAccountsTable.select { PayoutAccountsTable.userId eq userId }.any()) { "Add a bank account first" }
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: error("Account not found")
        val history = PayoutsTable.select { PayoutsTable.userId eq userId }.map(::toItem)
        val available = calculateAvailableBalance(userId, user, history)
        require(amount > 0 && amount <= available) { "Amount exceeds available balance" }
        val id = UUID.randomUUID()
        PayoutsTable.insert { it[this.id] = id; it[this.userId] = userId; it[this.amount] = amount }
        PayoutsTable.select { PayoutsTable.id eq id }.single().let(::toItem)
    }

    fun all(status: String?): List<Pair<String, PayoutItem>> = transaction {
        (PayoutsTable innerJoin UsersTable).selectAll().filter { status.isNullOrBlank() || it[PayoutsTable.status].equals(status, true) }
            .map { it[UsersTable.name] to toItem(it) }
    }

    fun process(id: UUID, request: ProcessPayoutRequest) = transaction {
        val status = request.status.uppercase(); require(status in setOf("PROCESSING", "PAID", "REJECTED")) { "Invalid payout status" }
        val current = PayoutsTable.select { PayoutsTable.id eq id }.singleOrNull() ?: error("Payout not found")
        require(current[PayoutsTable.status] in setOf("PENDING", "PROCESSING")) { "Payout is already final" }
        PayoutsTable.update({ PayoutsTable.id eq id }) { it[this.status] = status; it[reference] = request.reference?.trim(); if (status in setOf("PAID", "REJECTED")) it[processedAt] = CurrentDateTime }
    }

    private fun toItem(row: ResultRow) = PayoutItem(row[PayoutsTable.id].toString(), row[PayoutsTable.amount], row[PayoutsTable.status], row[PayoutsTable.reference], row[PayoutsTable.requestedAt].toString(), row[PayoutsTable.processedAt]?.toString())
}
