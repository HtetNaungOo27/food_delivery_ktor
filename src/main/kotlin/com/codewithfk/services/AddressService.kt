package com.codewithfk.services

import com.codewithfk.database.AddressesTable
import com.codewithfk.database.OrdersTable
import com.codewithfk.model.Address
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object AddressService {

    fun addAddress(address: Address): UUID {
        return transaction {
            AddressesTable.insert {
                it[userId] = UUID.fromString(address.userId)
                it[addressLine1] = address.addressLine1
                it[addressLine2] = address.addressLine2
                it[city] = address.city
                it[state] = address.state
                it[zipCode] = address.zipCode
                it[country] = address.country
                it[latitude] = address.latitude
                it[longitude] = address.longitude
                it[landmark] = address.landmark
                it[plusCode] = address.plusCode
            } get AddressesTable.id
        }
    }

    fun getAddressesByUser(userId: UUID): List<Address> {
        return transaction {
            AddressesTable.select { AddressesTable.userId eq userId }
                .map {
                    Address(
                        id = it[AddressesTable.id].toString(),
                        userId = it[AddressesTable.userId].toString(),
                        addressLine1 = it[AddressesTable.addressLine1],
                        addressLine2 = it[AddressesTable.addressLine2],
                        city = it[AddressesTable.city],
                        state = it[AddressesTable.state],
                        zipCode = it[AddressesTable.zipCode],
                        country = it[AddressesTable.country],
                        latitude = it[AddressesTable.latitude],
                        longitude = it[AddressesTable.longitude],
                        landmark = it[AddressesTable.landmark],
                        plusCode = it[AddressesTable.plusCode]
                    )
                }
        }
    }

    fun updateAddress(addressId: UUID, updatedAddress: Address): Boolean {
        return transaction {
            check(!OrdersTable.select { OrdersTable.addressId eq addressId }.any()) {
                "Addresses used by an order are immutable; create a new address instead"
            }
            AddressesTable.update({ AddressesTable.id eq addressId }) {
                it[addressLine1] = updatedAddress.addressLine1
                it[addressLine2] = updatedAddress.addressLine2
                it[city] = updatedAddress.city
                it[state] = updatedAddress.state
                it[zipCode] = updatedAddress.zipCode
                it[country] = updatedAddress.country
                it[latitude] = updatedAddress.latitude
                it[longitude] = updatedAddress.longitude
                it[landmark] = updatedAddress.landmark
                it[plusCode] = updatedAddress.plusCode
            } > 0
        }
    }

    fun deleteAddress(addressId: UUID): Boolean {
        return transaction {
            check(!OrdersTable.select { OrdersTable.addressId eq addressId }.any()) {
                "Addresses used by an order cannot be deleted"
            }
            AddressesTable.deleteWhere { AddressesTable.id eq addressId } > 0
        }
    }

    fun getAddressById(addressId: UUID): Address? {
        return transaction {
            AddressesTable.select { AddressesTable.id eq addressId }
                .map {
                    Address(
                        id = it[AddressesTable.id].toString(),
                        userId = it[AddressesTable.userId].toString(),
                        addressLine1 = it[AddressesTable.addressLine1],
                        addressLine2 = it[AddressesTable.addressLine2],
                        city = it[AddressesTable.city],
                        state = it[AddressesTable.state],
                        zipCode = it[AddressesTable.zipCode],
                        country = it[AddressesTable.country],
                        latitude = it[AddressesTable.latitude],
                        longitude = it[AddressesTable.longitude],
                        landmark = it[AddressesTable.landmark],
                        plusCode = it[AddressesTable.plusCode]
                    )
                }.singleOrNull()
        }
    }

    fun createDefaultAddress(userId: UUID) {
        transaction {
            AddressesTable.insert {
                it[AddressesTable.userId] = (userId)
                it[addressLine1] = "Set your delivery address"
                it[city] = "Yangon"
                it[state] = "Yangon Region"
                it[zipCode] = "11181"
                it[country] = "Myanmar"
                it[latitude] = 16.8409
                it[longitude] = 96.1735
            } get AddressesTable.id
        }
    }
}
