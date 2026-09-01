package com.codewithfk.utils

import org.mindrot.jbcrypt.BCrypt

object PasswordHasher {
    fun hash(password: String): String {
        require(password.length >= 6) { "Password must contain at least 6 characters" }
        return BCrypt.hashpw(password, BCrypt.gensalt(12))
    }

    fun verify(password: String, stored: String): Boolean =
        if (stored.startsWith("$2")) BCrypt.checkpw(password, stored) else password == stored

    fun needsUpgrade(stored: String): Boolean = !stored.startsWith("$2")
}
