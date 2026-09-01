package com.codewithfk.services

import com.codewithfk.database.*
import com.codewithfk.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.*
import org.jetbrains.exposed.sql.DoubleColumnType
import org.jetbrains.exposed.sql.ExpressionAlias
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object RestaurantOwnerService {
    fun addOwnedMenuItem(ownerId: UUID, request: MenuItem): UUID = transaction {
        val restaurantId = RestaurantsTable.select { RestaurantsTable.ownerId eq ownerId }
            .singleOrNull()?.get(RestaurantsTable.id)
            ?: throw IllegalStateException("Restaurant not found")
        MenuItemsTable.insert {
            it[this.restaurantId] = restaurantId
            it[name] = request.name.trim()
            it[description] = request.description?.trim()
            it[price] = request.price
            it[imageUrl] = request.imageUrl
            it[arModelUrl] = request.arModelUrl
            it[isAvailable] = true
            it[inventoryQuantity] = request.inventoryQuantity.coerceAtLeast(0)
            it[dietaryTags] = request.dietaryTags.joinToString(",")
            it[modifiersJson] = Json.encodeToString(request.modifierGroups)
        } get MenuItemsTable.id
    }

    fun deleteOwnedMenuItem(ownerId: UUID, itemId: UUID): Boolean = transaction {
        val belongsToOwner = (MenuItemsTable innerJoin RestaurantsTable)
            .select {
                (MenuItemsTable.id eq itemId) and (RestaurantsTable.ownerId eq ownerId)
            }
            .any()
        if (!belongsToOwner) return@transaction false
        // Keep historical order-item references intact while removing the item from active menus.
        MenuItemsTable.update({ MenuItemsTable.id eq itemId }) {
            it[isAvailable] = false
        } > 0
    }

    fun updateOwnedMenuItem(ownerId: UUID, itemId: UUID, request: UpdateMenuItemRequest): Boolean = transaction {
        val belongsToOwner = (MenuItemsTable innerJoin RestaurantsTable)
            .select {
                (MenuItemsTable.id eq itemId) and (RestaurantsTable.ownerId eq ownerId)
            }
            .any()
        if (!belongsToOwner) return@transaction false

        MenuItemsTable.update({ MenuItemsTable.id eq itemId }) { row ->
            request.name?.let { row[MenuItemsTable.name] = it }
            request.description?.let { row[MenuItemsTable.description] = it }
            request.price?.let { row[MenuItemsTable.price] = it }
            request.imageUrl?.let { row[MenuItemsTable.imageUrl] = it }
            request.category?.let { row[MenuItemsTable.category] = it }
            request.isAvailable?.let { row[MenuItemsTable.isAvailable] = it }
            request.inventoryQuantity?.let { require(it >= 0); row[MenuItemsTable.inventoryQuantity] = it }
            request.dietaryTags?.let { tags -> row[MenuItemsTable.dietaryTags] = tags.map(String::uppercase).distinct().joinToString(",") }
            request.modifierGroups?.let { groups ->
                require(groups.all { it.name.isNotBlank() && it.maxSelections > 0 && it.options.isNotEmpty() && it.options.all { option -> option.name.isNotBlank() && option.additionalPrice >= 0 } })
                row[MenuItemsTable.modifiersJson] = Json.encodeToString(groups)
            }
            if (request.isAvailable != null || request.unavailableUntil != null) {
                row[MenuItemsTable.unavailableUntil] = request.unavailableUntil?.let(LocalDateTime::parse)
            }
        } > 0
    }

    fun getOwnedMenuItems(ownerId: UUID): List<MenuItem> = transaction {
        (MenuItemsTable innerJoin RestaurantsTable)
            .select { RestaurantsTable.ownerId eq ownerId }
            .orderBy(MenuItemsTable.createdAt, SortOrder.DESC)
            .map { row ->
                MenuItem(
                    id = row[MenuItemsTable.id].toString(),
                    restaurantId = row[MenuItemsTable.restaurantId].toString(),
                    name = row[MenuItemsTable.name],
                    description = row[MenuItemsTable.description],
                    price = row[MenuItemsTable.price],
                    imageUrl = row[MenuItemsTable.imageUrl],
                    arModelUrl = row[MenuItemsTable.arModelUrl],
                    createdAt = row[MenuItemsTable.createdAt].toString(),
                    isAvailable = row[MenuItemsTable.isAvailable],
                    unavailableUntil = row[MenuItemsTable.unavailableUntil]?.toString(),
                    inventoryQuantity = row[MenuItemsTable.inventoryQuantity],
                    dietaryTags = row[MenuItemsTable.dietaryTags].split(',').filter(String::isNotBlank),
                    modifierGroups = runCatching { Json.decodeFromString<List<MenuModifierGroup>>(row[MenuItemsTable.modifiersJson]) }.getOrDefault(emptyList())
                )
            }
    }

    fun getRestaurantHours(ownerId: UUID): List<RestaurantHours> = transaction {
        val restaurant = RestaurantsTable.select { RestaurantsTable.ownerId eq ownerId }.singleOrNull()
            ?: throw IllegalStateException("Restaurant not found")
        hoursForRestaurant(restaurant[RestaurantsTable.id], restaurant[RestaurantsTable.opensAt], restaurant[RestaurantsTable.closesAt])
    }

    fun getRestaurantHoursByRestaurantId(restaurantId: UUID, fallbackOpen: String, fallbackClose: String): List<RestaurantHours> = transaction {
        hoursForRestaurant(restaurantId, fallbackOpen, fallbackClose)
    }

    fun updateRestaurantHours(ownerId: UUID, request: UpdateRestaurantHoursRequest): Boolean = transaction {
        require(request.hours.size == 7 && request.hours.map { it.dayOfWeek }.toSet() == (1..7).toSet()) {
            "Provide one schedule for every day of the week"
        }
        val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
        request.hours.forEach {
            require(it.opensAt.matches(timePattern) && it.closesAt.matches(timePattern)) { "Hours must use HH:mm" }
            require(it.isClosed || it.opensAt != it.closesAt) { "Opening and closing times cannot be the same" }
        }
        val restaurantId = RestaurantsTable.select { RestaurantsTable.ownerId eq ownerId }.singleOrNull()?.get(RestaurantsTable.id)
            ?: return@transaction false
        request.hours.forEach { hours ->
            val updated = RestaurantHoursTable.update({
                (RestaurantHoursTable.restaurantId eq restaurantId) and (RestaurantHoursTable.dayOfWeek eq hours.dayOfWeek)
            }) {
                it[opensAt] = hours.opensAt
                it[closesAt] = hours.closesAt
                it[isClosed] = hours.isClosed
            }
            if (updated == 0) RestaurantHoursTable.insert {
                it[RestaurantHoursTable.restaurantId] = restaurantId
                it[dayOfWeek] = hours.dayOfWeek
                it[opensAt] = hours.opensAt
                it[closesAt] = hours.closesAt
                it[isClosed] = hours.isClosed
            }
        }
        true
    }

    private fun hoursForRestaurant(restaurantId: UUID, fallbackOpen: String, fallbackClose: String): List<RestaurantHours> {
        val stored = RestaurantHoursTable.select { RestaurantHoursTable.restaurantId eq restaurantId }
            .associateBy { it[RestaurantHoursTable.dayOfWeek] }
        return (1..7).map { day ->
            stored[day]?.let {
                RestaurantHours(day, it[RestaurantHoursTable.opensAt], it[RestaurantHoursTable.closesAt], it[RestaurantHoursTable.isClosed])
            } ?: RestaurantHours(day, fallbackOpen, fallbackClose)
        }
    }

    fun getRestaurantOrders(ownerId: UUID, status: String? = null): List<Order> {
        return transaction {
            val query = (OrdersTable
                .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
                .join(UsersTable, JoinType.INNER, OrdersTable.userId, UsersTable.id)
                .join(AddressesTable, JoinType.LEFT, OrdersTable.addressId, AddressesTable.id)
                .select { RestaurantsTable.ownerId eq ownerId })
                
            status?.let {
                query.andWhere { OrdersTable.status eq status }
            }

            query.orderBy(OrdersTable.createdAt, SortOrder.DESC)
                .map { row ->
                    val orderId = row[OrdersTable.id]
                    
                    // Get order items
                    val items = OrderItemsTable
                        .join(MenuItemsTable, JoinType.INNER, OrderItemsTable.menuItemId, MenuItemsTable.id)
                        .select { OrderItemsTable.orderId eq orderId }
                        .map { itemRow ->
                            OrderItem(
                                id = itemRow[OrderItemsTable.id].toString(),
                                orderId = orderId.toString(),
                                menuItemId = itemRow[OrderItemsTable.menuItemId].toString(),
                                quantity = itemRow[OrderItemsTable.quantity],
                                menuItemName = itemRow[OrderItemsTable.itemName] ?: itemRow[MenuItemsTable.name],
                                selectedModifiers = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<SelectedModifier>>(itemRow[OrderItemsTable.selectedModifiersJson]) }.getOrDefault(emptyList())
                            )
                        }

                    // Map address
                    val address = if (row.getOrNull(AddressesTable.id) != null) {
                        Address(
                            id = row[AddressesTable.id].toString(),
                            userId = row[AddressesTable.userId].toString(),
                            addressLine1 = row[AddressesTable.addressLine1],
                            addressLine2 = row[AddressesTable.addressLine2],
                            city = row[AddressesTable.city],
                            state = row[AddressesTable.state],
                            zipCode = row[AddressesTable.zipCode],
                            country = row[AddressesTable.country],
                            latitude = row[AddressesTable.latitude],
                            longitude = row[AddressesTable.longitude],
                            landmark = row[AddressesTable.landmark],
                            plusCode = row[AddressesTable.plusCode]
                        )
                    } else null

                    Order(
                        id = orderId.toString(),
                        userId = row[OrdersTable.userId].toString(),
                        restaurantId = row[OrdersTable.restaurantId].toString(),
                        address = address,
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
                        items = items,
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
                            ,isOpen = row[RestaurantsTable.isOpen]
                        ),
                        createdAt = row[OrdersTable.createdAt].toString(),
                        updatedAt = row[OrdersTable.updatedAt].toString(),
                        riderId = row[OrdersTable.riderId]?.toString()
                    )
                }
        }
    }

    fun getRestaurantStatistics(ownerId: UUID): RestaurantStatistics {
        return transaction {
            // Get restaurant ID
            val restaurantId = RestaurantsTable
                .select { RestaurantsTable.ownerId eq ownerId }
                .map { it[RestaurantsTable.id] }
                .firstOrNull() ?: throw IllegalStateException("Restaurant not found")

            // Get all completed orders
            val orders = OrdersTable
                .select { 
                    (OrdersTable.restaurantId eq restaurantId) and
                    (OrdersTable.status inList listOf(OrderStatus.DELIVERED.name))
                }
                .toList()

            val totalOrders = orders.size
            val totalRevenue = orders.sumOf { it[OrdersTable.totalAmount] }
            val averageOrderValue = if (totalOrders > 0) totalRevenue / totalOrders else 0.0

            // Calculate orders by status
            val ordersByStatus = OrdersTable
                .slice(OrdersTable.status, OrdersTable.id.count())
                .select { OrdersTable.restaurantId eq restaurantId }
                .groupBy(OrdersTable.status)
                .associate { 
                    it[OrdersTable.status] to it[OrdersTable.id.count()].toInt()
                }

            // Calculate popular items with proper join and grouping
            val revenueColumn = (OrderItemsTable.quantity.sum().castTo<Double>(DoubleColumnType()) * MenuItemsTable.price)
                .alias("total_revenue")

            val popularItems = (OrderItemsTable
                .join(MenuItemsTable, JoinType.INNER)
                .join(OrdersTable, JoinType.INNER, OrderItemsTable.orderId, OrdersTable.id)
                .slice(
                    MenuItemsTable.id,
                    MenuItemsTable.name,
                    OrderItemsTable.quantity.sum(),
                    revenueColumn
                )
                .select { OrdersTable.restaurantId eq restaurantId }
                .groupBy(MenuItemsTable.id, MenuItemsTable.name, MenuItemsTable.price)
                .orderBy(OrderItemsTable.quantity.sum(), SortOrder.DESC)
                .limit(10)
                .map {
                    PopularItem(
                        id = it[MenuItemsTable.id].toString(),
                        name = it[MenuItemsTable.name],
                        totalOrders = it[OrderItemsTable.quantity.sum()]?.toInt() ?: 0,
                        revenue = it[revenueColumn].toDouble() ?: 0.0
                    )
                })

            // Calculate daily revenue with proper date grouping
            val thirtyDaysAgo = LocalDateTime.now().minusDays(30)
            val revenueByDay = OrdersTable
                .slice(
                    OrdersTable.createdAt,
                    OrdersTable.totalAmount.sum(),
                    OrdersTable.id.count()
                )
                .select { 
                    (OrdersTable.restaurantId eq restaurantId) and
                    (OrdersTable.createdAt greaterEq thirtyDaysAgo)
                }
                .groupBy(OrdersTable.createdAt)
                .map {
                    DailyRevenue(
                        date = it[OrdersTable.createdAt].toString(),
                        revenue = it[OrdersTable.totalAmount.sum()]?.toDouble() ?: 0.0,
                        orders = it[OrdersTable.id.count()].toInt()
                    )
                }

            RestaurantStatistics(
                totalOrders = totalOrders,
                totalRevenue = totalRevenue,
                averageOrderValue = averageOrderValue,
                popularItems = popularItems,
                ordersByStatus = ordersByStatus,
                revenueByDay = revenueByDay
            )
        }
    }

    fun getRestaurantDetails(ownerId: UUID): Restaurant? {
        return transaction {
            RestaurantsTable
                .select { RestaurantsTable.ownerId eq ownerId }
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
                        ,isOpen = row[RestaurantsTable.isOpen],
                        isBusy = row[RestaurantsTable.isBusy],
                        opensAt = row[RestaurantsTable.opensAt],
                        closesAt = row[RestaurantsTable.closesAt],
                        deliveryRadiusKm = row[RestaurantsTable.deliveryRadiusKm],
                        minimumOrderAmount = row[RestaurantsTable.minimumOrderAmount]
                        ,weeklyHours = hoursForRestaurant(
                            row[RestaurantsTable.id], row[RestaurantsTable.opensAt], row[RestaurantsTable.closesAt]
                        )
                        ,phone = row[RestaurantsTable.phone]
                        ,cuisine = row[RestaurantsTable.cuisine]
                        ,deliveryFee = row[RestaurantsTable.deliveryFee]
                    )
                }
                .firstOrNull()
        }
    }

    fun updateRestaurantProfile(ownerId: UUID, request: UpdateRestaurantRequest): Boolean {
        return transaction {
            RestaurantsTable.update({ RestaurantsTable.ownerId eq ownerId }) {
                request.name?.let { name -> it[RestaurantsTable.name] = name }
                request.address?.let { addr -> it[address] = addr }
                request.imageUrl?.let { url -> it[imageUrl] = url }
                request.categoryId?.let { catId -> it[categoryId] = UUID.fromString(catId) }
                request.latitude?.let { lat -> it[latitude] = lat }
                request.longitude?.let { lon -> it[longitude] = lon }
                request.isOpen?.let { open -> it[isOpen] = open }
                request.isBusy?.let { busy -> it[isBusy] = busy }
                request.opensAt?.let { value -> require(value.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))); it[opensAt] = value }
                request.closesAt?.let { value -> require(value.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))); it[closesAt] = value }
                request.deliveryRadiusKm?.let { value -> require(value in 1.0..30.0); it[deliveryRadiusKm] = value }
                request.minimumOrderAmount?.let { value -> require(value >= 0.0); it[minimumOrderAmount] = value }
                request.phone?.let { value -> require(value.length <= 40); it[phone] = value.trim().ifBlank { null } }
                request.cuisine?.let { value -> require(value.length <= 120); it[cuisine] = value.trim().ifBlank { null } }
                request.deliveryFee?.let { value -> require(value >= 0.0); it[deliveryFee] = value }
            } > 0
        }
    }
}
