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

@Serializable
data class UpdateCustomerProfileRequest(val name: String)

@Serializable data class FavoriteUpdateRequest(val favorite: Boolean)
@Serializable data class FavoriteIdsResponse(val ids: List<String>)
