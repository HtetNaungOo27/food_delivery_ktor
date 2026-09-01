package com.codewithfk.database

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID
import com.codewithfk.utils.PasswordHasher

/**
 * Adds a connected, repeatable dataset for demonstrating every SwiftBite role.
 * Existing rows are preserved; stable IDs make subsequent startup calls no-ops.
 */
fun seedPresentationDemo() {
    val now = LocalDateTime.now()

    fun stableId(key: String): UUID = UUID.nameUUIDFromBytes("swiftbite-demo:$key".toByteArray(StandardCharsets.UTF_8))

    fun ensureUser(key: String, name: String, email: String, role: String): UUID {
        val existing = UsersTable.select { UsersTable.email eq email }.singleOrNull()
        if (existing != null) {
            UsersTable.update({ UsersTable.id eq existing[UsersTable.id] }) {
                it[UsersTable.name] = name
                it[UsersTable.role] = role
                it[UsersTable.authProvider] = "email"
            }
            return existing[UsersTable.id]
        }
        val id = stableId("user:$key")
        UsersTable.insert {
            it[UsersTable.id] = id
            it[UsersTable.name] = name
            it[UsersTable.email] = email
            it[UsersTable.passwordHash] = PasswordHasher.hash("111111")
            it[UsersTable.authProvider] = "email"
            it[UsersTable.role] = role
            it[UsersTable.createdAt] = now.minusMonths(8)
        }
        return id
    }

    fun ensureCategory(name: String, image: String): UUID {
        CategoriesTable.select { CategoriesTable.name eq name }.singleOrNull()?.let { return it[CategoriesTable.id] }
        val id = stableId("category:$name")
        CategoriesTable.insert {
            it[CategoriesTable.id] = id
            it[CategoriesTable.name] = name
            it[CategoriesTable.imageUrl] = image
            it[CategoriesTable.createdAt] = now.minusMonths(6)
        }
        return id
    }

    fun ensureRestaurant(key: String, owner: UUID, category: UUID, name: String, address: String, image: String, lat: Double, lng: Double): UUID {
        val id = stableId("restaurant:$key")
        RestaurantsTable.select { RestaurantsTable.id eq id }.singleOrNull()?.let {
            RestaurantsTable.update({ RestaurantsTable.id eq id }) { row ->
                row[RestaurantsTable.ownerId] = owner
                row[RestaurantsTable.categoryId] = category
                row[RestaurantsTable.name] = name
                row[RestaurantsTable.address] = address
                row[RestaurantsTable.imageUrl] = image
                row[RestaurantsTable.latitude] = lat
                row[RestaurantsTable.longitude] = lng
                row[RestaurantsTable.isApproved] = true
            }
            return id
        }
        RestaurantsTable.insert {
            it[RestaurantsTable.id] = id
            it[RestaurantsTable.ownerId] = owner
            it[RestaurantsTable.categoryId] = category
            it[RestaurantsTable.name] = name
            it[RestaurantsTable.address] = address
            it[RestaurantsTable.imageUrl] = image
            it[RestaurantsTable.latitude] = lat
            it[RestaurantsTable.longitude] = lng
            it[RestaurantsTable.isApproved] = true
            it[RestaurantsTable.createdAt] = now.minusMonths(5)
        }
        return id
    }

    fun ensureMenuItem(key: String, restaurant: UUID, name: String, description: String, price: Double, image: String, category: String): UUID {
        val id = stableId("menu:$key")
        if (MenuItemsTable.select { MenuItemsTable.id eq id }.empty()) {
            MenuItemsTable.insert {
                it[MenuItemsTable.id] = id
                it[MenuItemsTable.restaurantId] = restaurant
                it[MenuItemsTable.name] = name
                it[MenuItemsTable.description] = description
                it[MenuItemsTable.price] = price
                it[MenuItemsTable.imageUrl] = image
                it[MenuItemsTable.category] = category
                it[MenuItemsTable.isAvailable] = true
                it[MenuItemsTable.createdAt] = now.minusMonths(3)
            }
        }
        return id
    }

    fun ensureAddress(key: String, user: UUID, line1: String, line2: String?, landmark: String, plusCode: String, lat: Double, lng: Double): UUID {
        val id = stableId("address:$key")
        if (AddressesTable.select { AddressesTable.id eq id }.empty()) {
            AddressesTable.insert {
                it[AddressesTable.id] = id
                it[AddressesTable.userId] = user
                it[AddressesTable.addressLine1] = line1
                it[AddressesTable.addressLine2] = line2
                it[AddressesTable.city] = "Yangon"
                it[AddressesTable.state] = "Yangon Region"
                it[AddressesTable.zipCode] = "11181"
                it[AddressesTable.country] = "Myanmar"
                it[AddressesTable.landmark] = landmark
                it[AddressesTable.plusCode] = plusCode
                it[AddressesTable.latitude] = lat
                it[AddressesTable.longitude] = lng
            }
        }
        return id
    }

    fun ensureOrder(
        key: String,
        customer: UUID,
        restaurant: UUID,
        address: UUID,
        status: String,
        paymentMethod: String,
        paymentStatus: String,
        amount: Double,
        rider: UUID?,
        codCollected: Boolean,
        ageHours: Long,
        items: List<Pair<UUID, Int>>
    ): UUID {
        val id = stableId("order:$key")
        if (OrdersTable.select { OrdersTable.id eq id }.empty()) {
            OrdersTable.insert {
                it[OrdersTable.id] = id
                it[OrdersTable.userId] = customer
                it[OrdersTable.restaurantId] = restaurant
                it[OrdersTable.addressId] = address
                it[OrdersTable.status] = status
                it[OrdersTable.paymentMethod] = paymentMethod
                it[OrdersTable.paymentStatus] = paymentStatus
                it[OrdersTable.totalAmount] = amount
                it[OrdersTable.commissionPercentage] = 10.0
                it[OrdersTable.commissionAmount] = amount * 0.10
                it[OrdersTable.riderId] = rider
                it[OrdersTable.codCollected] = codCollected
                it[OrdersTable.createdAt] = now.minusHours(ageHours)
                it[OrdersTable.updatedAt] = now.minusHours((ageHours / 2).coerceAtLeast(1))
            }
            items.forEachIndexed { index, (menuItem, quantity) ->
                OrderItemsTable.insert {
                    it[OrderItemsTable.id] = stableId("order-item:$key:$index")
                    it[OrderItemsTable.orderId] = id
                    it[OrderItemsTable.menuItemId] = menuItem
                    it[OrderItemsTable.quantity] = quantity
                }
            }
        }
        return id
    }

    fun ensureNotification(key: String, user: UUID, title: String, message: String, type: String, order: UUID?, read: Boolean, ageMinutes: Long) {
        val id = stableId("notification:$key")
        if (NotificationsTable.select { NotificationsTable.id eq id }.empty()) {
            NotificationsTable.insert {
                it[NotificationsTable.id] = id
                it[NotificationsTable.userId] = user
                it[NotificationsTable.title] = title
                it[NotificationsTable.message] = message
                it[NotificationsTable.type] = type
                it[NotificationsTable.orderId] = order
                it[NotificationsTable.isRead] = read
                it[NotificationsTable.createdAt] = now.minusMinutes(ageMinutes)
            }
        }
    }

    val customer = ensureUser("customer", "Maya Linn", "customer@swiftbite.demo", "CUSTOMER")
    val reviewerOne = ensureUser("reviewer-one", "Noah Win", "noah@swiftbite.demo", "CUSTOMER")
    val reviewerTwo = ensureUser("reviewer-two", "Thiri Aye", "thiri@swiftbite.demo", "CUSTOMER")
    val owner = ensureUser("owner", "Aiden Chen", "owner@swiftbite.demo", "OWNER")
    val bakeryOwner = ensureUser("bakery-owner", "Nora Hnin", "bakery@swiftbite.demo", "OWNER")
    val healthyOwner = ensureUser("healthy-owner", "Kai Zaw", "healthy@swiftbite.demo", "OWNER")
    val rider = ensureUser("rider", "Leo Min", "rider@swiftbite.demo", "RIDER")

    val asian = ensureCategory("Asian Kitchen", "https://images.unsplash.com/photo-1525755662778-989d0524087e?w=800")
    val bakery = ensureCategory("Bakery & Sweet", "https://images.unsplash.com/photo-1486427944299-d1955d23e34d?w=800")
    val healthy = ensureCategory("Fresh & Healthy", "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=800")

    val restaurant = ensureRestaurant("golden-bowl", owner, asian, "Golden Bowl Kitchen", "No. 18, Inya Road, Kamayut, Yangon", "https://images.unsplash.com/photo-1515003197210-e0cd71810b5f?w=1200", 16.8179, 96.1309)
    val bakeryRestaurant = ensureRestaurant("morning-crumb", bakeryOwner, bakery, "Morning Crumb", "Junction Square, Kamayut, Yangon", "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=1200", 16.8174, 96.1297)
    val healthyRestaurant = ensureRestaurant("green-table", healthyOwner, healthy, "The Green Table", "Pyay Road, Sanchaung, Yangon", "https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=1200", 16.8068, 96.1350)

    val teaLeaf = ensureMenuItem("tea-leaf", restaurant, "Tea Leaf Crunch Bowl", "Fermented tea leaves, cabbage, roasted nuts, tomato, and lime.", 7.90, "https://images.unsplash.com/photo-1547592180-85f173990554?w=1000", "Signature")
    val noodles = ensureMenuItem("shan-noodles", restaurant, "Shan Noodles", "Rice noodles with slow-cooked chicken, tomato, garlic, and pickled greens.", 8.50, "https://images.unsplash.com/photo-1569718212165-3a8278d5f624?w=1000", "Noodles")
    val curry = ensureMenuItem("coconut-curry", restaurant, "Coconut Curry Rice", "Aromatic coconut curry, jasmine rice, seasonal vegetables, and herbs.", 10.40, "https://images.unsplash.com/photo-1603894584373-5ac82b2ae398?w=1000", "Mains")
    val dumplings = ensureMenuItem("dumplings", restaurant, "Crispy Garden Dumplings", "Golden vegetable dumplings with ginger-soy dipping sauce.", 6.20, "https://images.unsplash.com/photo-1496116218417-1a781b1c416c?w=1000", "Small Plates")
    val mango = ensureMenuItem("mango-sticky-rice", restaurant, "Mango Sticky Rice", "Sweet coconut sticky rice with ripe mango and toasted sesame.", 5.80, "https://images.unsplash.com/photo-1563805042-7684c019e1cb?w=1000", "Dessert")
    val limeTea = ensureMenuItem("lime-tea", restaurant, "Sparkling Lime Tea", "Cold-brewed tea, fresh lime, mint, and sparkling water.", 3.60, "https://images.unsplash.com/photo-1556679343-c7306c1976bc?w=1000", "Drinks")
    ensureMenuItem("croissant", bakeryRestaurant, "Butter Croissant", "Flaky, slow-fermented pastry baked fresh each morning.", 3.90, "https://images.unsplash.com/photo-1555507036-ab1f4038808a?w=1000", "Pastry")
    ensureMenuItem("berry-toast", bakeryRestaurant, "Berry French Toast", "Brioche, seasonal berries, vanilla cream, and maple syrup.", 7.40, "https://images.unsplash.com/photo-1484723091739-30a097e8f929?w=1000", "Breakfast")
    ensureMenuItem("cocoa-cake", bakeryRestaurant, "Midnight Cocoa Cake", "Dark chocolate sponge with silky cocoa ganache.", 5.60, "https://images.unsplash.com/photo-1578985545062-69928b1d9587?w=1000", "Dessert")
    ensureMenuItem("avocado-bowl", healthyRestaurant, "Avocado Power Bowl", "Greens, avocado, grains, chickpeas, seeds, and citrus dressing.", 9.20, "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=1000", "Bowls")
    ensureMenuItem("salmon-salad", healthyRestaurant, "Sesame Salmon Salad", "Roasted salmon, crunchy vegetables, herbs, and sesame dressing.", 12.80, "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=1000", "Protein")
    ensureMenuItem("green-smoothie", healthyRestaurant, "Morning Green Smoothie", "Spinach, mango, banana, lime, and coconut water.", 4.80, "https://images.unsplash.com/photo-1610970881699-44a5587cabec?w=1000", "Drinks")

    val home = ensureAddress("home", customer, "88 Swift Street", "Apartment 4B", "Opposite Junction Square", "R4M6+9C Yangon", 16.8176, 96.1302)
    val campus = ensureAddress("campus", customer, "University Avenue Road", "Main library entrance", "Near Yangon University Convocation Hall", "R4JF+8W Yangon", 16.8256, 96.1348)
    val reviewerOneAddress = ensureAddress("reviewer-one", reviewerOne, "24 Pyay Road", null, "Beside City Mart", "R4J8+6P Yangon", 16.8121, 96.1321)
    val reviewerTwoAddress = ensureAddress("reviewer-two", reviewerTwo, "12 Insein Road", "Floor 2", "Near Hledan Centre", "R4Q5+3H Yangon", 16.8282, 96.1291)

    if (RiderLocationsTable.select { RiderLocationsTable.riderId eq rider }.empty()) {
        RiderLocationsTable.insert {
            it[RiderLocationsTable.id] = stableId("rider-location")
            it[RiderLocationsTable.riderId] = rider
            it[RiderLocationsTable.latitude] = 16.8210
            it[RiderLocationsTable.longitude] = 96.1320
            it[RiderLocationsTable.isAvailable] = true
            it[RiderLocationsTable.lastUpdated] = now
        }
    }

    val pending = ensureOrder("pending", customer, restaurant, home, "PENDING_ACCEPTANCE", "COD", "PENDING", 24.80, null, false, 1, listOf(noodles to 2, limeTea to 2))
    val preparing = ensureOrder("preparing", customer, restaurant, campus, "PREPARING", "CARD", "PAID", 20.30, null, false, 2, listOf(curry to 1, mango to 1, limeTea to 1))
    val available = ensureOrder("ready-available", reviewerOne, restaurant, reviewerOneAddress, "READY", "COD", "PENDING", 22.60, null, false, 3, listOf(teaLeaf to 2, dumplings to 1))
    val assigned = ensureOrder("assigned", customer, restaurant, campus, "ASSIGNED", "CARD", "PAID", 27.40, rider, false, 4, listOf(curry to 2, limeTea to 1))
    val travelling = ensureOrder("out-for-delivery", reviewerTwo, restaurant, reviewerTwoAddress, "OUT_FOR_DELIVERY", "COD", "PENDING", 19.30, rider, false, 5, listOf(noodles to 1, dumplings to 1, mango to 1))
    val deliveredCod = ensureOrder("delivered-cod", customer, restaurant, home, "DELIVERED", "COD", "PAID", 31.00, rider, true, 28, listOf(teaLeaf to 2, curry to 1, limeTea to 1))
    val deliveredCard = ensureOrder("delivered-card", customer, restaurant, campus, "DELIVERED", "CARD", "PAID", 22.70, rider, false, 76, listOf(noodles to 1, mango to 1, limeTea to 2))
    val rejected = ensureOrder("rejected", customer, restaurant, home, "REJECTED", "CARD", "REFUNDED", 14.30, null, false, 120, listOf(noodles to 1, mango to 1))

    if (CartTable.select { CartTable.id eq stableId("cart:tea") }.empty()) {
        listOf(Triple("tea", teaLeaf, 1), Triple("dumplings", dumplings, 2)).forEach { (key, item, quantity) ->
            CartTable.insert {
                it[CartTable.id] = stableId("cart:$key")
                it[CartTable.userId] = customer
                it[CartTable.restaurantId] = restaurant
                it[CartTable.menuItemId] = item
                it[CartTable.quantity] = quantity
                it[CartTable.addedAt] = now.minusMinutes(12)
            }
        }
    }

    fun ensureReview(key: String, user: UUID, targetRestaurant: UUID, rating: Int, comment: String, daysAgo: Long) {
        val id = stableId("review:$key")
        if (ReviewsTable.select { ReviewsTable.id eq id }.empty()) {
            ReviewsTable.insert {
                it[ReviewsTable.id] = id
                it[ReviewsTable.userId] = user
                it[ReviewsTable.restaurantId] = targetRestaurant
                it[ReviewsTable.rating] = rating
                it[ReviewsTable.comment] = comment
                it[ReviewsTable.createdAt] = now.minusDays(daysAgo)
                it[ReviewsTable.updatedAt] = now.minusDays(daysAgo)
            }
        }
    }
    ensureReview("maya", customer, restaurant, 5, "Bright flavors, thoughtful packaging, and the lime tea arrived perfectly chilled.", 2)
    ensureReview("noah", reviewerOne, restaurant, 4, "The tea leaf bowl was fresh and crunchy. Delivery was quick too.", 5)
    ensureReview("thiri", reviewerTwo, restaurant, 5, "Beautiful presentation and one of the best noodle bowls near campus.", 9)
    ensureReview("bakery-maya", customer, bakeryRestaurant, 5, "The croissant was crisp, buttery, and still warm.", 4)
    ensureReview("bakery-noah", reviewerOne, bakeryRestaurant, 4, "A lovely breakfast selection and careful packaging.", 7)
    ensureReview("healthy-thiri", reviewerTwo, healthyRestaurant, 5, "Fresh ingredients and portions that actually feel satisfying.", 3)
    ensureReview("healthy-noah", reviewerOne, healthyRestaurant, 4, "The salmon salad is a reliable weekday lunch.", 11)

    ensureNotification("customer-accepted", customer, "Order accepted", "Golden Bowl Kitchen is preparing your meal.", "ORDER_STATUS", preparing, false, 8)
    ensureNotification("customer-rider", customer, "Rider assigned", "Leo is heading to the restaurant for your order.", "RIDER_ASSIGNED", assigned, false, 18)
    ensureNotification("customer-delivered", customer, "Delivered with care", "Your order was delivered. We hope every bite was wonderful!", "ORDER_STATUS", deliveredCod, true, 1440)
    ensureNotification("customer-refund", customer, "Payment refunded", "Your cancelled card payment has been returned.", "PAYMENT_STATUS", rejected, true, 6800)
    ensureNotification("owner-new", owner, "New COD order", "A new order is waiting for kitchen acceptance.", "NEW_ORDER", pending, false, 3)
    ensureNotification("owner-ready", owner, "Ready for pickup", "A nearby rider can now accept order #${available.toString().takeLast(8)}.", "ORDER_STATUS", available, true, 35)
    ensureNotification("rider-job", rider, "New delivery nearby", "Golden Bowl Kitchen has a delivery ready for pickup.", "DELIVERY_AVAILABLE", available, false, 4)
    ensureNotification("rider-cod", rider, "Remember to collect cash", "Collect $19.30 when completing this delivery.", "COD_REMINDER", travelling, false, 22)
    ensureNotification("rider-wallet", rider, "Wallet updated", "COD cash from a completed delivery is ready to settle.", "WALLET", deliveredCod, true, 1400)

    println("SwiftBite presentation data ready")
    println("  Customer: customer@swiftbite.demo / 111111")
    println("  Restaurant: owner@swiftbite.demo / 111111")
    println("  Rider: rider@swiftbite.demo / 111111")
    println("  Demo order states: pending, preparing, ready, assigned, out-for-delivery, delivered, rejected")
}
