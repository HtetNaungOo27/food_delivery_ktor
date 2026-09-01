package com.codewithfk.database

import com.codewithfk.database.migrations.updateOwnerPassword
import com.codewithfk.model.Category
import io.ktor.http.*
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import com.codewithfk.utils.PasswordHasher

object DatabaseFactory {
    fun init() {
        val driverClassName = "com.mysql.cj.jdbc.Driver"
        val jdbcURL = System.getenv("DB_URL") ?: "jdbc:mysql://localhost:3306/food_delivery"
        val user = System.getenv("DB_USER") ?: "root"
        val password = System.getenv("DB_PASSWORD")
            ?.takeIf { it.isNotBlank() }
            ?: error("DB_PASSWORD is missing or empty in the Ktor run configuration")

        try {
            Class.forName(driverClassName)
            Database.connect(jdbcURL, driverClassName, user, password)

            transaction {
                // Create base tables
                SchemaUtils.createMissingTablesAndColumns(
                    UsersTable,
                    AccountProfilesTable,
                    StripeWebhookEventsTable,
                    StripeRefundsTable,
                    PayoutAccountsTable,
                    PayoutsTable,
                    PasswordResetTokensTable,
                    CategoriesTable,
                    RestaurantsTable,
                    RestaurantHoursTable,
                    MenuItemsTable,
                    AddressesTable,
                    OrdersTable,
                    OrderItemsTable,
                    CartTable,
                    NotificationsTable,
                    RiderLocationsTable,
                    DeliveryRequestsTable,
                    RiderRejectionsTable,
                    ReviewsTable,
                    RiderSettlementsTable
                    ,NotificationOutboxTable,
                    PlatformSettingsTable,
                    DisputesTable,
                    AdminAuditLogsTable
                    ,CustomerFavoritesTable
                )

                // Check if rider_id column exists
                val riderIdExists = exec(
                    """
                                SELECT COUNT(*) 
                                FROM information_schema.COLUMNS 
                                WHERE TABLE_SCHEMA = DATABASE()
                                AND TABLE_NAME = 'orders' 
                                AND COLUMN_NAME = 'rider_id'
                            """
                ) { it.next(); it.getInt(1) } ?: 0 > 0

                // Add rider_id column if it doesn't exist
                if (!riderIdExists) {
                    exec(
                        """
                        ALTER TABLE orders 
                        ADD COLUMN rider_id VARCHAR(36) NULL;
                    """
                    )

                    // Add foreign key constraint
                    exec(
                        """
                        ALTER TABLE orders
                        ADD CONSTRAINT fk_orders_rider
                        FOREIGN KEY (rider_id) 
                        REFERENCES users(id);
                    """
                    )
                }
            }
        } catch (e: Exception) {
            println("Database initialization failed: ${e.message}")
            throw e
        }
    }
}

