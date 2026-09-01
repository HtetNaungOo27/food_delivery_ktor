package com.codewithfk.utils

import com.codewithfk.model.UserRole
import com.codewithfk.services.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

suspend fun ApplicationCall.requireRole(vararg allowed: UserRole): UUID? {
    val rawId = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
    val userId = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        return null
    }
    if (allowed.isNotEmpty() && !AuthService.hasAnyRole(userId, allowed.toSet())) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "This account is not allowed to perform this action"))
        return null
    }
    return userId
}
