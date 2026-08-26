package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class ReviewRequest(val rating: Int, val comment: String)

@Serializable
data class RestaurantReview(
    val id: String,
    val userId: String,
    val userName: String,
    val restaurantId: String,
    val rating: Int,
    val comment: String,
    val createdAt: String
)

@Serializable
data class ReviewSummary(
    val averageRating: Double,
    val reviewCount: Int,
    val reviews: List<RestaurantReview>
)
