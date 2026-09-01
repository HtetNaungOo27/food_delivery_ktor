package com.codewithfk.configs

object SupabaseConfig {
    val SUPABASE_URL = System.getenv("SUPABASE_URL") ?: error("SUPABASE_URL is required")
    val SUPABASE_KEY = System.getenv("SUPABASE_KEY") ?: error("SUPABASE_KEY is required")
    val STORAGE_BUCKET = System.getenv("SUPABASE_STORAGE_BUCKET") ?: "foodhub-images"
}
