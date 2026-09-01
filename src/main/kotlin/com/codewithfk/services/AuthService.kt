package com.codewithfk.services


import com.codewithfk.JwtConfig
import com.codewithfk.database.UsersTable
import com.codewithfk.database.PasswordResetTokensTable
import com.codewithfk.model.AuthProvider
import com.codewithfk.model.UserRole
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import com.codewithfk.utils.PasswordHasher
import java.security.SecureRandom
import java.time.LocalDateTime

object AuthService {
    private val httpClient = HttpClient(CIO)
    private val secureRandom = SecureRandom()

    fun requestPasswordReset(email: String): String? = transaction {
        val user = UsersTable.select { UsersTable.email eq email.trim().lowercase() }.singleOrNull()
            ?: return@transaction null
        PasswordResetTokensTable.deleteWhere {
            (PasswordResetTokensTable.userId eq user[UsersTable.id]) and PasswordResetTokensTable.usedAt.isNull()
        }
        val code = (secureRandom.nextInt(900_000) + 100_000).toString()
        PasswordResetTokensTable.insert {
            it[userId] = user[UsersTable.id]
            it[codeHash] = PasswordHasher.hash(code)
            it[expiresAt] = LocalDateTime.now().plusMinutes(15)
        }
        // Connect an email/SMS provider in production. This is emitted only for explicit local demo mode.
        if (System.getenv("ENABLE_DEBUG_RESET_CODES") == "true") code else null
    }

    fun resetPassword(email: String, code: String, newPassword: String): Boolean = transaction {
        require(newPassword.length >= 8) { "Password must contain at least 8 characters" }
        val user = UsersTable.select { UsersTable.email eq email.trim().lowercase() }.singleOrNull()
            ?: return@transaction false
        val token = PasswordResetTokensTable.select {
            (PasswordResetTokensTable.userId eq user[UsersTable.id]) and PasswordResetTokensTable.usedAt.isNull()
        }.orderBy(PasswordResetTokensTable.createdAt, SortOrder.DESC).limit(1).singleOrNull()
            ?: return@transaction false
        if (token[PasswordResetTokensTable.expiresAt].isBefore(LocalDateTime.now()) || token[PasswordResetTokensTable.attempts] >= 5) return@transaction false
        if (!PasswordHasher.verify(code, token[PasswordResetTokensTable.codeHash])) {
            PasswordResetTokensTable.update({ PasswordResetTokensTable.id eq token[PasswordResetTokensTable.id] }) {
                it[attempts] = token[PasswordResetTokensTable.attempts] + 1
            }
            return@transaction false
        }
        UsersTable.update({ UsersTable.id eq user[UsersTable.id] }) { it[passwordHash] = PasswordHasher.hash(newPassword) }
        PasswordResetTokensTable.update({ PasswordResetTokensTable.id eq token[PasswordResetTokensTable.id] }) {
            it[usedAt] = LocalDateTime.now()
        }
        true
    }

    fun register(name: String, email: String, passwordHash: String, role: String): String {
        return transaction {
            val userId = UUID.randomUUID()
            UsersTable.insert {
                it[id] = userId
                it[this.name] = name
                it[this.email] = email
                it[this.passwordHash] = PasswordHasher.hash(passwordHash)
                it[this.role] = role
                it[this.authProvider] = "email"
            }
            val address = AddressService.getAddressesByUser(userId)
            if (address.isEmpty()) {
                AddressService.createDefaultAddress(userId)
            }
            JwtConfig.generateToken(userId.toString())
        }
    }

    fun hasAnyRole(userId: UUID, allowed: Set<UserRole>): Boolean = transaction {
        UsersTable.select { UsersTable.id eq userId }
            .singleOrNull()
            ?.takeIf { it[UsersTable.isActive] }
            ?.get(UsersTable.role)
            ?.let { stored -> allowed.any { it.name.equals(stored, ignoreCase = true) } }
            ?: false
    }

    fun isActive(userId: UUID): Boolean = transaction {
        UsersTable.select { UsersTable.id eq userId }.singleOrNull()?.get(UsersTable.isActive) == true
    }

