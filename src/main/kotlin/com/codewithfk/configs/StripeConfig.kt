package com.codewithfk.configs

object StripeConfig {
    val publishableKey = System.getenv("STRIPE_PUBLISHABLE_KEY")
        ?: error("STRIPE_PUBLISHABLE_KEY is required")
    val secretKey = System.getenv("STRIPE_SECRET_KEY")
        ?: error("STRIPE_SECRET_KEY is required")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
        ?: error("STRIPE_WEBHOOK_SECRET is required")
}
