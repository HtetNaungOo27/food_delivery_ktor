package com.codewithfk.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object NotificationsTable : Table("notifications") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val title = varchar("title", 255)
    val message = varchar("message", 1000)
    val type = varchar("type", 50)  // ORDER_STATUS, PAYMENT_STATUS, etc.
    val orderId = uuid("order_id").references(OrdersTable.id).nullable()
    val isRead = bool("is_read").default(false)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())

    init {
        index(false, userId, isRead)
        index(false, orderId)
    }

    override val primaryKey = PrimaryKey(id)
} 
