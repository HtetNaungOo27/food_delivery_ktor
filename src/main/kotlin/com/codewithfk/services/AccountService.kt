package com.codewithfk.services

import com.codewithfk.database.AccountProfilesTable
import com.codewithfk.database.UsersTable
import com.codewithfk.model.AccountProfile
import com.codewithfk.model.ChangePasswordRequest
import com.codewithfk.model.UpdateAccountRequest
import com.codewithfk.utils.PasswordHasher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

object AccountService {
    fun get(userId: UUID): AccountProfile = transaction {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: error("Account not found")
        val profile = AccountProfilesTable.select { AccountProfilesTable.userId eq userId }.singleOrNull()
        AccountProfile(
            id = userId.toString(),
            name = user[UsersTable.name],
            email = user[UsersTable.email],
            role = user[UsersTable.role],
            phone = profile?.get(AccountProfilesTable.phone),
            vehicleType = profile?.get(AccountProfilesTable.vehicleType),
            vehiclePlate = profile?.get(AccountProfilesTable.vehiclePlate)
        )
    }

    fun update(userId: UUID, request: UpdateAccountRequest): AccountProfile = transaction {
        val name = request.name.trim()
        val email = request.email.trim().lowercase()
        require(name.length in 2..80) { "Name must contain 2 to 80 characters" }
        require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) { "Enter a valid email address" }
        val duplicate = UsersTable.select { (UsersTable.email eq email) and (UsersTable.id neq userId) }.any()
        require(!duplicate) { "That email address is already in use" }
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: error("Account not found")
        UsersTable.update({ UsersTable.id eq userId }) {
            it[UsersTable.name] = name
            it[UsersTable.email] = email
        }
        AccountProfilesTable.insertIgnore {
            it[AccountProfilesTable.userId] = userId
        }
        val rider = user[UsersTable.role].equals("RIDER", true)
        AccountProfilesTable.update({ AccountProfilesTable.userId eq userId }) {
            it[phone] = request.phone?.trim()?.takeIf(String::isNotEmpty)
            it[vehicleType] = if (rider) request.vehicleType?.trim()?.takeIf(String::isNotEmpty) else null
            it[vehiclePlate] = if (rider) request.vehiclePlate?.trim()?.uppercase()?.takeIf(String::isNotEmpty) else null
            it[updatedAt] = LocalDateTime.now()
        }
        get(userId)
    }

    fun changePassword(userId: UUID, request: ChangePasswordRequest) = transaction {
        require(request.newPassword.length >= 8) { "New password must contain at least 8 characters" }
        require(request.newPassword != request.currentPassword) { "Choose a different new password" }
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: error("Account not found")
        val stored = user[UsersTable.passwordHash]
            ?: throw IllegalArgumentException("Set a password through password recovery first")
        require(PasswordHasher.verify(request.currentPassword, stored)) { "Current password is incorrect" }
        UsersTable.update({ UsersTable.id eq userId }) { it[passwordHash] = PasswordHasher.hash(request.newPassword) }
    }
}
