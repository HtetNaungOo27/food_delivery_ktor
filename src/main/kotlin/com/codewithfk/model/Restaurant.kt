package com.codewithfk.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Restaurant(
    val id: String,
    val ownerId: String,
    val name: String,
    val address: String,
    val categoryId: String,
    val latitude: Double,
    val imageUrl: String,
    val longitude: Double,
    val createdAt: String,
    val distance: Double? = null,
    val isOpen: Boolean = true,
    val isBusy: Boolean = false,
    val opensAt: String = "08:00",
    val closesAt: String = "22:00",
    val deliveryRadiusKm: Double = 10.0,
    val minimumOrderAmount: Double = 0.0,
    val weeklyHours: List<RestaurantHours> = emptyList(),
    val phone: String? = null,
    val cuisine: String? = null,
    val deliveryFee: Double = 1.5,
)

@Serializable
data class RestaurantHours(val dayOfWeek: Int, val opensAt: String = "08:00", val closesAt: String = "22:00", val isClosed: Boolean = false)

@Serializable
data class UpdateRestaurantHoursRequest(val hours: List<RestaurantHours>)