    fun getRole(userId: UUID): UserRole? = transaction {
        UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?.get(UsersTable.role)
            ?.let { stored -> UserRole.entries.firstOrNull { it.name.equals(stored, true) } }
    }

    fun getUserEmailFromID(userId: UUID): String? {
        return transaction {
            val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            user?.get(UsersTable.email)
        }
    }

    fun login(email: String, passwordHash: String, userRole: UserRole): String? {
        return transaction {
            val user = UsersTable.select {
                (UsersTable.email eq email) and (UsersTable.role.lowerCase() eq userRole.name.lowercase())
            }.singleOrNull()
            if (user?.get(UsersTable.isActive) == false) return@transaction null
            val stored = user?.get(UsersTable.passwordHash) ?: return@transaction null
            if (!PasswordHasher.verify(passwordHash, stored)) return@transaction null
            if (PasswordHasher.needsUpgrade(stored)) {
                UsersTable.update({ UsersTable.id eq user[UsersTable.id] }) {
                    it[UsersTable.passwordHash] = PasswordHasher.hash(passwordHash)
                }
            }

            user.let {
                val userId = it[UsersTable.id]
                val address = AddressService.getAddressesByUser(userId)
                if (address.isEmpty()) {
                    AddressService.createDefaultAddress(userId)
                }
                JwtConfig.generateToken(userId.toString())
            }
        }
    }

    // Google OAuth User Info
    /**
     * Validate Google ID Token and get user information.
     */
    suspend fun validateGoogleToken(idToken: String): Map<String, String>? {
        val response: HttpResponse = httpClient.get("https://oauth2.googleapis.com/tokeninfo") {
            parameter("id_token", idToken)
        }
        val responseBody = response.bodyAsText() // Read as plain text first
        println("Response Body: $responseBody") // Debug response

        // Parse as JsonObject
        val jsonObject: JsonObject = Json.parseToJsonElement(responseBody).jsonObject

        return if (response.status == HttpStatusCode.OK) {
            val userInfo = jsonObject
            mapOf(
                "email" to userInfo["email"]?.jsonPrimitive?.content.orEmpty(),
                "name" to userInfo["name"]?.jsonPrimitive?.content.orEmpty()
            )
        } else {
            null
        }
    }

    /**
     * Validate Facebook Access Token and get user information.
     */
    suspend fun validateFacebookToken(accessToken: String): Map<String, String>? {
        val response: HttpResponse = httpClient.get("https://graph.facebook.com/me") {
            parameter("fields", "id,name,email")
            parameter("access_token", accessToken)
        }
        val responseBody = response.bodyAsText() // Read as plain text first
        println("Response Body: $responseBody") // Debug response

        // Parse as JsonObject
        val jsonObject: JsonObject = Json.parseToJsonElement(responseBody).jsonObject


        return if (response.status == HttpStatusCode.OK) {
            val userInfo = jsonObject
            mapOf(
                "email" to userInfo["email"]?.jsonPrimitive?.content.orEmpty(),
                "name" to userInfo["name"]?.jsonPrimitive?.content.orEmpty()
            )
        } else {
            null
        }
    }

    /**
     * Handle user registration or login based on OAuth provider.
     */
    fun oauthLoginOrRegister(email: String, name: String, provider: String, userType: String): String {
        return transaction {
            val user = UsersTable.select { UsersTable.email eq email }.singleOrNull()

            if (user == null) {
                // Register a new user
                val userId = UUID.randomUUID()
                UsersTable.insert {
                    it[id] = userId
                    it[this.email] = email
                    it[this.name] = name
                    it[this.authProvider] = provider
                    it[this.role] = userType
                }
                val address = AddressService.getAddressesByUser(userId)
                if (address.isEmpty()) {
                    AddressService.createDefaultAddress(userId)
                }
                JwtConfig.generateToken(userId.toString())
            } else {
                // Generate token for existing user
                val userId = user[UsersTable.id]
                val address = AddressService.getAddressesByUser(userId)
                if (address.isEmpty()) {
                    AddressService.createDefaultAddress(userId)
                }
                JwtConfig.generateToken(userId.toString())
            }
        }
    }

    fun updateFcmToken(userId: UUID, token: String) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[fcmToken] = token
            }
        }
    }

}
