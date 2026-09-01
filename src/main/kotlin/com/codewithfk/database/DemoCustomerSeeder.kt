package com.codewithfk.database

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.util.UUID
import com.codewithfk.utils.PasswordHasher

/** Adds a repeatable presentation account without replacing existing user data. */
fun seedDemoCustomer() {
    if (!UsersTable.select { UsersTable.email eq "customer@example.com" }.empty()) return

    val customerId = UUID.randomUUID()
    UsersTable.insert {
        it[id] = customerId
        it[email] = "customer@example.com"
        it[name] = "Alex Morgan"
        it[role] = "CUSTOMER"
        it[authProvider] = "email"
        it[passwordHash] = PasswordHasher.hash("111111")
        it[createdAt] = org.jetbrains.exposed.sql.javatime.CurrentDateTime
    }
    AddressesTable.insert {
        it[id] = UUID.randomUUID()
        it[userId] = customerId
        it[addressLine1] = "88 Swift Street"
        it[addressLine2] = "Apartment 4B"
        it[city] = "Yangon"
        it[state] = "Yangon Region"
        it[zipCode] = "11181"
        it[country] = "Myanmar"
        it[latitude] = 16.8409
        it[longitude] = 96.1735
    }
    println("Seeded demo customer: customer@example.com / 111111")
}
