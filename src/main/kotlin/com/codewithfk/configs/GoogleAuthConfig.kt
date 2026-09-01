package com.codewithfk.configs


object GoogleAuthConfig {
    val clientId = System.getenv("GOOGLE_OAUTH_CLIENT_ID") ?: error("GOOGLE_OAUTH_CLIENT_ID is required")
    val clientSecret = System.getenv("GOOGLE_OAUTH_CLIENT_SECRET") ?: error("GOOGLE_OAUTH_CLIENT_SECRET is required")
    val redirectUri = System.getenv("GOOGLE_OAUTH_REDIRECT_URI") ?: "http://localhost:8081/auth/google/callback"
    const val authorizeUrl = "https://accounts.google.com/o/oauth2/auth"
    const val tokenUrl = "https://oauth2.googleapis.com/token"
}