fun Application.migrateDatabase() {
    transaction {
        try {
            // Migration 1: Add FCM token to users
            val fcmTokenExists = exec(
                """
                SELECT COUNT(*) 
                FROM information_schema.COLUMNS 
                WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'users' 
                AND COLUMN_NAME = 'fcm_token'
            """
            ) { it.next(); it.getInt(1) } ?: 0 > 0

            if (!fcmTokenExists) {
                exec(
                    """
                    ALTER TABLE users 
                    ADD COLUMN fcm_token VARCHAR(255) NULL
                """
                )
                println("Added fcm_token column to users table")
            }

            // Migration 2: Add category to menu_items
            val categoryExists = exec(
                """
                SELECT COUNT(*) 
                FROM information_schema.COLUMNS 
                WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'menu_items' 
                AND COLUMN_NAME = 'category'
            """
            ) { it.next(); it.getInt(1) } ?: 0 > 0

            if (!categoryExists) {
                exec(
                    """
                    ALTER TABLE menu_items 
                    ADD COLUMN category VARCHAR(100) NULL
                """
                )
                println("Added category column to menu_items table")
            }

            // Migration 3: Add isAvailable to menu_items
            val isAvailableExists = exec(
                """
                SELECT COUNT(*) 
                FROM information_schema.COLUMNS 
                WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'menu_items' 
                AND COLUMN_NAME = 'is_available'
            """
            ) { it.next(); it.getInt(1) } ?: 0 > 0

            if (!isAvailableExists) {
                exec(
                    """
                    ALTER TABLE menu_items 
                    ADD COLUMN is_available BOOLEAN DEFAULT TRUE
                """
                )
                println("Added is_available column to menu_items table")
            }

            listOf(
                "landmark" to "ALTER TABLE addresses ADD COLUMN landmark VARCHAR(255) NULL",
                "plus_code" to "ALTER TABLE addresses ADD COLUMN plus_code VARCHAR(32) NULL"
            ).forEach { (column, statement) ->
                val exists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'addresses' AND COLUMN_NAME = '$column'") { it.next(); it.getInt(1) } ?: 0 > 0
                if (!exists) exec(statement)
            }
            listOf(
                "payment_method" to "ALTER TABLE orders ADD COLUMN payment_method VARCHAR(20) NOT NULL DEFAULT 'CARD'",
                "cod_collected" to "ALTER TABLE orders ADD COLUMN cod_collected BOOLEAN NOT NULL DEFAULT FALSE",
                "cod_cash_received" to "ALTER TABLE orders ADD COLUMN cod_cash_received DOUBLE NULL",
                "special_instructions" to "ALTER TABLE orders ADD COLUMN special_instructions VARCHAR(500) NULL",
                "rider_instructions" to "ALTER TABLE orders ADD COLUMN rider_instructions VARCHAR(500) NULL",
                "preparation_minutes" to "ALTER TABLE orders ADD COLUMN preparation_minutes INT NULL",
                "delivery_otp" to "ALTER TABLE orders ADD COLUMN delivery_otp VARCHAR(6) NULL",
                "commission_percentage" to "ALTER TABLE orders ADD COLUMN commission_percentage DOUBLE NOT NULL DEFAULT 10.0",
                "commission_amount" to "ALTER TABLE orders ADD COLUMN commission_amount DOUBLE NOT NULL DEFAULT 0.0"
            ).forEach { (column, statement) ->
                val exists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = '$column'") { it.next(); it.getInt(1) } ?: 0 > 0
                if (!exists) exec(statement)
            }
            // Reconstruct commission for valid orders created before commission
            // tracking. This is idempotent and preserves refunds and failed flows.
            exec(
                """
                UPDATE orders
                SET commission_amount = total_amount * commission_percentage / 100.0
                WHERE commission_amount = 0
                  AND commission_percentage > 0
                  AND total_amount > 0
                  AND UPPER(status) NOT IN ('CANCELLED', 'REJECTED', 'DELIVERY_FAILED', 'REFUNDED')
                """.trimIndent()
            )
            val restaurantOpenExists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'restaurants' AND COLUMN_NAME = 'is_open'") { it.next(); it.getInt(1) } ?: 0 > 0
            if (!restaurantOpenExists) exec("ALTER TABLE restaurants ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT TRUE")
            val userActiveExists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'is_active'") { it.next(); it.getInt(1) } ?: 0 > 0
            if (!userActiveExists) exec("ALTER TABLE users ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE")
            val restaurantApprovedExists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'restaurants' AND COLUMN_NAME = 'is_approved'") { it.next(); it.getInt(1) } ?: 0 > 0
            if (!restaurantApprovedExists) exec("ALTER TABLE restaurants ADD COLUMN is_approved BOOLEAN NOT NULL DEFAULT TRUE")
            listOf(
                "is_busy" to "ALTER TABLE restaurants ADD COLUMN is_busy BOOLEAN NOT NULL DEFAULT FALSE",
                "opens_at" to "ALTER TABLE restaurants ADD COLUMN opens_at VARCHAR(5) NOT NULL DEFAULT '08:00'",
                "closes_at" to "ALTER TABLE restaurants ADD COLUMN closes_at VARCHAR(5) NOT NULL DEFAULT '22:00'",
                "delivery_radius_km" to "ALTER TABLE restaurants ADD COLUMN delivery_radius_km DOUBLE NOT NULL DEFAULT 10.0",
                "minimum_order_amount" to "ALTER TABLE restaurants ADD COLUMN minimum_order_amount DOUBLE NOT NULL DEFAULT 0.0"
            ).forEach { (column, statement) ->
                val exists = exec("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'restaurants' AND COLUMN_NAME = '$column'") { it.next(); it.getInt(1) } ?: 0 > 0
                if (!exists) exec(statement)
            }

            println("All migrations completed successfully")
        } catch (e: Exception) {
            println("Migration failed: ${e.message}")
            throw e
        }
    }
}

