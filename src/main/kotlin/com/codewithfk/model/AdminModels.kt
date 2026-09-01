package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class AdminLoginRequest(val email: String, val password: String)

@Serializable
data class AdminSummary(
    val customers: Long,
    val owners: Long,
    val riders: Long,
    val restaurants: Long,
    val orders: Long,
    val activeOrders: Long,
    val deliveredOrders: Long,
    val grossMerchandiseValue: Double
)

@Serializable
data class AdminUserItem(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable data class AdminRestaurantItem(val id: String, val name: String, val owner: String, val isOpen: Boolean, val isApproved: Boolean)
@Serializable data class AdminDisputeItem(val id: String, val orderId: String?, val customer: String, val subject: String, val description: String, val status: String, val resolution: String?, val createdAt: String)
@Serializable data class SetActiveRequest(val active: Boolean)
@Serializable data class SetApprovalRequest(val approved: Boolean)
@Serializable data class CommissionRequest(val percentage: Double)
@Serializable data class CommissionOverview(
    val percentage: Double,
    val earnedAmount: Double,
    val earnedOrderCount: Long,
    val projectedAmount: Double,
    val projectedOrderCount: Long
)
@Serializable data class ResolveDisputeRequest(val status: String, val resolution: String)
@Serializable data class AdminRefundRequest(val reason: String)

@Serializable
data class AdminOrderItem(
    val id: String,
    val customer: String,
    val restaurant: String,
    val status: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val totalAmount: Double,
    val commissionAmount: Double,
    val createdAt: String
)

@Serializable
data class AdminAuditItem(
    val id: String,
    val administrator: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val details: String?,
    val createdAt: String
)
