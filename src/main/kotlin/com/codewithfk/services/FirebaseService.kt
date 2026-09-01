package com.codewithfk.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.AndroidConfig
import java.io.FileInputStream

object FirebaseService {
    @Volatile
    private var initialized = false

    init {
        try {
            val serviceAccountPath = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH")
                ?: throw IllegalStateException("FIREBASE_SERVICE_ACCOUNT_PATH is not configured")
            val serviceAccount = FileInputStream(serviceAccountPath)
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
            }
            initialized = true
            println("Firebase initialized successfully")
        } catch (e: Exception) {
            println("Firebase initialization failed: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = initialized && FirebaseApp.getApps().isNotEmpty()

    fun sendNotification(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        if (!isAvailable()) return false
        try {
            val message = Message.builder()
                .setToken(token)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .putAllData(data + mapOf("title" to title, "message" to body))
                .build()

            val response = FirebaseMessaging.getInstance().send(message)
            println("Successfully sent message: $response")
            return true
        } catch (e: Exception) {
            println("Error sending Firebase notification: ${e.message}")
            return false
        }
    }
}
