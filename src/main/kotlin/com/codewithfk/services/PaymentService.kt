package com.codewithfk.services

import com.codewithfk.configs.StripeConfig
import com.codewithfk.model.PaymentIntentResponse
import com.codewithfk.model.PlaceOrderRequest
import com.stripe.Stripe
import com.stripe.model.PaymentIntent
import com.stripe.model.Event
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PaymentIntentConfirmParams
import java.util.*
import com.stripe.model.EphemeralKey
import com.stripe.model.Customer
import com.codewithfk.model.PaymentSheetResponse
import com.stripe.net.RequestOptions
import com.stripe.model.Refund
import com.codewithfk.database.StripeRefundsTable
import com.codewithfk.database.StripeWebhookEventsTable
import com.codewithfk.database.OrdersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.javatime.CurrentDateTime

object PaymentService {
    init {
        Stripe.apiKey = StripeConfig.secretKey
    }

    fun createPaymentIntent(userId: UUID, addressId: UUID, idempotencyKey: String, fulfillmentType: String = "DELIVERY", scheduledFor: String? = null): PaymentIntentResponse {
        try {
            // Get cart total
            val checkoutDetails = OrderService.getCheckoutDetails(userId, fulfillmentType)
            require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }
            val amountInMinorUnits = (checkoutDetails.totalAmount * 1000).toLong()

            // Get or create customer
            val customer = getOrCreateCustomer(userId)

            // Create ephemeral key
            val requestOptions = RequestOptions.builder()
                .build()

            val ephemeralKey = EphemeralKey.create(
                mapOf(
                    "customer" to customer,
                    "stripe-version" to "2020-08-27"  // API version for Stripe Android SDK 20.35.0
                ),
                requestOptions
            )

            // Create payment intent
            val paramsBuilder = PaymentIntentCreateParams.builder()
                .setAmount(amountInMinorUnits)
                .setCurrency("mmk")
                .setCustomer(customer)
                .putMetadata("userId", userId.toString())
                .putMetadata("addressId", addressId.toString())
                .putMetadata("fulfillmentType", fulfillmentType)
                .also { builder -> scheduledFor?.let { builder.putMetadata("scheduledFor", it) } }
                .setAutomaticPaymentMethods(
                    PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build()
                )

            val paymentIntent = PaymentIntent.create(
                paramsBuilder.build(),
                RequestOptions.builder().setIdempotencyKey("intent:$userId:$idempotencyKey").build()
            )

            return PaymentIntentResponse(
                paymentIntentClientSecret = paymentIntent.clientSecret,
                paymentIntentId = paymentIntent.id,
                customerId = customer,
                ephemeralKeySecret = ephemeralKey.secret,
                publishableKey = StripeConfig.publishableKey,
                amount = amountInMinorUnits,
                currency = "mmk",
                status = paymentIntent.status
            )
        } catch (e: Exception) {
            throw IllegalStateException("Error creating payment intent: ${e.message}")
        }
    }

    fun handleWebhook(payload: String, sigHeader: String): Boolean {
        val event = try {
            com.stripe.net.Webhook.constructEvent(
                payload,
                sigHeader,
                StripeConfig.webhookSecret
            )
        } catch (e: Exception) {
            throw IllegalStateException("Webhook signature verification failed")
        }
        val shouldProcess = transaction {
            val existing = StripeWebhookEventsTable.select { StripeWebhookEventsTable.eventId eq event.id }.singleOrNull()
            if (existing?.get(StripeWebhookEventsTable.status) == "PROCESSED") false
            else {
                StripeWebhookEventsTable.insertIgnore {
                    it[eventId] = event.id; it[eventType] = event.type; it[status] = "PROCESSING"
                }
                StripeWebhookEventsTable.update({ StripeWebhookEventsTable.eventId eq event.id }) {
                    it[status] = "PROCESSING"; it[error] = null
                }
                true
            }
        }
        if (!shouldProcess) return true
        try {

            when (event.type) {
                "payment_intent.succeeded" -> {
                    val paymentIntent = event.dataObjectDeserializer.`object`.get() as PaymentIntent
                    handleSuccessfulPayment(paymentIntent)
                    println("Webhook: Payment succeeded for intent ${paymentIntent.id}")
                }
                "payment_intent.payment_failed" -> {
                    val paymentIntent = event.dataObjectDeserializer.`object`.get() as PaymentIntent
                    handleFailedPayment(paymentIntent)
                    println("Webhook: Payment failed for intent ${paymentIntent.id}")
                }
                "refund.created", "refund.updated", "refund.failed" -> {
                    val refund = event.dataObjectDeserializer.`object`.orElseThrow() as Refund
                    reconcileRefund(refund)
                }
            }
            transaction { StripeWebhookEventsTable.update({ StripeWebhookEventsTable.eventId eq event.id }) { it[status] = "PROCESSED"; it[processedAt] = CurrentDateTime } }
            return true
        } catch (e: Exception) {
            transaction { StripeWebhookEventsTable.update({ StripeWebhookEventsTable.eventId eq event.id }) { it[status] = "FAILED"; it[error] = e.message?.take(1000) } }
            println("Webhook error: ${e.message}")
            throw IllegalStateException("Webhook handling failed: ${e.message}")
        }
    }

    private fun handleSuccessfulPayment(paymentIntent: PaymentIntent) {
        try {
            if (OrderService.getOrderByPaymentIntentId(paymentIntent.id) != null) {
                println("Webhook replay ignored for intent ${paymentIntent.id}")
                return
            }
            val userId = UUID.fromString(paymentIntent.metadata["userId"])
                ?: throw IllegalStateException("User ID not found in payment intent metadata")
            val addressId = UUID.fromString(paymentIntent.metadata["addressId"])
                ?: throw IllegalStateException("Address ID not found in payment intent metadata")

            println("Creating order for userId: $userId, addressId: $addressId, paymentIntentId: ${paymentIntent.id}")

            OrderService.placeOrder(
                userId = userId,
                request = PlaceOrderRequest(
                    addressId = addressId.toString(),
                    fulfillmentType = paymentIntent.metadata["fulfillmentType"] ?: "DELIVERY",
                    scheduledFor = paymentIntent.metadata["scheduledFor"]
                ),
                paymentIntentId = paymentIntent.id
            )

            println("Order created successfully")
        } catch (e: Exception) {
            if (OrderService.getOrderByPaymentIntentId(paymentIntent.id) != null) return
            println("Error handling successful payment: ${e.message}")
            throw IllegalStateException("Error handling successful payment: ${e.message}")
        }
    }

    private fun handleFailedPayment(paymentIntent: PaymentIntent) {
        println("Payment failed for intent: ${paymentIntent.id}")
        println("Failure message: ${paymentIntent.lastPaymentError?.message}")
    }

    fun createPaymentSheet(userId: UUID, addressId: UUID, idempotencyKey: String, fulfillmentType: String = "DELIVERY"): PaymentSheetResponse {
        try {
            // Get cart total
            val checkoutDetails = OrderService.getCheckoutDetails(userId, fulfillmentType)
            require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }
            val amountInMinorUnits = (checkoutDetails.totalAmount * 1000).toLong()

            // Get or create customer
            val customer = getOrCreateCustomer(userId)

            // Create ephemeral key with proper request options
            val requestOptions = RequestOptions.builder()
                .build()

            val ephemeralKey = EphemeralKey.create(
                mapOf(
                    "customer" to customer,
                    "stripe-version" to "2020-08-27"  // API version for Stripe Android SDK 20.35.0
                ),
                requestOptions
            )

            // Create payment intent
            val paymentIntent = PaymentIntent.create(
                PaymentIntentCreateParams.builder()
                    .setAmount(amountInMinorUnits)
                    .setCurrency("mmk")
                    .setCustomer(customer)
                    .putMetadata("userId", userId.toString())
                    .putMetadata("addressId", addressId.toString())
                    .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .build()
                    )
                    .build(),
                RequestOptions.builder().setIdempotencyKey("sheet:$userId:$idempotencyKey").build()
            )

            return PaymentSheetResponse(
                paymentIntent = paymentIntent.clientSecret,
                ephemeralKey = ephemeralKey.secret,
                customer = customer,
                publishableKey = StripeConfig.publishableKey
            )
        } catch (e: Exception) {
            throw IllegalStateException("Error creating payment sheet: ${e.message}")
        }
    }

    fun refundOnce(paymentIntentId: String, orderId: UUID, reason: String = "Order cancelled") {
        val amountMinor = transaction {
            val order = OrdersTable.select { OrdersTable.id eq orderId }.singleOrNull() ?: error("Order not found")
            (order[OrdersTable.totalAmount] * 1000).toLong()
        }
        val already = transaction { StripeRefundsTable.select { StripeRefundsTable.orderId eq orderId }.singleOrNull() }
        if (already?.get(StripeRefundsTable.status) == "SUCCEEDED") return
        transaction {
            StripeRefundsTable.insertIgnore {
                it[StripeRefundsTable.orderId] = orderId; it[this.paymentIntentId] = paymentIntentId
                it[this.amountMinor] = amountMinor; it[this.reason] = reason.take(500); it[status] = "PENDING"
            }
        }
        val refund = Refund.create(
            mapOf("payment_intent" to paymentIntentId),
            RequestOptions.builder().setIdempotencyKey("refund:$orderId").build()
        )
        transaction {
            StripeRefundsTable.update({ StripeRefundsTable.orderId eq orderId }) {
                it[stripeRefundId] = refund.id; it[status] = refund.status?.uppercase() ?: "PENDING"; it[updatedAt] = CurrentDateTime
            }
        }
    }

    private fun reconcileRefund(refund: Refund) = transaction {
        val paymentIntent = refund.paymentIntent ?: return@transaction
        val normalized = when (refund.status?.lowercase()) { "succeeded" -> "SUCCEEDED"; "failed", "canceled" -> "FAILED"; else -> "PENDING" }
        StripeRefundsTable.update({ StripeRefundsTable.paymentIntentId eq paymentIntent }) {
            it[stripeRefundId] = refund.id; it[status] = normalized; it[failureReason] = refund.failureReason; it[updatedAt] = CurrentDateTime
        }
        OrdersTable.update({ OrdersTable.stripePaymentIntentId eq paymentIntent }) {
            it[paymentStatus] = when (normalized) { "SUCCEEDED" -> "REFUNDED"; "FAILED" -> "REFUND_FAILED"; else -> "REFUND_PENDING" }
            it[updatedAt] = CurrentDateTime
        }
    }

    private fun getOrCreateCustomer(userId: UUID): String {
        val user = AuthService.getUserEmailFromID(userId)
            ?: throw IllegalStateException("User not found")

        val existingCustomers = Customer.list(
            mapOf("email" to user)
        )

        if (existingCustomers.data.isNotEmpty()) {
            return existingCustomers.data[0].id
        }

        val customer = Customer.create(
            mapOf(
                "metadata" to mapOf("userId" to userId.toString()),
                "email" to user,
            )
        )

        return customer.id
    }

    fun verifyAndGetPaymentIntent(userId: UUID, paymentIntentId: String): PaymentIntent {
        val paymentIntent = PaymentIntent.retrieve(paymentIntentId)

        // Verify ownership
        if (paymentIntent.metadata["userId"] != userId.toString()) {
            throw IllegalStateException("Payment intent does not belong to this user")
        }

        return paymentIntent
    }
}
