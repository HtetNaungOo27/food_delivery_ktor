package com.codewithfk.services

import com.codewithfk.database.*
import com.codewithfk.model.*
import com.codewithfk.utils.StripeUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.math.*
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate

object OrderService {

    fun getCheckoutDetails(userId: UUID, fulfillmentType: String = "DELIVERY"): CheckoutModel {
        return transaction {
            val cartItems =
                CartTable.select { (CartTable.userId eq userId) }

            if (cartItems.empty()) {
                return@transaction CheckoutModel(
                    subTotal = 0.0,
                    totalAmount = 0.0,
                    tax = 0.0,
                    deliveryFee = 0.0
                )
            }

            val subTotal = cartItems.sumOf {
                val quantity = it[CartTable.quantity]
                val menu = MenuItemsTable.select { MenuItemsTable.id eq it[CartTable.menuItemId] }.single()
                val selected = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<SelectedModifier>>(it[CartTable.selectedModifiersJson]) }.getOrDefault(emptyList())
                val groups = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<MenuModifierGroup>>(menu[MenuItemsTable.modifiersJson]) }.getOrDefault(emptyList())
                val extra = selected.sumOf { choice -> groups.flatMap { group -> group.options }.firstOrNull { option -> option.name.equals(choice.option, true) }?.additionalPrice ?: 0.0 }
                quantity * (menu[MenuItemsTable.price] + extra)
            }
            val tax = subTotal * 0.1
            val restaurantId = cartItems.first()[CartTable.restaurantId]
            val deliveryFee = if (fulfillmentType.equals("PICKUP", true)) 0.0 else
                RestaurantsTable.select { RestaurantsTable.id eq restaurantId }.single()[RestaurantsTable.deliveryFee]
            val total = subTotal + tax + deliveryFee

