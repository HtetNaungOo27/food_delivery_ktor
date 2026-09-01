package com.codewithfk.services

import com.codewithfk.database.RestaurantsTable
import com.codewithfk.database.RestaurantHoursTable
import com.codewithfk.model.Restaurant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.math.*
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate

object RestaurantService {

    private const val EARTH_RADIUS_KM = 6371.0 // Radius of Earth in kilometers

    private fun isAcceptingOrders(row: ResultRow): Boolean {
        val now = LocalTime.now(ZoneId.of("Asia/Yangon"))
        val day = LocalDate.now(ZoneId.of("Asia/Yangon")).dayOfWeek.value
        val schedule = RestaurantHoursTable.select {
            (RestaurantHoursTable.restaurantId eq row[RestaurantsTable.id]) and (RestaurantHoursTable.dayOfWeek eq day)
        }.singleOrNull()
        if (schedule?.get(RestaurantHoursTable.isClosed) == true) return false
        val opens = LocalTime.parse(schedule?.get(RestaurantHoursTable.opensAt) ?: row[RestaurantsTable.opensAt])
        val closes = LocalTime.parse(schedule?.get(RestaurantHoursTable.closesAt) ?: row[RestaurantsTable.closesAt])
        val withinHours = if (closes >= opens) now in opens..closes else now >= opens || now <= closes
        return row[RestaurantsTable.isOpen] && !row[RestaurantsTable.isBusy] && withinHours
    }

    /**
     * Calculate distance using Haversine formula.
     */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Add a new restaurant.
     */
    fun addRestaurant(
        ownerId: UUID, name: String, address: String, latitude: Double, longitude: Double, categoryId: UUID
    ): UUID {
        return transaction {
            check(!RestaurantsTable.select { RestaurantsTable.ownerId eq ownerId }.any()) {
                "This owner already has a restaurant"
            }
            RestaurantsTable.insert {
                it[this.ownerId] = ownerId
                it[this.name] = name
                it[this.address] = address
                it[this.latitude] = latitude
                it[this.longitude] = longitude
                it[this.categoryId] = categoryId
                it[this.isApproved] = false
            } get RestaurantsTable.id
        }
    }

    /**
     * Fetch restaurants within a practical Yangon delivery radius.
     */
    fun getNearbyRestaurants(lat: Double, lon: Double, categoryId: UUID? = null): List<Restaurant> {
        return transaction {
            val customerIsInYangon = lat in 16.45..17.20 && lon in 95.75..96.55
            val query = if (categoryId != null) {
                RestaurantsTable.select { (RestaurantsTable.categoryId eq categoryId) and (RestaurantsTable.isApproved eq true) }
            } else {
                RestaurantsTable.select { RestaurantsTable.isApproved eq true }
            }

            query.mapNotNull {
                val distance = haversine(lat, lon, it[RestaurantsTable.latitude], it[RestaurantsTable.longitude])
                val restaurantIsInYangon = it[RestaurantsTable.latitude] in 16.45..17.20 &&
                    it[RestaurantsTable.longitude] in 95.75..96.55
                if ((customerIsInYangon && restaurantIsInYangon) || distance <= 10.0) {
                    Restaurant(
                        id = it[RestaurantsTable.id].toString(),
                        ownerId = it[RestaurantsTable.ownerId].toString(),
                        name = it[RestaurantsTable.name],
                        address = it[RestaurantsTable.address],
                        categoryId = it[RestaurantsTable.categoryId].toString(),
                        latitude = it[RestaurantsTable.latitude],
                        longitude = it[RestaurantsTable.longitude],
                        createdAt = it[RestaurantsTable.createdAt].toString(),
                        distance = distance,
                        imageUrl = it[RestaurantsTable.imageUrl].toString()
                        ,isOpen = isAcceptingOrders(it),
                        isBusy = it[RestaurantsTable.isBusy],
                        opensAt = it[RestaurantsTable.opensAt],
                        closesAt = it[RestaurantsTable.closesAt],
                        deliveryRadiusKm = it[RestaurantsTable.deliveryRadiusKm],
                        minimumOrderAmount = it[RestaurantsTable.minimumOrderAmount]
                        ,weeklyHours = RestaurantOwnerService.getRestaurantHoursByRestaurantId(
                            it[RestaurantsTable.id], it[RestaurantsTable.opensAt], it[RestaurantsTable.closesAt]
                        )
                        ,phone = it[RestaurantsTable.phone]
                        ,cuisine = it[RestaurantsTable.cuisine]
                        ,deliveryFee = it[RestaurantsTable.deliveryFee]
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Fetch all details of a specific restaurant.
     */
    fun getRestaurantById(id: UUID): Restaurant? {
        return transaction {
            RestaurantsTable.select { (RestaurantsTable.id eq id) and (RestaurantsTable.isApproved eq true) }.map {
                Restaurant(
                    id = it[RestaurantsTable.id].toString(),
                    ownerId = it[RestaurantsTable.ownerId].toString(),
                    name = it[RestaurantsTable.name],
                    address = it[RestaurantsTable.address],
                    categoryId = it[RestaurantsTable.categoryId].toString(),
                    latitude = it[RestaurantsTable.latitude],
                    longitude = it[RestaurantsTable.longitude],
                    createdAt = it[RestaurantsTable.createdAt].toString(),
                    distance = null, // Distance not needed here
                    imageUrl = it[RestaurantsTable.imageUrl].toString()
                    ,isOpen = isAcceptingOrders(it),
                    isBusy = it[RestaurantsTable.isBusy],
                    opensAt = it[RestaurantsTable.opensAt],
                    closesAt = it[RestaurantsTable.closesAt],
                    deliveryRadiusKm = it[RestaurantsTable.deliveryRadiusKm],
                    minimumOrderAmount = it[RestaurantsTable.minimumOrderAmount]
                    ,weeklyHours = RestaurantOwnerService.getRestaurantHoursByRestaurantId(
                        it[RestaurantsTable.id], it[RestaurantsTable.opensAt], it[RestaurantsTable.closesAt]
                    )
                    ,phone = it[RestaurantsTable.phone]
                    ,cuisine = it[RestaurantsTable.cuisine]
                    ,deliveryFee = it[RestaurantsTable.deliveryFee]
                )
            }.singleOrNull()
        }
    }
}
