package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountProfile(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val phone: String? = null,
    val vehicleType: String? = null,
    val vehiclePlate: String? = null
)

@Serializable
data class UpdateAccountRequest(
    val name: String,
    val email: String,
    val phone: String? = null,
    val vehicleType: String? = null,
    val vehiclePlate: String? = null
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