fun Application.seedDatabase() {
    environment.monitor.subscribe(ApplicationStarted) {
        transaction {
            val adminEmail = System.getenv("ADMIN_EMAIL")?.trim()?.lowercase()
            val adminPassword = System.getenv("ADMIN_PASSWORD")
            if (!adminEmail.isNullOrBlank() && !adminPassword.isNullOrBlank() &&
                UsersTable.select { UsersTable.email eq adminEmail }.empty()
            ) {
                require(adminPassword.length >= 10) { "ADMIN_PASSWORD must contain at least 10 characters" }
                UsersTable.insert {
                    it[id] = UUID.randomUUID()
                    it[email] = adminEmail
                    it[name] = System.getenv("ADMIN_NAME")?.takeIf(String::isNotBlank) ?: "SwiftBite Administrator"
                    it[role] = "ADMIN"
                    it[authProvider] = "email"
                    it[passwordHash] = PasswordHasher.hash(adminPassword)
                    it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }
                println("Created administrator account from ADMIN_EMAIL")
            }
            val owner1Id = UsersTable.select { UsersTable.email eq "owner1@example.com" }.singleOrNull()?.get(UsersTable.id) ?: UUID.randomUUID()
            val owner2Id = UsersTable.select { UsersTable.email eq "owner2@example.com" }.singleOrNull()?.get(UsersTable.id) ?: UUID.randomUUID()
            val riderId = UsersTable.selectAll().firstOrNull { it[UsersTable.role].equals("rider", ignoreCase = true) }?.get(UsersTable.id) ?: UUID.randomUUID()
            // Seed users if none exist
            if (UsersTable.selectAll().empty()) {
                println("Seeding users...")


                // Insert owner1
                UsersTable.insert {
                    it[id] = owner1Id
                    it[email] = "owner1@example.com"
                    it[name] = "Aung Min"
                    it[role] = "OWNER"
                    it[authProvider] = "email"
                    it[passwordHash] = PasswordHasher.hash("111111")
                    it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }

                // Insert owner2
                UsersTable.insert {
                    it[id] = owner2Id
                    it[email] = "owner2@example.com"
                    it[name] = "Thiri Win"
                    it[role] = "OWNER"
                    it[authProvider] = "email"
                    it[passwordHash] = PasswordHasher.hash("111111")
                    it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }
            }

            if (UsersTable.selectAll().none { it[UsersTable.role].equals("rider", ignoreCase = true) }) {
                UsersTable.insert {
                    it[id] = riderId
                    it[email] = "rider@example.com"
                    it[name] = "Ko Zaw"
                    it[role] = "RIDER"
                    it[authProvider] = "email"
                    it[passwordHash] = PasswordHasher.hash("111111")
                    it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }

                println("Seeded default users: owner1@example.com, owner2@example.com, rider@example.com")
                println("Default password for all users: password123")

                // Add initial rider location
                RiderLocationsTable.insert {
                    it[this.riderId] = riderId
                    it[latitude] = 16.8210
                    it[longitude] = 96.1320
                    it[isAvailable] = true
                    it[lastUpdated] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                }
            }

            seedDemoCustomer()

            // Seed categories if none exist
            val categoryIds = if (CategoriesTable.selectAll().empty()) {
                println("Seeding categories...")
                val categories = listOf(
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Pizza",
                        imageUrl = "https://images.vexels.com/content/136312/preview/logo-pizza-fast-food-d65bfe.png"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Fast Food",
                        imageUrl = "https://www.pngarts.com/files/3/Fast-Food-Free-PNG-Image.png"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Beverages",
                        imageUrl = "https://www.pngfind.com/pngs/m/172-1729150_alcohol-drinks-png-mojito-drink-transparent-png.png"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Desserts",
                        imageUrl = "https://img.freepik.com/premium-psd/isolated-cake-style-png-with-white-background-generative-ia_209190-251177.jpg"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Healthy Food",
                        imageUrl = "https://png.pngtree.com/png-clipart/20190516/original/pngtree-healthy-food-png-image_3776802.jpg"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Asian Cuisine",
                        imageUrl = "https://e7.pngegg.com/pngimages/706/98/png-clipart/japanese-cuisine-chinese-cuisine-vietnamese-cuisine-asian-cuisine-dish-cooking-leaf-vegetable-food.png"
                    ),
                    Category(
                        id = UUID.randomUUID().toString(),
                        name = "Burger",
                        imageUrl = "https://png.pngtree.com/png-vector/20231016/ourmid/pngtree-burger-food-png-free-download-png-image_10199386.png"
                    )
                )

                categories.associate { category ->
                    val uuid = UUID.fromString(category.id)
                    CategoriesTable.insert {
                        it[id] = uuid
                        it[name] = category.name
                        it[imageUrl] = category.imageUrl
                        it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
                    }
                    category.name to uuid
                }
            } else {
                CategoriesTable.selectAll().associate { it[CategoriesTable.name] to it[CategoriesTable.id] }
            }

            // Seed restaurants if none exist
            if (RestaurantsTable.selectAll().empty()) {
                println("Seeding restaurants...")
                val restaurants = listOf(
                    Triple(
                        Pair(
                            "Yangon Pizza House",
                            "https://www.marthastewart.com/thmb/3N-0cJgJfLDyytnCehJd4aVgHJw=/1500x0/filters:no_upscale():max_bytes(150000):strip_icc()/white-pizza-172-d112100_horiz-c868dcf28ed44b21af90f11797d6d7d6.jpgitokKoRSmCVm"
                        ),
                        "No. 12, Inya Road, Kamayut, Yangon",
                        Triple(16.8181, 96.1312, "Pizza")
                    ),
                    Triple(
                        Pair(
                            "Hledan Burger House",
                            "https://imageproxy.wolt.com/mes-image/43bb7be3-03c2-4337-9d52-99cba2b1650d/85493202-0013-44f0-b7c1-59262d53e9ff"
                        ),
                        "Hledan Road, Kamayut, Yangon",
                        Triple(16.8283, 96.1294, "Fast Food")
                    ),
                    Triple(
                        Pair(
                            "Shwe Dessert House",
                            "https://static.vecteezy.com/system/resources/previews/032/160/853/large_2x/mouthwatering-dessert-heaven-a-tray-of-assorted-creamy-delights-ai-generated-photo.jpg"
                        ),
                        "Baho Road, Sanchaung, Yangon",
                        Triple(16.8069, 96.1336, "Desserts")
                    ),
                    Triple(
                        Pair(
                            "Yangon Green Bowl",
                            "https://i2.wp.com/www.downshiftology.com/wp-content/uploads/2019/04/Cobb-Salad-main.jpg"
                        ),
                        "Pyay Road, Sanchaung, Yangon",
                        Triple(16.8090, 96.1352, "Healthy Food")
                    ),
                    Triple(
                        Pair(
                            "Sakura Yangon",
                            "https://tb-static.uber.com/prod/image-proc/processed_images/87baf961b666795ea98160dc3b1d465c/fb86662148be855d931b37d6c1e5fcbe.jpeg"
                        ),
                        "Dhammazedi Road, Bahan, Yangon",
                        Triple(16.8125, 96.1450, "Asian Cuisine")
                    ),
                    Triple(
                        Pair(
                            "Rangoon Tea and Coffee",
                            "https://insanelygoodrecipes.com/wp-content/uploads/2020/07/Cup-Of-Creamy-Coffee.png"
                        ),
                        "University Avenue Road, Kamayut, Yangon",
                        Triple(16.8254, 96.1346, "Beverages")
                    )
                )

                RestaurantsTable.batchInsert(restaurants) { restaurant ->
                    this[RestaurantsTable.id] = UUID.randomUUID()
                    this[RestaurantsTable.ownerId] = owner1Id
                    this[RestaurantsTable.name] = restaurant.first.first
                    this[RestaurantsTable.address] = restaurant.second
                    this[RestaurantsTable.latitude] = restaurant.third.first
                    this[RestaurantsTable.longitude] = restaurant.third.second
                    this[RestaurantsTable.imageUrl] = restaurant.first.second
                    this[RestaurantsTable.categoryId] =
                        categoryIds[restaurant.third.third] ?: error("Category not found: ${restaurant.third.third}")
                    this[RestaurantsTable.createdAt] = org.jetbrains.exposed.sql.javatime.CurrentTimestamp()
                }

                println("Restaurants seeded: ${restaurants.map { it.first }}")
            }

            // Seed menu items if none exist
            if (MenuItemsTable.selectAll().empty()) {
                println("Seeding menu items...")
                val restaurants =
                    RestaurantsTable.selectAll().associate { it[RestaurantsTable.name] to it[RestaurantsTable.id] }

                val menuItems = listOf(
                    Pair(
                        "Yangon Pizza House", listOf(
                            Triple(
                                "Margherita Pizza", "Classic cheese pizza with fresh basil",
                                Pair(12.99, "https://foodbyjonister.com/wp-content/uploads/2020/01/pizzadough18.jpg")
                            ),
                            Triple(
                                "Pepperoni Pizza", "Pepperoni, mozzarella, and marinara sauce",
                                Pair(
                                    14.99,
                                    "https://www.cobsbread.com/us/wp-content//uploads/2022/09/Pepperoni-pizza-850x630-1.png"
                                )
                            ),
                            Triple(
                                "Veggie Supreme", "Loaded with bell peppers, onions, and olives",
                                Pair(
                                    13.99,
                                    "https://www.thecandidcooks.com/wp-content/uploads/2022/07/california-veggie-pizza-feature.jpg"
                                )
                            ),
                            Triple(
                                "Special Pizza", "Classic cheese pizza with fresh basil",
                                Pair(
                                    21.99,
                                    "https://eatlanders.com/wp-content/uploads/2021/05/new-pizza-pic-e1672671486218.jpeg"
                                )
                            ),
                            Triple(
                                "Crown Crust Pizza", "Pepperoni, mozzarella, and marinara sauce",
                                Pair(19.99, "https://wenewsenglish.pk/wp-content/uploads/2024/05/Recipe-1.jpg")
                            ),
                            Triple(
                                "Thin Crust Supreme", "Loaded with bell peppers, onions, and olives",
                                Pair(
                                    18.99,
                                    "https://cdn.apartmenttherapy.info/image/upload/f_jpg,q_auto:eco,c_fill,g_auto,w_1500,ar_4:3/k%2Farchive%2Fcb2e9502cd9da3468caa944e15527b19bce68a8e"
                                )
                            ),
                            Triple(
                                "Malai Boti Pizza", "Classic cheese pizza with fresh basil",
                                Pair(
                                    14.99,
                                    "https://www.tastekahani.com/wp-content/uploads/2022/05/71.Malai-Boti-Pizza.jpg"
                                )
                            ),
                            Triple(
                                "Tikka Pizza", "Pepperoni, mozzarella, and marinara sauce",
                                Pair(
                                    16.99,
                                    "https://onestophalal.com/cdn/shop/articles/tikka_masala_pizza-1694014914105_1200x.jpg?v=1694568363"
                                )
                            ),
                            Triple(
                                "Cheeze Crust Supreme", "Loaded with bell peppers, onions, and olives",
                                Pair(
                                    17.99,
                                    "https://www.allrecipes.com/thmb/ofh4mVETQPBbcOb4uCFQr92cqb4=/1500x0/filters:no_upscale():max_bytes(150000):strip_icc()/2612551-cheesy-crust-skillet-pizza-The-Gruntled-Gourmand-1x1-1-f9a328af9dfe487a9fc408f581927696.jpg"
                                )
                            )
                        )
                    ),
                    Pair(
                        "Hledan Burger House", listOf(
                            Triple(
                                "Classic Cheeseburger", "Juicy beef patty with cheddar cheese",
                                Pair(
                                    10.99,
                                    "https://rhubarbandcod.com/wp-content/uploads/2022/06/The-Classic-Cheeseburger-1.jpg"
                                )
                            ),
                            Triple(
                                "Veggie Burger", "Grilled veggie patty with avocado",
                                Pair(
                                    9.99,
                                    "https://www.foodandwine.com/thmb/pwFie7NRkq4SXMDJU6QKnUKlaoI=/1500x0/filters:no_upscale():max_bytes(150000):strip_icc()/Ultimate-Veggie-Burgers-FT-Recipe-0821-5d7532c53a924a7298d2175cf1d4219f.jpg"
                                )
                            )
                        )
                    )
                )

                MenuItemsTable.batchInsert(menuItems.flatMap { (restaurantName, items) ->
                    val restaurantId = restaurants[restaurantName] ?: error("Restaurant not found: $restaurantName")
                    items.map { menuItem ->
                        Triple(restaurantId, menuItem.first, menuItem.second to menuItem.third)
                    }
                }) { menuItem ->
                    this[MenuItemsTable.id] = UUID.randomUUID()
                    this[MenuItemsTable.restaurantId] = menuItem.first
                    this[MenuItemsTable.name] = menuItem.second
                    this[MenuItemsTable.description] = menuItem.third.first
                    this[MenuItemsTable.price] = menuItem.third.second.first
                    this[MenuItemsTable.imageUrl] = menuItem.third.second.second
                    this[MenuItemsTable.arModelUrl] = null
                    this[MenuItemsTable.category] = null // New field
                    this[MenuItemsTable.isAvailable] = true // New field
                    this[MenuItemsTable.createdAt] = org.jetbrains.exposed.sql.javatime.CurrentTimestamp()
                }

                println("Menu items seeded for all restaurants.")
            }

            // Localize only the original tutorial seed rows. User-created records are untouched.
            listOf(
                arrayOf("Pizza Palace", "Yangon Pizza House", "No. 12, Inya Road, Kamayut, Yangon", "16.8181", "96.1312"),
                arrayOf("Burger Haven", "Hledan Burger House", "Hledan Road, Kamayut, Yangon", "16.8283", "96.1294"),
                arrayOf("Dessert Delight", "Shwe Dessert House", "Baho Road, Sanchaung, Yangon", "16.8069", "96.1336"),
                arrayOf("Healthy Bites", "Yangon Green Bowl", "Pyay Road, Sanchaung, Yangon", "16.8090", "96.1352"),
                arrayOf("Sushi Express", "Sakura Yangon", "Dhammazedi Road, Bahan, Yangon", "16.8125", "96.1450"),
                arrayOf("Coffee Corner", "Rangoon Tea and Coffee", "University Avenue Road, Kamayut, Yangon", "16.8254", "96.1346")
            ).forEach { seed ->
                RestaurantsTable.update({ RestaurantsTable.name eq seed[0] }) {
                    it[name] = seed[1]
                    it[address] = seed[2]
                    it[latitude] = seed[3].toDouble()
                    it[longitude] = seed[4].toDouble()
                }
            }
            RiderLocationsTable.select { RiderLocationsTable.riderId eq riderId }
                .filter { it[RiderLocationsTable.latitude] !in 9.0..29.0 || it[RiderLocationsTable.longitude] !in 92.0..102.0 }
                .forEach { row ->
                    RiderLocationsTable.update({ RiderLocationsTable.id eq row[RiderLocationsTable.id] }) {
                        it[latitude] = 16.8210
                        it[longitude] = 96.1320
                    }
                }

            // Repair legacy/demo Yangon addresses that had missing or non-Myanmar coordinates.
            AddressesTable.selectAll()
                .filter { row ->
                    val lat = row[AddressesTable.latitude]
                    val lon = row[AddressesTable.longitude]
                    row[AddressesTable.city].contains("Yangon", ignoreCase = true) &&
                        (lat == null || lon == null || lat !in 16.45..17.20 || lon !in 95.75..96.55)
                }
                .forEachIndexed { index, row ->
                    AddressesTable.update({ AddressesTable.id eq row[AddressesTable.id] }) {
                        it[city] = "Yangon"
                        it[state] = "Yangon Region"
                        it[country] = "Myanmar"
                        it[latitude] = 16.8176 + (index % 5) * 0.002
                        it[longitude] = 96.1302 + (index % 4) * 0.002
                    }
                }

            seedPresentationDemo()
        }
    }
}
