package com.codewithfk.services

import com.codewithfk.database.*
import com.codewithfk.model.*
import com.codewithfk.services.OrderService.getOrderAddress
import com.google.maps.DirectionsApi
import com.google.maps.model.TravelMode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.math.*

object RiderService {
    private const val SEARCH_RADIUS_KM = 6371.0
    private const val EARTH_RADIUS_KM = 6371.0

    fun isAvailable(riderId: UUID): Boolean = transaction {
        RiderLocationsTable.select { RiderLocationsTable.riderId eq riderId }
            .singleOrNull()?.get(RiderLocationsTable.isAvailable) ?: false
    }

    fun setAvailability(riderId: UUID, available: Boolean) = transaction {
        val changed = RiderLocationsTable.update({ RiderLocationsTable.riderId eq riderId }) {
            it[isAvailable] = available
            it[lastUpdated] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
        }
        check(changed == 1) { "Rider location is not configured" }
    }

    fun updateRiderLocation(riderId: UUID, latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
        transaction {
            // Update or insert new location
            val existingLocation = RiderLocationsTable
                .select { RiderLocationsTable.riderId eq riderId }
                .firstOrNull()

            if (existingLocation != null) {
                RiderLocationsTable.update({ RiderLocationsTable.riderId eq riderId }) {
                    it[this.latitude] = latitude
                    it[this.longitude] = longitude
                    it[this.lastUpdated] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }
            } else {
                RiderLocationsTable.insert {
                    it[this.riderId] = riderId
                    it[this.latitude] = latitude
                    it[this.longitude] = longitude
                    it[this.isAvailable] = true
                    it[this.lastUpdated] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }
            }
        }
    }

