package com.codewithfk.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object UsersTable : Table("users") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val authProvider = varchar("auth_provider", 50) // "google", "facebook", "email"
    val role = varchar("role", 50) // "customer", "rider", "restaurant"
    val createdAt = datetime("created_at").defaultExpression(
        org.jetbrains.exposed.sql.javatime.CurrentTimestamp()
    )
    val fcmToken = varchar("fcm_token", 255).nullable()
    val isActive = bool("is_active").default(true)

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}

object AccountProfilesTable : Table("account_profiles") {
    val userId = uuid("user_id").references(UsersTable.id)
    val phone = varchar("phone", 40).nullable()
    val vehicleType = varchar("vehicle_type", 40).nullable()
    val vehiclePlate = varchar("vehicle_plate", 40).nullable()
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(userId)
}

object PasswordResetTokensTable : Table("password_reset_tokens") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val codeHash = varchar("code_hash", 100)
    val expiresAt = datetime("expires_at")
    val usedAt = datetime("used_at").nullable()
    val attempts = integer("attempts").default(0)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object CustomerFavoritesTable : Table("customer_favorites") {
    val userId = uuid("user_id").references(UsersTable.id)
    val menuItemId = uuid("menu_item_id").references(MenuItemsTable.id)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    init { uniqueIndex(userId, menuItemId) }
    override val primaryKey = PrimaryKey(userId, menuItemId)
}
object RiderRejectionsTable : Table("rider_rejections") {
    val id = uuid("id").autoGenerate()
    val riderId = uuid("rider_id").references(UsersTable.id)
    val orderId = uuid("order_id").references(OrdersTable.id)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)

    init { uniqueIndex(riderId, orderId) }
    override val primaryKey: PrimaryKey get() = PrimaryKey(id)
}


object CategoriesTable : Table("categories") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 255).uniqueIndex()
    val imageUrl = varchar("image_url", 500).nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}

object RestaurantsTable : Table("restaurants") {
    val id = uuid("id").autoGenerate()
    val ownerId = uuid("owner_id").references(UsersTable.id) // User managing the restaurant
    val name = varchar("name", 255)
    val address = varchar("address", 500)
    val categoryId = uuid("category_id").references(CategoriesTable.id)
    val imageUrl = varchar("image_url", 500).nullable()
    val latitude = double("latitude") // Restaurant's latitude
    val longitude = double("longitude") // Restaurant's longitude
    val isOpen = bool("is_open").default(true)
    val isApproved = bool("is_approved").default(true)
    val isBusy = bool("is_busy").default(false)
    val opensAt = varchar("opens_at", 5).default("08:00")
    val closesAt = varchar("closes_at", 5).default("22:00")
    val deliveryRadiusKm = double("delivery_radius_km").default(10.0)
    val minimumOrderAmount = double("minimum_order_amount").default(0.0)
    val phone = varchar("phone", 40).nullable()
    val cuisine = varchar("cuisine", 120).nullable()
    val deliveryFee = double("delivery_fee").default(1.5)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    init { uniqueIndex(ownerId) }
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}

object RestaurantHoursTable : Table("restaurant_hours") {
    val restaurantId = uuid("restaurant_id").references(RestaurantsTable.id)
    val dayOfWeek = integer("day_of_week")
    val opensAt = varchar("opens_at", 5).default("08:00")
    val closesAt = varchar("closes_at", 5).default("22:00")
    val isClosed = bool("is_closed").default(false)
    init { uniqueIndex(restaurantId, dayOfWeek) }
    override val primaryKey = PrimaryKey(restaurantId, dayOfWeek)
}

object MenuItemsTable : Table("menu_items") {
    val id = uuid("id").autoGenerate()
    val restaurantId = uuid("restaurant_id").references(RestaurantsTable.id)
    val name = varchar("name", 255)
    val description = varchar("description", 1000).nullable()
    val price = double("price")
    val imageUrl = varchar("image_url", 500).nullable()
    val arModelUrl = varchar("ar_model_url", 500).nullable()
    val category = varchar("category", 100).nullable()
    val isAvailable = bool("is_available").default(true)
    val unavailableUntil = datetime("unavailable_until").nullable()
    val inventoryQuantity = integer("inventory_quantity").default(100)
    val dietaryTags = varchar("dietary_tags", 500).default("")
    val modifiersJson = varchar("modifiers_json", 4000).default("[]")
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    init { index(false, restaurantId, isAvailable) }
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}

object CartTable : Table("cart") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id) // Cart belongs to a user
    val restaurantId = uuid("restaurant_id").references(RestaurantsTable.id) // Restaurant associated with the cart
    val menuItemId = uuid("menu_item_id").references(MenuItemsTable.id) // Menu item in the cart
    val quantity = integer("quantity") // Quantity of the item
    val selectedModifiersJson = varchar("selected_modifiers_json", 4000).default("[]")
    val addedAt = datetime("added_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    init {
        uniqueIndex(userId, menuItemId)
        index(false, userId)
    }
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}

