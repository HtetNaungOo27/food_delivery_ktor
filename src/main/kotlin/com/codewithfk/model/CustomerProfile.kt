package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomerProfile(
    val id: String,
    val name: String,
    val email: String,
    val memberSince: String,
    val completedOrders: Int,
    val totalSpent: Double,
    val bitePoints: Int,
    val savedAddresses: Int
)
