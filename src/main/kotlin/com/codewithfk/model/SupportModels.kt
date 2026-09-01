package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateOrderIssueRequest(val type: String, val description: String)

@Serializable
data class OrderIssue(
    val id: String,
    val orderId: String,
    val type: String,
    val description: String,
    val status: String,
    val resolution: String? = null,
    val createdAt: String
)

@Serializable
data class OrderIssueResponse(val issue: OrderIssue? = null)
