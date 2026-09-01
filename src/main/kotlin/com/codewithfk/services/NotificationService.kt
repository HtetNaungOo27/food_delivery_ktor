package com.codewithfk.services

import com.codewithfk.database.NotificationsTable
import com.codewithfk.database.UsersTable
import com.codewithfk.database.NotificationOutboxTable
import com.codewithfk.model.Notification
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object NotificationService {
    fun createNotification(
        userId: UUID,
        title: String,
        message: String,
        type: String,
        orderId: UUID? = null,
        push: Boolean = true
    ): UUID {
        val notificationId = transaction {
            // Save notification to database
            val notificationId = NotificationsTable.insert {
                it[NotificationsTable.userId] = userId
                it[NotificationsTable.title] = title
                it[NotificationsTable.message] = message
                it[NotificationsTable.type] = type
                it[NotificationsTable.orderId] = orderId
                it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
            } get NotificationsTable.id

            // Get user's FCM token
            val fcmToken = UsersTable
                .select { UsersTable.id eq userId }
                .map { it[UsersTable.fcmToken] }
                .singleOrNull()

            // Send push notification if token exists
            fcmToken?.takeIf { push }?.let { token ->
                NotificationOutboxTable.insertIgnore {
                    it[this.notificationId] = notificationId
                    it[this.token] = token
                    it[this.title] = title
                    it[body] = message
                    it[this.type] = type
                    it[this.orderId] = orderId
                }
            }

            notificationId
        }
        return notificationId
    }

    /** Retries durable push events; database notifications remain the source of truth. */
    fun flushPending(limit: Int = 25) {
        // Keep push jobs pending while Firebase Admin is unavailable. In-app
        // notifications are already persisted and remain fully functional.
        if (!FirebaseService.isAvailable()) return

        val pending = transaction {
            NotificationOutboxTable.select { NotificationOutboxTable.sentAt.isNull() }
                .orderBy(NotificationOutboxTable.createdAt, SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    PendingPush(
                        row[NotificationOutboxTable.id], row[NotificationOutboxTable.notificationId],
                        row[NotificationOutboxTable.token], row[NotificationOutboxTable.title],
                        row[NotificationOutboxTable.body], row[NotificationOutboxTable.type],
                        row[NotificationOutboxTable.orderId]
                    )
                }
        }
        pending.forEach { push ->
            val sent = FirebaseService.sendNotification(
                push.token, push.title, push.body,
                mapOf(
                    "type" to push.type,
                    "orderId" to (push.orderId?.toString() ?: ""),
                    "notificationId" to push.notificationId.toString()
                )
            )
            transaction {
                NotificationOutboxTable.update({
                    (NotificationOutboxTable.id eq push.id) and NotificationOutboxTable.sentAt.isNull()
                }) {
                    it[attempts] = attempts + 1
                    if (sent) it[sentAt] = java.time.LocalDateTime.now()
                }
            }
        }
    }

    private data class PendingPush(
        val id: UUID,
        val notificationId: UUID,
        val token: String,
        val title: String,
        val body: String,
        val type: String,
        val orderId: UUID?
    )

    fun getNotifications(userId: UUID): List<Notification> {
        return transaction {
            NotificationsTable
                .select { NotificationsTable.userId eq userId }
                .orderBy(NotificationsTable.createdAt, SortOrder.DESC)
                .map {
                    Notification(
                        id = it[NotificationsTable.id].toString(),
                        userId = it[NotificationsTable.userId].toString(),
                        title = it[NotificationsTable.title],
                        message = it[NotificationsTable.message],
                        type = it[NotificationsTable.type],
                        orderId = it[NotificationsTable.orderId]?.toString(),
                        isRead = it[NotificationsTable.isRead],
                        createdAt = it[NotificationsTable.createdAt].toString()
                    )
                }
        }
    }

    fun markAsRead(userId: UUID, notificationId: UUID): Boolean {
        return transaction {
            NotificationsTable.update({
                (NotificationsTable.id eq notificationId) and (NotificationsTable.userId eq userId)
            }) {
                it[isRead] = true
            } == 1
        }
    }

    fun getUnreadCount(userId: UUID): Int {
        return transaction {
            NotificationsTable
                .select { (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false) }
                .count()
                .toInt()
        }
    }
}
