package com.codewithfk.services

import com.codewithfk.database.OrdersTable
import com.codewithfk.database.RestaurantsTable
import com.codewithfk.database.UsersTable
import com.codewithfk.model.*
import com.codewithfk.database.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object AdminService {
    fun summary(): AdminSummary = transaction {
        val users = UsersTable.selectAll().toList()
        val orders = OrdersTable.selectAll().toList()
        val terminal = setOf("DELIVERED", "CANCELLED", "DELIVERY_FAILED", "REFUNDED")
        AdminSummary(
            customers = users.count { it[UsersTable.role].equals("CUSTOMER", true) }.toLong(),
            owners = users.count { it[UsersTable.role].equals("OWNER", true) }.toLong(),
            riders = users.count { it[UsersTable.role].equals("RIDER", true) }.toLong(),
            restaurants = RestaurantsTable.selectAll().count(),
            orders = orders.size.toLong(),
            activeOrders = orders.count { it[OrdersTable.status].uppercase() !in terminal }.toLong(),
            deliveredOrders = orders.count { it[OrdersTable.status].equals("DELIVERED", true) }.toLong(),
            grossMerchandiseValue = orders.filter { it[OrdersTable.status].equals("DELIVERED", true) }
                .sumOf { it[OrdersTable.totalAmount] }
        )
    }

    fun users(limit: Int = 100, offset: Long = 0, role: String? = null): List<AdminUserItem> = transaction {
        UsersTable.selectAll().let { query -> if (!role.isNullOrBlank()) query.andWhere { UsersTable.role.lowerCase() eq role.lowercase() }; query }
            .orderBy(UsersTable.createdAt, SortOrder.DESC).limit(limit, offset).map {
            AdminUserItem(
                id = it[UsersTable.id].toString(),
                name = it[UsersTable.name],
                email = it[UsersTable.email],
                role = it[UsersTable.role].uppercase(),
                isActive = it[UsersTable.isActive],
                createdAt = it[UsersTable.createdAt].toString()
            )
        }
    }

    fun orders(limit: Int = 100, offset: Long = 0, status: String? = null): List<AdminOrderItem> = transaction {
        val customer = UsersTable.alias("customer")
        OrdersTable
            .join(customer, JoinType.INNER, OrdersTable.userId, customer[UsersTable.id])
            .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
            .selectAll()
            .let { query -> if (!status.isNullOrBlank()) query.andWhere { OrdersTable.status eq status.uppercase() }; query }
            .orderBy(OrdersTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map {
                AdminOrderItem(
                    id = it[OrdersTable.id].toString(),
                    customer = it[customer[UsersTable.name]],
                    restaurant = it[RestaurantsTable.name],
                    status = it[OrdersTable.status],
                    paymentMethod = it[OrdersTable.paymentMethod],
                    paymentStatus = it[OrdersTable.paymentStatus],
                    totalAmount = it[OrdersTable.totalAmount],
                    commissionAmount = it[OrdersTable.commissionAmount],
                    createdAt = it[OrdersTable.createdAt].toString()
                )
            }
    }

    fun restaurants(): List<AdminRestaurantItem> = transaction {
        (RestaurantsTable innerJoin UsersTable).selectAll().orderBy(RestaurantsTable.name).map {
            AdminRestaurantItem(it[RestaurantsTable.id].toString(), it[RestaurantsTable.name], it[UsersTable.name], it[RestaurantsTable.isOpen], it[RestaurantsTable.isApproved])
        }
    }

    fun auditLogs(limit: Int = 200): List<AdminAuditItem> = transaction {
        val administrator = UsersTable.alias("administrator")
        AdminAuditLogsTable
            .join(administrator, JoinType.INNER, AdminAuditLogsTable.adminId, administrator[UsersTable.id])
            .selectAll().orderBy(AdminAuditLogsTable.createdAt, SortOrder.DESC).limit(limit)
            .map {
                AdminAuditItem(
                    id = it[AdminAuditLogsTable.id].toString(),
                    administrator = it[administrator[UsersTable.name]],
                    action = it[AdminAuditLogsTable.action],
                    targetType = it[AdminAuditLogsTable.targetType],
                    targetId = it[AdminAuditLogsTable.targetId],
                    details = it[AdminAuditLogsTable.details],
                    createdAt = it[AdminAuditLogsTable.createdAt].toString()
                )
            }
    }

    fun setUserActive(adminId: UUID, userId: UUID, active: Boolean) = transaction {
        require(adminId != userId) { "You cannot suspend your own administrator account" }
        val target = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: error("User not found")
        require(!target[UsersTable.role].equals("ADMIN", true)) { "Administrator accounts cannot be changed here" }
        UsersTable.update({ UsersTable.id eq userId }) { it[isActive] = active }
        audit(adminId, if (active) "USER_ACTIVATED" else "USER_SUSPENDED", "USER", userId.toString())
    }

    fun setRestaurantApproved(adminId: UUID, restaurantId: UUID, approved: Boolean) = transaction {
        check(RestaurantsTable.update({ RestaurantsTable.id eq restaurantId }) { it[isApproved] = approved } == 1) { "Restaurant not found" }
        audit(adminId, if (approved) "RESTAURANT_APPROVED" else "RESTAURANT_SUSPENDED", "RESTAURANT", restaurantId.toString())
    }

    fun commissionPercentage(): Double = transaction {
        PlatformSettingsTable.select { PlatformSettingsTable.key eq "commission_percentage" }.singleOrNull()?.get(PlatformSettingsTable.value)?.toDoubleOrNull() ?: 10.0
    }

    fun commissionOverview(): CommissionOverview = transaction {
        val percentage = PlatformSettingsTable
            .select { PlatformSettingsTable.key eq "commission_percentage" }
            .singleOrNull()?.get(PlatformSettingsTable.value)?.toDoubleOrNull() ?: 10.0
        val orders = OrdersTable.selectAll().toList()
        val delivered = orders.filter { it[OrdersTable.status].equals("DELIVERED", true) }
        val terminal = setOf("DELIVERED", "CANCELLED", "DELIVERY_FAILED", "REFUNDED", "REJECTED")
        val active = orders.filter { it[OrdersTable.status].uppercase() !in terminal }
        CommissionOverview(
            percentage = percentage,
            earnedAmount = delivered.sumOf { it[OrdersTable.commissionAmount] },
            earnedOrderCount = delivered.size.toLong(),
            projectedAmount = active.sumOf { it[OrdersTable.commissionAmount] },
            projectedOrderCount = active.size.toLong()
        )
    }

    fun setCommission(adminId: UUID, percentage: Double) = transaction {
        require(percentage in 0.0..50.0) { "Commission must be between 0 and 50 percent" }
        if (PlatformSettingsTable.select { PlatformSettingsTable.key eq "commission_percentage" }.empty()) {
            PlatformSettingsTable.insert { it[key] = "commission_percentage"; it[value] = percentage.toString() }
        } else PlatformSettingsTable.update({ PlatformSettingsTable.key eq "commission_percentage" }) { it[value] = percentage.toString(); it[updatedAt] = CurrentDateTime }
        audit(adminId, "COMMISSION_UPDATED", "PLATFORM", "commission", "$percentage percent")
    }

    fun disputes(limit: Int = 100, offset: Long = 0, status: String? = null): List<AdminDisputeItem> = transaction {
        (DisputesTable innerJoin UsersTable).selectAll().let { query -> if (!status.isNullOrBlank()) query.andWhere { DisputesTable.status eq status.uppercase() }; query }.orderBy(DisputesTable.createdAt, SortOrder.DESC).limit(limit, offset).map {
            AdminDisputeItem(it[DisputesTable.id].toString(), it[DisputesTable.orderId]?.toString(), it[UsersTable.name], it[DisputesTable.subject], it[DisputesTable.description], it[DisputesTable.status], it[DisputesTable.resolution], it[DisputesTable.createdAt].toString())
        }
    }

    fun resolveDispute(adminId: UUID, disputeId: UUID, status: String, resolution: String) {
        val result = transaction {
            val normalized = status.uppercase()
            require(normalized in setOf("RESOLVED", "REJECTED")) { "Status must be RESOLVED or REJECTED" }
            require(resolution.isNotBlank()) { "Resolution is required" }
            val current = DisputesTable.select { DisputesTable.id eq disputeId }.singleOrNull() ?: error("Dispute not found")
            require(current[DisputesTable.status] == "OPEN") { "Dispute has already been closed" }
            DisputesTable.update({ (DisputesTable.id eq disputeId) and (DisputesTable.status eq "OPEN") }) { it[this.status] = normalized; it[this.resolution] = resolution; it[updatedAt] = CurrentDateTime }
            audit(adminId, "DISPUTE_$normalized", "DISPUTE", disputeId.toString(), resolution)
            Triple(current[DisputesTable.userId], current[DisputesTable.orderId], normalized)
        }
        NotificationService.createNotification(
            userId = result.first,
            title = "Support request ${result.third.lowercase()}",
            message = resolution,
            type = "SUPPORT_UPDATE",
            orderId = result.second
        )
    }

    fun refundOrder(adminId: UUID, orderId: UUID, reason: String) {
        require(reason.isNotBlank()) { "Refund reason is required" }
        val paymentIntent = transaction {
            val order = OrdersTable.select { OrdersTable.id eq orderId }.singleOrNull() ?: error("Order not found")
            require(order[OrdersTable.paymentMethod] != "COD") { "COD orders cannot be refunded through Stripe" }
            require(order[OrdersTable.paymentStatus] == "PAID") { "Only a paid order can be refunded" }
            order[OrdersTable.stripePaymentIntentId] ?: error("Stripe payment reference is missing")
        }
        PaymentService.refundOnce(paymentIntent, orderId, reason)
        transaction {
            val changed = OrdersTable.update({ (OrdersTable.id eq orderId) and (OrdersTable.paymentStatus eq "PAID") }) { it[paymentStatus] = "REFUND_PENDING"; it[updatedAt] = CurrentDateTime } == 1
            require(changed) { "Order was already refunded" }
            audit(adminId, "ORDER_REFUND_REQUESTED", "ORDER", orderId.toString(), reason)
        }
    }

    private fun audit(adminId: UUID, action: String, targetType: String, targetId: String, details: String? = null) {
        AdminAuditLogsTable.insert { it[id] = UUID.randomUUID(); it[this.adminId] = adminId; it[this.action] = action; it[this.targetType] = targetType; it[this.targetId] = targetId; it[this.details] = details }
    }
}