            CheckoutModel(
                subTotal = subTotal,
                totalAmount = total,
                tax = tax,
                deliveryFee = deliveryFee
            )
        }
    }

    fun placeOrder(userId: UUID, request: PlaceOrderRequest, paymentIntentId: String? = null): UUID {
        return transaction {
            val effectiveKey = paymentIntentId?.let { "stripe:$it" } ?: request.idempotencyKey
            require(!effectiveKey.isNullOrBlank()) { "Idempotency key is required" }
            OrdersTable.select {
                (OrdersTable.userId eq userId) and (OrdersTable.idempotencyKey eq effectiveKey)
            }.singleOrNull()?.let { return@transaction it[OrdersTable.id] }
            val fulfillment = request.fulfillmentType.uppercase()
            require(fulfillment in setOf("DELIVERY", "PICKUP")) { "Fulfilment type must be DELIVERY or PICKUP" }
            val scheduledFor = request.scheduledFor?.let {
                runCatching { java.time.LocalDateTime.parse(it) }
                    .getOrElse { throw IllegalArgumentException("Invalid scheduled time") }
            }
            scheduledFor?.let {
                require(it.isAfter(java.time.LocalDateTime.now().plusMinutes(29)) && it.isBefore(java.time.LocalDateTime.now().plusDays(8))) {
                    "Scheduled orders must be 30 minutes to 7 days ahead"
                }
            }
            // Verify address belongs to user
            val address = AddressService.getAddressById(UUID.fromString(request.addressId))
                ?: throw IllegalStateException("Address not found")

            if (address.userId != userId.toString()) {
                throw IllegalStateException("Address does not belong to user")
            }

            // Get cart items
            // Lock the cart rows so two different checkout requests cannot both consume them.
            val cartItems = CartTable.select { CartTable.userId eq userId }.forUpdate().toList()

            if (cartItems.isEmpty()) {
                throw IllegalStateException("Cart is empty")
            }

            // Verify all items are from the same restaurant
            val restaurantId = cartItems.first()[CartTable.restaurantId]
            val allSameRestaurant = cartItems.all { it[CartTable.restaurantId] == restaurantId }
            if (!allSameRestaurant) {
                throw IllegalStateException("All items must be from the same restaurant")
            }

            val nowDateTime = java.time.LocalDateTime.now()
            val unavailableItems = cartItems.mapNotNull { cartRow ->
                MenuItemsTable.select { MenuItemsTable.id eq cartRow[CartTable.menuItemId] }.singleOrNull()
                    ?.takeIf { menu ->
                        !menu[MenuItemsTable.isAvailable] ||
                            menu[MenuItemsTable.inventoryQuantity] < cartRow[CartTable.quantity] ||
                            menu[MenuItemsTable.unavailableUntil]?.isAfter(nowDateTime) == true
                    }?.get(MenuItemsTable.name)
            }
            require(unavailableItems.isEmpty()) {
                "Unavailable items in cart: ${unavailableItems.joinToString()}. Remove them before checkout"
            }

            // Calculate total amount
            val subTotal = cartItems.sumOf {
                val quantity = it[CartTable.quantity]
                val price = MenuItemsTable.select { MenuItemsTable.id eq it[CartTable.menuItemId] }
                    .single()[MenuItemsTable.price]
                quantity * price
            }
            val restaurant = RestaurantsTable.select { RestaurantsTable.id eq restaurantId }.single()
            val totalAmount = subTotal + (subTotal * 0.1) + if (fulfillment == "PICKUP") 0.0 else restaurant[RestaurantsTable.deliveryFee]
            require(restaurant[RestaurantsTable.isOpen] && !restaurant[RestaurantsTable.isBusy]) {
                "This restaurant is not accepting orders right now"
            }
            val operationDateTime = scheduledFor ?: java.time.LocalDateTime.now(ZoneId.of("Asia/Yangon"))
            val now = operationDateTime.toLocalTime()
            val day = operationDateTime.dayOfWeek.value
            val schedule = RestaurantHoursTable.select {
                (RestaurantHoursTable.restaurantId eq restaurantId) and (RestaurantHoursTable.dayOfWeek eq day)
            }.singleOrNull()
            require(schedule?.get(RestaurantHoursTable.isClosed) != true) { "This restaurant is closed today" }
            val opensText = schedule?.get(RestaurantHoursTable.opensAt) ?: restaurant[RestaurantsTable.opensAt]
            val closesText = schedule?.get(RestaurantHoursTable.closesAt) ?: restaurant[RestaurantsTable.closesAt]
            val opensAt = LocalTime.parse(opensText)
            val closesAt = LocalTime.parse(closesText)
            val withinHours = if (closesAt >= opensAt) now in opensAt..closesAt else now >= opensAt || now <= closesAt
            require(withinHours) {
                "This restaurant accepts orders from $opensText to $closesText"
            }
            require(subTotal >= restaurant[RestaurantsTable.minimumOrderAmount]) {
                "Minimum order is ${"%,.0f".format(restaurant[RestaurantsTable.minimumOrderAmount] * 1000)} Ks"
            }
            if (fulfillment == "DELIVERY" && address.latitude != null && address.longitude != null) {
                val distance = distanceKm(
                    restaurant[RestaurantsTable.latitude], restaurant[RestaurantsTable.longitude],
                    address.latitude, address.longitude
                )
                require(distance <= restaurant[RestaurantsTable.deliveryRadiusKm]) {
                    "Delivery address is outside this restaurant's ${restaurant[RestaurantsTable.deliveryRadiusKm].toInt()} km delivery area"
                }
            }
            val commissionPercentage = PlatformSettingsTable
                .select { PlatformSettingsTable.key eq "commission_percentage" }
                .singleOrNull()?.get(PlatformSettingsTable.value)?.toDoubleOrNull() ?: 10.0

            // Create order
            val orderId = OrdersTable.insert {
                it[this.userId] = userId
                it[this.restaurantId] = restaurantId
                it[this.addressId] = UUID.fromString(request.addressId)
                it[this.totalAmount] = totalAmount
                it[this.specialInstructions] = request.specialInstructions?.trim()?.take(500)?.ifBlank { null }
                it[this.riderInstructions] = request.riderInstructions?.trim()?.take(500)?.ifBlank { null }
                it[this.fulfillmentType] = fulfillment
                it[this.scheduledFor] = scheduledFor
                it[this.deliveryOtp] = (1000..9999).random().toString()
                it[this.status] = OrderStatus.PENDING_ACCEPTANCE.name
                it[this.paymentStatus] = if (paymentIntentId != null) "PAID" else "PENDING"
                it[this.paymentMethod] = request.paymentMethod.uppercase()
                it[this.codCollected] = false
                it[this.stripePaymentIntentId] = paymentIntentId
                it[this.idempotencyKey] = effectiveKey
                it[this.riderId] = null
                it[this.commissionPercentage] = commissionPercentage
                it[this.commissionAmount] = totalAmount * commissionPercentage / 100.0
            } get OrdersTable.id

            // Get restaurant owner's ID
            val restaurantOwnerId = RestaurantsTable
                .select { RestaurantsTable.id eq restaurantId }
                .map { it[RestaurantsTable.ownerId] }
                .single()

            // Send notification to restaurant owner
            NotificationService.createNotification(
                userId = restaurantOwnerId,
                title = "New Order Received",
                message = "New order #${orderId.toString().take(8)} worth ${"%,.0f".format(totalAmount * 1000)} Ks is waiting for acceptance",
                type = "NEW_ORDER",
                orderId = orderId
            )

            // Create order items
            cartItems.forEach { cartItem ->
                val menu = MenuItemsTable.select { MenuItemsTable.id eq cartItem[CartTable.menuItemId] }.single()
                val quantity = cartItem[CartTable.quantity]
                val deducted = MenuItemsTable.update({
                    (MenuItemsTable.id eq cartItem[CartTable.menuItemId]) and
                        (MenuItemsTable.inventoryQuantity greaterEq quantity)
                }) { it[inventoryQuantity] = inventoryQuantity - quantity }
                require(deducted == 1) { "${menu[MenuItemsTable.name]} just sold out. Review your cart" }
                OrderItemsTable.insert {
                    it[this.orderId] = orderId
                    it[this.menuItemId] = cartItem[CartTable.menuItemId]
                    it[this.quantity] = quantity
                    it[this.itemName] = menu[MenuItemsTable.name]
                    it[this.unitPrice] = menu[MenuItemsTable.price]
                    it[this.selectedModifiersJson] = cartItem[CartTable.selectedModifiersJson]
                }
            }

            // Clear cart
            CartTable.deleteWhere { CartTable.userId eq userId }

            orderId
        }
    }

    fun getOrdersByUser(userId: UUID): List<Order> {
        return transaction {
            (OrdersTable
                .join(RestaurantsTable, JoinType.LEFT, OrdersTable.restaurantId, RestaurantsTable.id)
                .select { OrdersTable.userId eq userId })
                .map { orderRow ->
                    val orderId = orderRow[OrdersTable.id]
                    
                    // Get address
                    val address = getOrderAddress(orderRow[OrdersTable.addressId])
                    
                    // Get order items
                    val items = getOrderItems(orderId)

                    Order(
                        id = orderId.toString(),
                        userId = orderRow[OrdersTable.userId].toString(),
                        restaurantId = orderRow[OrdersTable.restaurantId].toString(),
                        riderId = orderRow[OrdersTable.riderId]?.toString(),
                        address = address,
                        status = orderRow[OrdersTable.status],
                        paymentStatus = orderRow[OrdersTable.paymentStatus],
                        paymentMethod = orderRow[OrdersTable.paymentMethod],
                        codCollected = orderRow[OrdersTable.codCollected],
                        stripePaymentIntentId = orderRow[OrdersTable.stripePaymentIntentId],
                        totalAmount = orderRow[OrdersTable.totalAmount],
                        specialInstructions = orderRow[OrdersTable.specialInstructions],
                        riderInstructions = orderRow[OrdersTable.riderInstructions],
                        preparationMinutes = orderRow[OrdersTable.preparationMinutes],
                        deliveryOtp = orderRow[OrdersTable.deliveryOtp],
                        rejectionReason = orderRow[OrdersTable.rejectionReason],
                        fulfillmentType = orderRow[OrdersTable.fulfillmentType],
                        scheduledFor = orderRow[OrdersTable.scheduledFor]?.toString(),
                        items = items,
                        restaurant = Restaurant(
                            id = orderRow[RestaurantsTable.id].toString(),
                            ownerId = orderRow[RestaurantsTable.ownerId].toString(),
                            name = orderRow[RestaurantsTable.name],
                            address = orderRow[RestaurantsTable.address],
                            categoryId = orderRow[RestaurantsTable.categoryId].toString(),
                            latitude = orderRow[RestaurantsTable.latitude],
                            longitude = orderRow[RestaurantsTable.longitude],
                            imageUrl = orderRow[RestaurantsTable.imageUrl] ?: "",
                            createdAt = orderRow[RestaurantsTable.createdAt].toString()
                        ),
                        createdAt = orderRow[OrdersTable.createdAt].toString(),
                        updatedAt = orderRow[OrdersTable.updatedAt].toString()
                    )
                }
        }
    }

    fun getOrderDetails(orderId: UUID): Order {
        return transaction {
            val order = OrdersTable
                .select { OrdersTable.id eq orderId }
                .firstOrNull() ?: throw IllegalStateException("Order not found")

            Order(
                id = order[OrdersTable.id].toString(),
                userId = order[OrdersTable.userId].toString(),
                restaurantId = order[OrdersTable.restaurantId].toString(),
                riderId = order[OrdersTable.riderId]?.toString(),
                riderName = order[OrdersTable.riderId]?.let { riderId ->
                    UsersTable.select { UsersTable.id eq riderId }.singleOrNull()?.get(UsersTable.name)
                },
                address = getOrderAddress(order[OrdersTable.addressId]),
                status = order[OrdersTable.status],
                paymentStatus = order[OrdersTable.paymentStatus],
                paymentMethod = order[OrdersTable.paymentMethod],
                codCollected = order[OrdersTable.codCollected],
                stripePaymentIntentId = order[OrdersTable.stripePaymentIntentId],
                totalAmount = order[OrdersTable.totalAmount],
                specialInstructions = order[OrdersTable.specialInstructions],
                riderInstructions = order[OrdersTable.riderInstructions],
                preparationMinutes = order[OrdersTable.preparationMinutes],
                deliveryOtp = order[OrdersTable.deliveryOtp],
                rejectionReason = order[OrdersTable.rejectionReason],
                fulfillmentType = order[OrdersTable.fulfillmentType],
                scheduledFor = order[OrdersTable.scheduledFor]?.toString(),
                items = getOrderItems(orderId),
                restaurant = getRestaurantDetails(order[OrdersTable.restaurantId]),
                createdAt = order[OrdersTable.createdAt].toString(),
                updatedAt = order[OrdersTable.updatedAt].toString()
            )
        }
    }

    fun getOrderDetailsForCustomer(orderId: UUID, customerId: UUID): Order {
        transaction {
            check(OrdersTable.select {
                (OrdersTable.id eq orderId) and (OrdersTable.userId eq customerId)
            }.any()) { "Order not found" }
        }
        return getOrderDetails(orderId)
    }

    fun getOrderDetailsForOwner(orderId: UUID, ownerId: UUID): Order {
        val allowed = transaction {
            (OrdersTable innerJoin RestaurantsTable).select {
                (OrdersTable.id eq orderId) and (RestaurantsTable.ownerId eq ownerId)
            }.any()
        }
        if (!allowed) throw IllegalStateException("Order not found or unauthorized")
        return getOrderDetails(orderId).copy(deliveryOtp = null)
    }

    fun cancelCustomerOrder(orderId: UUID, customerId: UUID): Order {
        val paymentIntentId = transaction {
            val order = OrdersTable.select {
                (OrdersTable.id eq orderId) and (OrdersTable.userId eq customerId)
            }.forUpdate().singleOrNull() ?: throw IllegalStateException("Order not found")
            if (order[OrdersTable.status] != OrderStatus.PENDING_ACCEPTANCE.name) {
                throw IllegalStateException("Only an order waiting for restaurant acceptance can be cancelled")
            }
            OrdersTable.update({
                (OrdersTable.id eq orderId) and
                    (OrdersTable.userId eq customerId) and
                    (OrdersTable.status eq OrderStatus.PENDING_ACCEPTANCE.name)
            }) {
                it[status] = OrderStatus.CANCELLED.name
                it[updatedAt] = java.time.LocalDateTime.now()
            }
            order[OrdersTable.stripePaymentIntentId]
        }
        if (paymentIntentId != null) {
            try {
                PaymentService.refundOnce(paymentIntentId, orderId)
                transaction {
                    OrdersTable.update({ OrdersTable.id eq orderId }) { it[paymentStatus] = "REFUNDED" }
                }
            } catch (error: Exception) {
                transaction {
                    OrdersTable.update({ OrdersTable.id eq orderId }) { it[paymentStatus] = "REFUND_PENDING" }
                }
                println("Refund queued for cancelled order $orderId: ${error.message}")
            }
        }
        return getOrderDetailsForCustomer(orderId, customerId)
    }

    fun createCustomerIssue(orderId: UUID, customerId: UUID, type: String, description: String): OrderIssue {
        val normalizedType = type.trim().uppercase()
        require(normalizedType in setOf("MISSING_ITEM", "WRONG_ITEM", "DAMAGED_ORDER", "LATE_DELIVERY", "NEVER_ARRIVED", "OTHER")) {
            "Unsupported issue type"
        }
        require(description.trim().length in 10..2000) { "Describe the issue using at least 10 characters" }
        return transaction {
            OrdersTable.select { (OrdersTable.id eq orderId) and (OrdersTable.userId eq customerId) }
                .singleOrNull() ?: throw IllegalStateException("Order not found")
            DisputesTable.select {
                (DisputesTable.orderId eq orderId) and (DisputesTable.userId eq customerId) and
                    (DisputesTable.status eq "OPEN")
            }.singleOrNull()?.let { return@transaction it.toOrderIssue() }
            val id = DisputesTable.insert {
                it[userId] = customerId
                it[this.orderId] = orderId
                it[subject] = normalizedType
                it[this.description] = description.trim()
            } get DisputesTable.id
            DisputesTable.select { DisputesTable.id eq id }.single().toOrderIssue()
        }
    }

    fun getCustomerIssue(orderId: UUID, customerId: UUID): OrderIssue? = transaction {
        OrdersTable.select { (OrdersTable.id eq orderId) and (OrdersTable.userId eq customerId) }
            .singleOrNull() ?: throw IllegalStateException("Order not found")
        DisputesTable.select {
            (DisputesTable.orderId eq orderId) and (DisputesTable.userId eq customerId)
        }.orderBy(DisputesTable.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.toOrderIssue()
    }

    private fun ResultRow.toOrderIssue() = OrderIssue(
        id = this[DisputesTable.id].toString(),
        orderId = this[DisputesTable.orderId].toString(),
        type = this[DisputesTable.subject],
        description = this[DisputesTable.description],
        status = this[DisputesTable.status],
        resolution = this[DisputesTable.resolution],
        createdAt = this[DisputesTable.createdAt].toString()
    )

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun canAccessTracking(orderId: UUID, actorId: UUID, role: UserRole): Boolean = transaction {
        val order = (OrdersTable innerJoin RestaurantsTable).select {
            (OrdersTable.id eq orderId) and
                (OrdersTable.status inList listOf(OrderStatus.ASSIGNED.name, OrderStatus.OUT_FOR_DELIVERY.name))
        }.singleOrNull() ?: return@transaction false
        when (role) {
            UserRole.CUSTOMER -> order[OrdersTable.userId] == actorId
            UserRole.RIDER -> order[OrdersTable.riderId] == actorId
            UserRole.OWNER -> order[RestaurantsTable.ownerId] == actorId
            UserRole.ADMIN -> true
        }
    }

    fun isAssignedActiveRider(orderId: UUID, riderId: UUID): Boolean = transaction {
        OrdersTable.select {
            (OrdersTable.id eq orderId) and
                (OrdersTable.riderId eq riderId) and
                (OrdersTable.status eq OrderStatus.OUT_FOR_DELIVERY.name)
        }.any()
    }

    fun transitionOwnerOrder(ownerId: UUID, orderId: UUID, requestedStatus: String, preparationMinutes: Int? = null): Boolean {
        return transaction {
            val order = (OrdersTable innerJoin RestaurantsTable).select {
                (OrdersTable.id eq orderId) and (RestaurantsTable.ownerId eq ownerId)
            }.singleOrNull() ?: throw IllegalStateException("Order not found or unauthorized")
            val current = OrderStatus.valueOf(order[OrdersTable.status])
            val requested = runCatching { OrderStatus.valueOf(requestedStatus) }
                .getOrElse { throw IllegalArgumentException("Unknown order status") }
            val pickupCompletion = order[OrdersTable.fulfillmentType] == "PICKUP" &&
                current == OrderStatus.READY && requested == OrderStatus.DELIVERED
            if (!pickupCompletion) OrderTransitionPolicy.requireAllowed(OrderActor.RESTAURANT, current, requested)
            if (requested == OrderStatus.ACCEPTED) {
                require(preparationMinutes in 5..120) { "Preparation time must be between 5 and 120 minutes" }
            }

            val updated = OrdersTable.update({
                (OrdersTable.id eq orderId) and (OrdersTable.status eq current.name)
            }) {
                it[OrdersTable.status] = requested.name
                it[OrdersTable.updatedAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                if (requested == OrderStatus.ACCEPTED) it[OrdersTable.preparationMinutes] = preparationMinutes
            } == 1

            if (updated) {
                val notificationCopy = when (requested) {
                    OrderStatus.ACCEPTED -> "Order accepted" to "The restaurant accepted your order."
                    OrderStatus.PREPARING -> "Food is being prepared" to "The kitchen is preparing your order."
                    OrderStatus.READY -> "Order is ready" to "Your order is ready and waiting for a rider."
                    else -> "Order updated" to "Your order status changed to ${requested.name.replace('_', ' ').lowercase()}."
                }
                NotificationService.createNotification(
                    userId = order[OrdersTable.userId],
                    title = notificationCopy.first,
                    message = notificationCopy.second,
                    type = "ORDER_${requested.name}",
                    orderId = orderId,
                    push = requested == OrderStatus.ACCEPTED
                )
            } else {
                throw IllegalStateException("Order status changed already. Refresh and try again")
            }

            updated
        }
    }

    fun getOrderByPaymentIntentId(paymentIntentId: String): Order? {
        return transaction {
            OrdersTable
                .join(RestaurantsTable, JoinType.LEFT, OrdersTable.restaurantId, RestaurantsTable.id)
                .select { OrdersTable.stripePaymentIntentId eq paymentIntentId }
                .map { row ->
                    Order(
                        id = row[OrdersTable.id].toString(),
                        userId = row[OrdersTable.userId].toString(),
                        restaurantId = row[OrdersTable.restaurantId].toString(),
                        riderId = row[OrdersTable.riderId]?.toString(),
                        status = row[OrdersTable.status],
                        paymentStatus = row[OrdersTable.paymentStatus],
                        paymentMethod = row[OrdersTable.paymentMethod],
                        codCollected = row[OrdersTable.codCollected],
                        stripePaymentIntentId = row[OrdersTable.stripePaymentIntentId],
                        totalAmount = row[OrdersTable.totalAmount],
                        specialInstructions = row[OrdersTable.specialInstructions],
                        riderInstructions = row[OrdersTable.riderInstructions],
                        preparationMinutes = row[OrdersTable.preparationMinutes],
                        deliveryOtp = row[OrdersTable.deliveryOtp],
                        rejectionReason = row[OrdersTable.rejectionReason],
                        fulfillmentType = row[OrdersTable.fulfillmentType],
                        scheduledFor = row[OrdersTable.scheduledFor]?.toString(),
                        createdAt = row[OrdersTable.createdAt].toString(),
                        updatedAt = row[OrdersTable.updatedAt].toString(),
                        address = getOrderAddress(row[OrdersTable.addressId]),
                        items = getOrderItems(row[OrdersTable.id]),
                        restaurant = Restaurant(
                            id = row[RestaurantsTable.id].toString(),
                            ownerId = row[RestaurantsTable.ownerId].toString(),
                            name = row[RestaurantsTable.name],
                            address = row[RestaurantsTable.address],
                            categoryId = row[RestaurantsTable.categoryId].toString(),
                            latitude = row[RestaurantsTable.latitude],
                            longitude = row[RestaurantsTable.longitude],
                            imageUrl = row[RestaurantsTable.imageUrl] ?: "",
                            createdAt = row[RestaurantsTable.createdAt].toString()
                        )
                    )
                }
                .singleOrNull()
        }
    }

    fun saveSpecialInstructions(orderId: UUID, userId: UUID, value: String?) = transaction {
        OrdersTable.update({ (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }) {
            it[specialInstructions] = value?.trim()?.take(500)?.ifBlank { null }
        }
    }

    fun saveRiderInstructions(orderId: UUID, userId: UUID, value: String?) = transaction {
        OrdersTable.update({ (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }) {
            it[riderInstructions] = value?.trim()?.take(500)?.ifBlank { null }
        }
    }

    fun reorder(orderId: UUID, customerId: UUID): Int = transaction {
        val order = OrdersTable.select {
            (OrdersTable.id eq orderId) and (OrdersTable.userId eq customerId)
        }.singleOrNull() ?: throw IllegalStateException("Order not found")
        val originalItems = OrderItemsTable.select { OrderItemsTable.orderId eq orderId }.toList()
        require(originalItems.isNotEmpty()) { "This order has no items" }
        val now = java.time.LocalDateTime.now()
        val validated = originalItems.map { item ->
            val menu = MenuItemsTable.select { MenuItemsTable.id eq item[OrderItemsTable.menuItemId] }.singleOrNull()
                ?: throw IllegalStateException("An item from this order is no longer sold")
            require(menu[MenuItemsTable.isAvailable] && menu[MenuItemsTable.unavailableUntil]?.isAfter(now) != true) {
                "${menu[MenuItemsTable.name]} is currently unavailable"
            }
            item to menu
        }
        CartTable.deleteWhere { CartTable.userId eq customerId }
        validated.forEach { (item, menu) ->
            CartTable.insert {
                it[userId] = customerId
                it[restaurantId] = order[OrdersTable.restaurantId]
                it[menuItemId] = menu[MenuItemsTable.id]
                it[quantity] = item[OrderItemsTable.quantity]
            }
        }
        validated.size
    }

    fun handleOrderAction(orderId: UUID, ownerId: UUID, action: String, reason: String? = null): Boolean {
        return transaction {
            // Verify restaurant ownership
            val order = OrdersTable
                .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
                .select { 
                    (OrdersTable.id eq orderId) and 
                    (RestaurantsTable.ownerId eq ownerId) 
                }
                .firstOrNull() ?: throw IllegalStateException("Order not found or unauthorized")

            val currentStatus = order[OrdersTable.status]
            if (currentStatus != OrderStatus.PENDING_ACCEPTANCE.name) {
                throw IllegalStateException("Order cannot be ${action.lowercase()} in status: $currentStatus")
            }

            val newStatus = when (action.uppercase()) {
                "ACCEPT" -> OrderStatus.ACCEPTED
                "REJECT" -> OrderStatus.REJECTED
                else -> throw IllegalArgumentException("Invalid action: $action")
            }
            OrderTransitionPolicy.requireAllowed(OrderActor.RESTAURANT, OrderStatus.PENDING_ACCEPTANCE, newStatus)

            // Claim the transition atomically; only one concurrent action may win.
            val changed = OrdersTable.update({
                (OrdersTable.id eq orderId) and
                    (OrdersTable.status eq OrderStatus.PENDING_ACCEPTANCE.name)
            }) {
                it[status] = newStatus.name
                it[updatedAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
            } == 1
            if (!changed) throw IllegalStateException("Order was already processed")

            if (newStatus == OrderStatus.REJECTED) {
                require(!reason.isNullOrBlank()) { "A rejection reason is required" }
                OrdersTable.update({ OrdersTable.id eq orderId }) {
                    it[rejectionReason] = reason.trim().take(500)
                }
            }

            // Notify customer
            val customerId = order[OrdersTable.userId]
            val message = when (newStatus) {
                OrderStatus.ACCEPTED -> "Your order has been accepted and will be prepared soon"
                OrderStatus.REJECTED -> "Your order was rejected${reason?.let { " - $it" } ?: ""}"
                else -> throw IllegalStateException("Unexpected status")
            }

            NotificationService.createNotification(
                userId = customerId,
                title = "Order Update",
                message = message,
                type = "ORDER_${newStatus.name}",
                orderId = orderId
            )

            // If rejected, initiate refund if payment was made
            if (newStatus == OrderStatus.REJECTED) {
                order[OrdersTable.stripePaymentIntentId]?.let { paymentIntentId ->
                    PaymentService.refundOnce(paymentIntentId, orderId)
                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[paymentStatus] = "REFUNDED"
                    }
                }
            }

            true
        }
    }

    fun getOrderAddress(addressId: UUID?): Address? {
        if (addressId == null) return null
        
        return transaction {
            AddressesTable.select { AddressesTable.id eq addressId }
                .map { row ->
                    Address(
                        id = row[AddressesTable.id].toString(),
                        userId = row[AddressesTable.userId].toString(),
                        addressLine1 = row[AddressesTable.addressLine1],
                        addressLine2 = row[AddressesTable.addressLine2],
                        city = row[AddressesTable.city],
                        state = row[AddressesTable.state],
                        country = row[AddressesTable.country],
                        zipCode = row[AddressesTable.zipCode],
                        latitude = row[AddressesTable.latitude],
                        longitude = row[AddressesTable.longitude],
                    )
                }
                .firstOrNull()
        }
    }

    private fun getOrderItems(orderId: UUID): List<OrderItem> {
        return OrderItemsTable
            .select { OrderItemsTable.orderId eq orderId }
            .map { row ->
                val item = MenuItemsTable.select({ MenuItemsTable.id eq row[OrderItemsTable.menuItemId] }).single()
                OrderItem(
                    id = row[OrderItemsTable.id].toString(),
                    orderId = orderId.toString(),
                    menuItemId = row[OrderItemsTable.menuItemId].toString(),
                    quantity = row[OrderItemsTable.quantity],
                    menuItemName = row[OrderItemsTable.itemName] ?: item[MenuItemsTable.name],
                    selectedModifiers = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<SelectedModifier>>(row[OrderItemsTable.selectedModifiersJson]) }.getOrDefault(emptyList())
                )
            }
    }

    private fun getRestaurantDetails(restaurantId: UUID): Restaurant {
        return transaction {
            RestaurantsTable
                .select { RestaurantsTable.id eq restaurantId }
                .map { row ->
                    Restaurant(
                        id = row[RestaurantsTable.id].toString(),
                        ownerId = row[RestaurantsTable.ownerId].toString(),
                        name = row[RestaurantsTable.name],
                        address = row[RestaurantsTable.address],
                        categoryId = row[RestaurantsTable.categoryId].toString(),
                        latitude = row[RestaurantsTable.latitude],
                        longitude = row[RestaurantsTable.longitude],
                        imageUrl = row[RestaurantsTable.imageUrl] ?: "",
                        createdAt = row[RestaurantsTable.createdAt].toString()
                    )
                }
                .first()
        }
    }
}