object AddressesTable : Table("addresses") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val addressLine1 = varchar("address_line1", 255)
    val addressLine2 = varchar("address_line2", 255).nullable()
    val city = varchar("city", 100)
    val state = varchar("state", 100)
    val zipCode = varchar("zip_code", 20)
    val country = varchar("country", 100)
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val landmark = varchar("landmark", 255).nullable()
    val plusCode = varchar("plus_code", 32).nullable()
    init { index(false, userId) }
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(id)
}
object OrdersTable : Table("orders") {
    val id = uuid("id").autoGenerate().uniqueIndex()
    val userId = uuid("user_id").references(UsersTable.id)
    val restaurantId = uuid("restaurant_id").references(RestaurantsTable.id)
    val addressId = uuid("address_id").references(AddressesTable.id)
    val status = varchar("status", 50).default("PENDING_ACCEPTANCE")
    val paymentStatus = varchar("payment_status", 50).default("PENDING")
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 255).nullable().uniqueIndex()
    val idempotencyKey = varchar("idempotency_key", 100).nullable().uniqueIndex()
    val totalAmount = double("total_amount")
    val specialInstructions = varchar("special_instructions", 500).nullable()
    val riderInstructions = varchar("rider_instructions", 500).nullable()
    val fulfillmentType = varchar("fulfillment_type", 20).default("DELIVERY")
    val scheduledFor = datetime("scheduled_for").nullable()
    val preparationMinutes = integer("preparation_minutes").nullable()
    val rejectionReason = varchar("rejection_reason", 500).nullable()
    val deliveryOtp = varchar("delivery_otp", 6).nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val riderId = uuid("rider_id").references(UsersTable.id).nullable()
    val paymentMethod = varchar("payment_method", 20).default("CARD")
    val codCollected = bool("cod_collected").default(false)
    val codCashReceived = double("cod_cash_received").nullable()
    val commissionPercentage = double("commission_percentage").default(10.0)
    val commissionAmount = double("commission_amount").default(0.0)
    
    init {
        index(false, userId, status)
        index(false, restaurantId, status)
        index(false, riderId, status)
    }
    override val primaryKey = PrimaryKey(id)
}

object OrderItemsTable : Table("order_items") {
    val id = uuid("id").autoGenerate()
    val orderId = uuid("order_id").references(OrdersTable.id)
    val menuItemId = uuid("menu_item_id").references(MenuItemsTable.id)
    val quantity = integer("quantity")
    val itemName = varchar("item_name", 255).nullable()
    val unitPrice = double("unit_price").nullable()
    val selectedModifiersJson = varchar("selected_modifiers_json", 4000).default("[]")
    
    override val primaryKey = PrimaryKey(id)
}

object ReviewsTable : Table("restaurant_reviews") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val restaurantId = uuid("restaurant_id").references(RestaurantsTable.id)
    val rating = integer("rating")
    val comment = varchar("comment", 1000)
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())

    init {
        uniqueIndex(userId, restaurantId)
    }

    override val primaryKey = PrimaryKey(id)
}

object RiderSettlementsTable : Table("rider_settlements") {
    val id = uuid("id").autoGenerate()
    val riderId = uuid("rider_id").references(UsersTable.id)
    val amount = double("amount")
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object NotificationOutboxTable : Table("notification_outbox") {
    val id = uuid("id").autoGenerate()
    val notificationId = uuid("notification_id").references(NotificationsTable.id).uniqueIndex()
    val token = varchar("token", 255)
    val title = varchar("title", 255)
    val body = varchar("body", 1000)
    val type = varchar("type", 100)
    val orderId = uuid("order_id").nullable()
    val attempts = integer("attempts").default(0)
    val sentAt = datetime("sent_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object PlatformSettingsTable : Table("platform_settings") {
    val key = varchar("setting_key", 100)
    val value = varchar("setting_value", 500)
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(key)
}

object DisputesTable : Table("disputes") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val orderId = uuid("order_id").references(OrdersTable.id).nullable()
    val subject = varchar("subject", 255)
    val description = varchar("description", 2000)
    val status = varchar("status", 30).default("OPEN")
    val resolution = varchar("resolution", 2000).nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    init { index(false, status, createdAt) }
    override val primaryKey = PrimaryKey(id)
}

object AdminAuditLogsTable : Table("admin_audit_logs") {
    val id = uuid("id").autoGenerate()
    val adminId = uuid("admin_id").references(UsersTable.id)
    val action = varchar("action", 100)
    val targetType = varchar("target_type", 50)
    val targetId = varchar("target_id", 100)
    val details = varchar("details", 2000).nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object StripeWebhookEventsTable : Table("stripe_webhook_events") {
    val eventId = varchar("event_id", 255)
    val eventType = varchar("event_type", 120)
    val status = varchar("status", 30).default("PROCESSING")
    val error = varchar("error", 1000).nullable()
    val processedAt = datetime("processed_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(eventId)
}

object StripeRefundsTable : Table("stripe_refunds") {
    val id = uuid("id").autoGenerate()
    val orderId = uuid("order_id").references(OrdersTable.id).uniqueIndex()
    val paymentIntentId = varchar("payment_intent_id", 255)
    val stripeRefundId = varchar("stripe_refund_id", 255).nullable().uniqueIndex()
    val amountMinor = long("amount_minor")
    val reason = varchar("reason", 500)
    val status = varchar("status", 30).default("PENDING")
    val failureReason = varchar("failure_reason", 1000).nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object PayoutAccountsTable : Table("payout_accounts") {
    val userId = uuid("user_id").references(UsersTable.id)
    val bankName = varchar("bank_name", 100)
    val accountName = varchar("account_name", 120)
    val accountNumberMasked = varchar("account_number_masked", 40)
    val accountFingerprint = varchar("account_fingerprint", 64)
    val updatedAt = datetime("updated_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    override val primaryKey = PrimaryKey(userId)
}

object PayoutsTable : Table("payouts") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val amount = double("amount")
    val status = varchar("status", 30).default("PENDING")
    val reference = varchar("reference", 100).nullable()
    val requestedAt = datetime("requested_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val processedAt = datetime("processed_at").nullable()
    init { index(false, userId, status) }
    override val primaryKey = PrimaryKey(id)
}