    // Calculate distance between two points using Haversine formula
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val originLat = Math.toRadians(lat1)
        val destinationLat = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) + 
                cos(originLat) * cos(destinationLat) * 
                sin(dLon / 2).pow(2)
        
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_KM * c
    }

    fun findNearbyRiders(restaurantLat: Double, restaurantLng: Double): List<RiderLocation> {
        return transaction {
            RiderLocationsTable
                .select { RiderLocationsTable.isAvailable eq true }
                .map {
                    RiderLocation(
                        id = it[RiderLocationsTable.riderId].toString(),
                        latitude = it[RiderLocationsTable.latitude],
                        longitude = it[RiderLocationsTable.longitude],
                        isAvailable = it[RiderLocationsTable.isAvailable],
                        lastUpdated = it[RiderLocationsTable.lastUpdated].toString()
                    )
                }
                .filter { rider ->
                    calculateDistance(
                        restaurantLat, restaurantLng,
                        rider.latitude, rider.longitude
                    ) <= SEARCH_RADIUS_KM
                }
        }
    }

    fun createDeliveryRequest(orderId: UUID): Boolean {
        return transaction {
            try {
                val order = OrdersTable
                    .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
                    .select { OrdersTable.id eq orderId }
                    .firstOrNull() ?: throw IllegalStateException("Order not found")
                if (order[OrdersTable.fulfillmentType] == "PICKUP") return@transaction false
                if (order[OrdersTable.status] != OrderStatus.READY.name || order[OrdersTable.riderId] != null) {
                    return@transaction false
                }

                val restaurantLat = order[RestaurantsTable.latitude] 
                    ?: throw IllegalStateException("Restaurant latitude not found")
                val restaurantLng = order[RestaurantsTable.longitude] 
                    ?: throw IllegalStateException("Restaurant longitude not found")

                val nearbyRiders = findNearbyRiders(restaurantLat, restaurantLng)
                
                if (nearbyRiders.isEmpty()) {
                    return@transaction false
                }

                // Create delivery requests for nearby riders
                nearbyRiders.forEach { rider ->
                    val riderUuid = UUID.fromString(rider.id)
                    if (DeliveryRequestsTable.select {
                        (DeliveryRequestsTable.orderId eq orderId) and
                            (DeliveryRequestsTable.riderId eq riderUuid)
                    }.any()) return@forEach
                    DeliveryRequestsTable.insert {
                        it[this.orderId] = orderId
                        it[this.riderId] = riderUuid
                        it[this.status] = "PENDING"
                        it[this.createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                    }

                    // Notify rider
                    notifyRider(UUID.fromString(rider.id), orderId)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun notifyRider(riderId: UUID, orderId: UUID) {
        NotificationService.createNotification(
            userId = riderId,
            title = "New Delivery Request",
            message = "New delivery request available",
            type = "NEW_DELIVERY",
            orderId = orderId
        )
    }

    fun acceptDeliveryRequest(riderId: UUID, orderId: UUID): Boolean {
        return transaction {
            OrderTransitionPolicy.requireAllowed(OrderActor.RIDER, OrderStatus.READY, OrderStatus.ASSIGNED)
            // Check if order is available (READY status and no assigned rider)
            val order = OrdersTable.select { 
                (OrdersTable.id eq orderId) and 
                (OrdersTable.status eq OrderStatus.READY.name) and
                (OrdersTable.riderId.isNull())
            }.firstOrNull() ?: return@transaction false
            
            // Update order to assign it to rider
            val updated = OrdersTable.update({
                (OrdersTable.id eq orderId) and
                    (OrdersTable.status eq OrderStatus.READY.name) and
                    OrdersTable.riderId.isNull()
            }) {
                it[OrdersTable.riderId] = riderId
                it[OrdersTable.status] = OrderStatus.ASSIGNED.name
                it[OrdersTable.updatedAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
            } == 1
            
            if (updated) {
                DeliveryRequestsTable.update({
                    (DeliveryRequestsTable.orderId eq orderId) and
                        (DeliveryRequestsTable.riderId eq riderId)
                }) { it[status] = "ACCEPTED" }
                DeliveryRequestsTable.update({
                    (DeliveryRequestsTable.orderId eq orderId) and
                        (DeliveryRequestsTable.riderId neq riderId) and
                        (DeliveryRequestsTable.status eq "PENDING")
                }) { it[status] = "CANCELLED" }

                // Notify customer
                val customerId = order[OrdersTable.userId]
                NotificationService.createNotification(
                    userId = customerId,
                    title = "Delivery Update",
                    message = "Your order has been assigned to a rider and will be picked up soon",
                    type = "DELIVERY_ASSIGNED",
                    orderId = orderId
                )
                
                // Notify restaurant
                val restaurantId = order[OrdersTable.restaurantId]
                val restaurantOwnerId = RestaurantsTable
                    .select { RestaurantsTable.id eq restaurantId }
                    .map { it[RestaurantsTable.ownerId] }
                    .single()
                    
                NotificationService.createNotification(
                    userId = restaurantOwnerId,
                    title = "Rider Assigned",
                    message = "A rider has been assigned to pick up order #${orderId.toString().take(8)}",
                    type = "RIDER_ASSIGNED",
                    orderId = orderId
                )
            }
            
            updated
        }
    }

    fun rejectDeliveryRequest(riderId: UUID, orderId: UUID): Boolean {
        return transaction {
            // We don't modify the order directly when rejecting,
            // instead we track rejections in a new table to avoid showing
            // this delivery to this rider again
            
            // First check if the order is still available
            val orderExists = OrdersTable.select { 
                (OrdersTable.id eq orderId) and 
                (OrdersTable.status eq OrderStatus.READY.name) and
                (OrdersTable.riderId.isNull())
            }.count() > 0
            
            if (!orderExists) {
                return@transaction false
            }
            if (RiderRejectionsTable.select {
                (RiderRejectionsTable.riderId eq riderId) and (RiderRejectionsTable.orderId eq orderId)
            }.any()) return@transaction true
            
            // Instead of using DeliveryRequestsTable, we'll track rejections in RiderRejections table
            // This table needs to be created if it doesn't exist
            RiderRejectionsTable.insert {
                it[this.riderId] = riderId
                it[this.orderId] = orderId
                it[this.createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
            }
            DeliveryRequestsTable.update({
                (DeliveryRequestsTable.orderId eq orderId) and
                    (DeliveryRequestsTable.riderId eq riderId) and
                    (DeliveryRequestsTable.status eq "PENDING")
            }) { it[status] = "REJECTED" }
            
            true
        }
    }

    fun getDeliveryPath(riderId: UUID, orderId: UUID): DeliveryPath {
        val order = OrderService.getOrderDetails(orderId)
        check(order.riderId == riderId.toString()) { "Order is not assigned to this rider" }
        check(order.status in setOf(OrderStatus.ASSIGNED.name, OrderStatus.OUT_FOR_DELIVERY.name)) {
            "Delivery is not active"
        }
        val riderLocation = getRiderLocation(riderId)
        val restaurant = RestaurantService.getRestaurantById(UUID.fromString(order.restaurantId))
            ?: throw IllegalStateException("Restaurant not found")
        val customerAddress = order.address 
            ?: throw IllegalStateException("Customer address not found")

        val isPickedUp = order.status == OrderStatus.OUT_FOR_DELIVERY.name

        // Get directions
        val directions = if (!isPickedUp) {
            // Rider -> Restaurant -> Customer
            DirectionsApi.newRequest(GeocodingService.geoApiContext)
                .mode(TravelMode.DRIVING)
                .origin(com.google.maps.model.LatLng(riderLocation.latitude, riderLocation.longitude))
                .destination(com.google.maps.model.LatLng(customerAddress.latitude!!, customerAddress.longitude!!))
                .waypoints(com.google.maps.model.LatLng(restaurant.latitude!!, restaurant.longitude!!))
                .await()
        } else {
            // Rider -> Customer
            DirectionsApi.newRequest(GeocodingService.geoApiContext)
                .mode(TravelMode.DRIVING)
                .origin(com.google.maps.model.LatLng(riderLocation.latitude, riderLocation.longitude))
                .destination(com.google.maps.model.LatLng(customerAddress.latitude!!, customerAddress.longitude!!))
                .await()
        }

        return createDeliveryPathFromDirections(
            directions = directions,
            riderLocation = riderLocation,
            restaurant = restaurant,
            customerAddress = customerAddress,
            isPickedUp = isPickedUp
        )
    }

    private fun createDeliveryPathFromDirections(
        directions: com.google.maps.model.DirectionsResult,
        riderLocation: RiderLocation,
        restaurant: Restaurant,
        customerAddress: Address,
        isPickedUp: Boolean
    ): DeliveryPath = DeliveryPath(
        currentLocation = Location(
            latitude = riderLocation.latitude,
            longitude = riderLocation.longitude,
            address = "Rider's current location"
        ),
        nextStop = if (!isPickedUp) {
            Location(
                latitude = restaurant.latitude!!,
                longitude = restaurant.longitude!!,
                address = restaurant.address!!
            )
        } else {
            Location(
                latitude = customerAddress.latitude!!,
                longitude = customerAddress.longitude!!,
                address = customerAddress.addressLine1
            )
        },
        finalDestination = Location(
            latitude = customerAddress.latitude!!,
            longitude = customerAddress.longitude!!,
            address = customerAddress.addressLine1
        ),
        polyline = directions.routes[0].overviewPolyline.encodedPath,
        estimatedTime = directions.routes[0].legs.sumOf { it.duration.inSeconds.toInt() } / 60,
        deliveryPhase = if (isPickedUp) DeliveryPhase.TO_CUSTOMER else DeliveryPhase.TO_RESTAURANT
    )

    fun getRiderLocation(riderId: UUID): RiderLocation {
        return transaction {
            RiderLocationsTable
                .select { RiderLocationsTable.riderId eq riderId }
                .orderBy(RiderLocationsTable.lastUpdated, SortOrder.DESC)
                .limit(1)
                .map {
                    RiderLocation(
                        id = it[RiderLocationsTable.riderId].toString(),
                        latitude = it[RiderLocationsTable.latitude],
                        longitude = it[RiderLocationsTable.longitude],
                        isAvailable = it[RiderLocationsTable.isAvailable],
                        lastUpdated = it[RiderLocationsTable.lastUpdated].toString()
                    )
                }
                .firstOrNull() ?: throw IllegalStateException("Rider location not found")
        }
    }

    fun getAvailableDeliveries(riderId: UUID): List<AvailableDelivery> {
        return transaction {
            // Get rider's current location
            val riderLocation = getRiderLocation(riderId)
            if (!riderLocation.isAvailable) return@transaction emptyList()
            
            // Get IDs of orders this rider has rejected
            val rejectedOrderIds = RiderRejectionsTable
                .select { RiderRejectionsTable.riderId eq riderId }
                .map { it[RiderRejectionsTable.orderId] }
                .toSet()
                
            // Find orders that are ready and haven't been assigned to riders
            // and haven't been rejected by this rider
            val query = (OrdersTable
                .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
                .select {
                    (OrdersTable.status eq OrderStatus.READY.name) and
                    (OrdersTable.fulfillmentType eq "DELIVERY") and
                    (OrdersTable.riderId.isNull())
                })
                
            query.mapNotNull { row ->
                val orderId = row[OrdersTable.id]
                
                // Skip if rider has already rejected this order
                if (orderId in rejectedOrderIds) {
                    return@mapNotNull null
                }
                
                val restaurantLat = row[RestaurantsTable.latitude]
                val restaurantLng = row[RestaurantsTable.longitude]
                
                // Calculate distance between rider and restaurant
                val distance = calculateDistance(
                    riderLocation.latitude, riderLocation.longitude,
                    restaurantLat, restaurantLng
                )

                // Only show deliveries within reasonable distance (e.g., 5km)
                if (distance <= SEARCH_RADIUS_KM) {
                    // Get customer address
                    val customerAddress = getOrderAddress(row[OrdersTable.addressId])
                    
                    AvailableDelivery(
                        orderId = orderId.toString(),
                        restaurantName = row[RestaurantsTable.name],
                        restaurantAddress = row[RestaurantsTable.address],
                        customerAddress = customerAddress?.addressLine1 ?: "",
                        orderAmount = row[OrdersTable.totalAmount],
                        estimatedDistance = distance,
                        estimatedEarning = calculateEarnings(distance, row[OrdersTable.totalAmount]),
                        createdAt = row[OrdersTable.createdAt].toString(),
                        paymentMethod = row[OrdersTable.paymentMethod]
                    )
                } else null
            }
        }
    }

    fun updateDeliveryStatus(
        riderId: UUID,
        orderId: UUID,
        statusUpdate: DeliveryStatusUpdate
    ): Boolean {
        return transaction {
            // Verify rider is assigned to this order
            val order = OrdersTable
                .select { 
                    (OrdersTable.id eq orderId) and
                    (OrdersTable.riderId eq riderId)
                }
                .firstOrNull() ?: throw IllegalStateException("Order not found or unauthorized")

            val currentStatus = order[OrdersTable.status]
            val expectedStatus = when (statusUpdate.status) {
                "PICKED_UP" -> OrderStatus.ASSIGNED.name
                "DELIVERED", "FAILED" -> OrderStatus.OUT_FOR_DELIVERY.name
                else -> throw IllegalArgumentException("Invalid status: ${statusUpdate.status}")
            }
            if (currentStatus != expectedStatus) {
                val message = when (currentStatus) {
                    OrderStatus.DELIVERED.name -> "This delivery has already been completed"
                    OrderStatus.DELIVERY_FAILED.name -> "This delivery has already been closed as failed"
                    else -> "Cannot mark ${statusUpdate.status.lowercase()} while delivery is $currentStatus"
                }
                throw IllegalArgumentException(message)
            }
            val targetStatus = when (statusUpdate.status) {
                "PICKED_UP" -> OrderStatus.OUT_FOR_DELIVERY
                "DELIVERED" -> OrderStatus.DELIVERED
                "FAILED" -> OrderStatus.DELIVERY_FAILED
                else -> throw IllegalArgumentException("Invalid status: ${statusUpdate.status}")
            }
            OrderTransitionPolicy.requireAllowed(
                OrderActor.RIDER,
                OrderStatus.valueOf(currentStatus),
                targetStatus
            )

            if (statusUpdate.status == "DELIVERED") {
                val expectedOtp = order[OrdersTable.deliveryOtp]
                    ?: throw IllegalStateException("Delivery confirmation code is unavailable")
                if (statusUpdate.deliveryOtp?.trim() != expectedOtp) {
                    throw IllegalArgumentException("Ask the customer for the correct 4-digit delivery code")
                }
            }

            if (statusUpdate.status == "DELIVERED" && order[OrdersTable.paymentMethod] == "COD") {
                val cashReceived = statusUpdate.cashReceived
                    ?: throw IllegalArgumentException("Enter the cash received before completing this COD delivery")
                if (cashReceived < order[OrdersTable.totalAmount]) {
                    throw IllegalArgumentException("Cash received is less than the amount due")
                }
            }

            // Update order status
            // Include the old state in the update predicate. If two requests arrive
            // together, only the first can advance the order and create a notification.
            val updated = OrdersTable.update({
                (OrdersTable.id eq orderId) and
                    (OrdersTable.riderId eq riderId) and
                    (OrdersTable.status eq expectedStatus)
            }) {
                it[status] = when (statusUpdate.status) {
                    "PICKED_UP" -> OrderStatus.OUT_FOR_DELIVERY.name
                    "DELIVERED" -> OrderStatus.DELIVERED.name
                    "FAILED" -> OrderStatus.DELIVERY_FAILED.name
                    else -> throw IllegalArgumentException("Invalid status: ${statusUpdate.status}")
                }
                if (statusUpdate.status == "DELIVERED" && order[OrdersTable.paymentMethod] == "COD") {
                    it[paymentStatus] = "PAID"
                    it[codCollected] = true
                    it[codCashReceived] = statusUpdate.cashReceived
                }
            } > 0

            if (!updated) {
                throw IllegalArgumentException("Delivery status changed already. Refresh and try again")
            }

            if (updated) {
                // Notify customer
                val customerId = order[OrdersTable.userId]
                val message = when (statusUpdate.status) {
                    "PICKED_UP" -> "Your order has been picked up and is on the way"
                    "DELIVERED" -> "Your order has been delivered"
                    "FAILED" -> "Delivery failed: ${statusUpdate.reason}"
                    else -> throw IllegalArgumentException("Invalid status")
                }

                NotificationService.createNotification(
                    userId = customerId,
                    title = "Delivery Update",
                    message = message,
                    type = when (statusUpdate.status) {
                        "PICKED_UP" -> "OUT_FOR_DELIVERY"
                        "DELIVERED" -> "DELIVERED"
                        else -> "DELIVERY_FAILED"
                    },
                    orderId = orderId
                )
            }

            updated
        }
    }

    fun getWallet(riderId: UUID): RiderWallet = transaction {
        val completed = OrdersTable.select {
            (OrdersTable.riderId eq riderId) and (OrdersTable.status eq OrderStatus.DELIVERED.name)
        }.toList()
        val earnings = completed.sumOf { row ->
            val restaurant = RestaurantsTable.select { RestaurantsTable.id eq row[OrdersTable.restaurantId] }.single()
            val address = getOrderAddress(row[OrdersTable.addressId])
            calculateEarnings(
                calculateDistance(restaurant[RestaurantsTable.latitude], restaurant[RestaurantsTable.longitude], address?.latitude ?: 0.0, address?.longitude ?: 0.0),
                row[OrdersTable.totalAmount]
            )
        }
        val cash = completed.filter { it[OrdersTable.paymentMethod] == "COD" && it[OrdersTable.codCollected] }
            .sumOf { it[OrdersTable.totalAmount] }
        val history = RiderSettlementsTable
            .select { RiderSettlementsTable.riderId eq riderId }
            .orderBy(RiderSettlementsTable.createdAt, SortOrder.DESC)
            .limit(20)
            .map { RiderSettlement(it[RiderSettlementsTable.id].toString(), it[RiderSettlementsTable.amount], it[RiderSettlementsTable.createdAt].toString()) }
        RiderWallet(completed.size, earnings, cash, (cash - earnings).coerceAtLeast(0.0), settlements = history)
    }

    fun settleWallet(riderId: UUID): Boolean = transaction {
        val orders = OrdersTable.select {
            (OrdersTable.riderId eq riderId) and
                (OrdersTable.paymentMethod eq "COD") and
                (OrdersTable.codCollected eq true)
        }.toList()
        if (orders.isEmpty()) return@transaction false
        val orderIds = orders.map { it[OrdersTable.id] }
        val amount = orders.sumOf { it[OrdersTable.totalAmount] }
        val updated = OrdersTable.update({
            (OrdersTable.id inList orderIds) and
                (OrdersTable.riderId eq riderId) and
                (OrdersTable.codCollected eq true)
        }) { it[codCollected] = false }
        if (updated > 0) {
            RiderSettlementsTable.insert {
                it[this.riderId] = riderId
                it[this.amount] = amount
            }
        }
        updated > 0
    }

    private fun calculateEarnings(distance: Double, orderAmount: Double): Double {
        // Basic earnings calculation
        val baseRate = 1.5 // thousands of MMK
        val perKmRate = 0.5 // thousands of MMK per kilometer
        val orderPercentage = 0.03
        
        return baseRate + (distance * perKmRate) + (orderAmount * orderPercentage)
    }

    fun getActiveDeliveries(riderId: UUID): List<RiderDelivery> {
        return transaction {
            // Get orders assigned to this rider that are in active delivery states
            (OrdersTable
                .join(RestaurantsTable, JoinType.INNER, OrdersTable.restaurantId, RestaurantsTable.id)
                .select {
                    (OrdersTable.riderId eq riderId) and
                    (OrdersTable.status inList listOf(
                        OrderStatus.ASSIGNED.name,
                        OrderStatus.OUT_FOR_DELIVERY.name
                    ))
                })
                .orderBy(OrdersTable.updatedAt, SortOrder.DESC)
                .map { row ->
                    val orderId = row[OrdersTable.id]
                    val customerAddress = getOrderAddress(row[OrdersTable.addressId])
                    
                    // Get order items
                    val items = OrderItemsTable
                        .join(MenuItemsTable, JoinType.INNER, OrderItemsTable.menuItemId, MenuItemsTable.id)
                        .select { OrderItemsTable.orderId eq orderId }
                        .map { itemRow ->
                            OrderItemDetail(
                                id = itemRow[OrderItemsTable.id].toString(),
                                name = itemRow[OrderItemsTable.itemName] ?: itemRow[MenuItemsTable.name],
                                quantity = itemRow[OrderItemsTable.quantity],
                                price = itemRow[OrderItemsTable.unitPrice] ?: itemRow[MenuItemsTable.price],
                                selectedModifiers = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<SelectedModifier>>(itemRow[OrderItemsTable.selectedModifiersJson]) }.getOrDefault(emptyList())
                            )
                        }
                    
                    RiderDelivery(
                        orderId = orderId.toString(),
                        status = row[OrdersTable.status],
                        restaurant = RestaurantDetail(
                            id = row[RestaurantsTable.id].toString(),
                            name = row[RestaurantsTable.name],
                            address = row[RestaurantsTable.address],
                            latitude = row[RestaurantsTable.latitude],
                            longitude = row[RestaurantsTable.longitude],
                            imageUrl = row[RestaurantsTable.imageUrl] ?: ""
                        ),
                        customer = CustomerAddress(
                            addressLine1 = customerAddress?.addressLine1 ?: "",
                            addressLine2 = customerAddress?.addressLine2,
                            city = customerAddress?.city ?: "",
                            state = customerAddress?.state,
                            zipCode = customerAddress?.zipCode ?: "",
                            latitude = customerAddress?.latitude ?: 0.0,
                            longitude = customerAddress?.longitude ?: 0.0,
                            landmark = customerAddress?.landmark,
                            plusCode = customerAddress?.plusCode
                        ),
                        items = items,
                        totalAmount = row[OrdersTable.totalAmount],
                        estimatedEarning = calculateEarnings(
                            calculateDistance(
                                row[RestaurantsTable.latitude], row[RestaurantsTable.longitude],
                                customerAddress?.latitude ?: 0.0, customerAddress?.longitude ?: 0.0
                            ),
                            row[OrdersTable.totalAmount]
                        ),
                        createdAt = row[OrdersTable.createdAt].toString(),
                        updatedAt = row[OrdersTable.updatedAt].toString(),
                        paymentMethod = row[OrdersTable.paymentMethod],
                        paymentStatus = row[OrdersTable.paymentStatus],
                        riderInstructions = row[OrdersTable.riderInstructions],
                        preparationMinutes = row[OrdersTable.preparationMinutes]
                    )
                }
        }
    }
}
