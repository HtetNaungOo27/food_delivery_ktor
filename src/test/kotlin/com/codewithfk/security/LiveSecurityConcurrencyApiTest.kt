package com.codewithfk.security

import com.stripe.Stripe
import com.stripe.model.Refund
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Black-box security tests for a disposable, seeded SwiftBite QA environment.
 *
 * Tests never contain credentials. Set SWIFTBITE_QA_BASE_URL and only the fixture variables
 * required by the cases being executed. Missing case-specific fixtures skip that case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
class LiveSecurityConcurrencyApiTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun connectToQaEnvironment() {
        val configured = System.getenv("SWIFTBITE_QA_BASE_URL")
        assumeTrue(!configured.isNullOrBlank(), "Set SWIFTBITE_QA_BASE_URL to run live API tests")
        baseUrl = configured!!.trimEnd('/')
        client = HttpClient(CIO) { expectSuccess = false }
    }

    @AfterAll
    fun closeClient() {
        if (::client.isInitialized) client.close()
    }

    @Test
    @Order(1)
    fun `customer token cannot use owner or rider APIs`() = runBlocking {
        val customer = fixture("QA_CUSTOMER_A_TOKEN")
        listOf(
            request(HttpMethod.Get, "/restaurant-owner/profile", customer),
            request(HttpMethod.Get, "/restaurant-owner/orders", customer),
            request(HttpMethod.Get, "/rider/wallet", customer),
            request(HttpMethod.Get, "/rider/deliveries/available", customer)
        ).forEach { response ->
            assertTrue(response.status in setOf(401, 403), "Expected role denial, got ${response.status}: ${response.body}")
        }
    }

    @Test
    @Order(2)
    fun `owner A cannot mutate owner B menu or profile`() = runBlocking {
        val ownerA = fixture("QA_OWNER_A_TOKEN")
        val ownerB = fixture("QA_OWNER_B_TOKEN")
        val ownerBItem = fixture("QA_OWNER_B_MENU_ITEM_ID")

        val menuAttempt = request(
            HttpMethod.Patch,
            "/restaurant-owner/menu/$ownerBItem",
            ownerA,
            """{"price":1.0}"""
        )
        assertTrue(menuAttempt.status in setOf(403, 404), "Cross-owner menu mutation succeeded: ${menuAttempt.body}")

        val beforeB = request(HttpMethod.Get, "/restaurant-owner/profile", ownerB)
        assertEquals(200, beforeB.status)
        val marker = "QA-A-${UUID.randomUUID()}"
        val ownerAUpdate = request(
            HttpMethod.Put,
            "/restaurant-owner/profile",
            ownerA,
            """{"name":"$marker"}"""
        )
        assertEquals(200, ownerAUpdate.status, ownerAUpdate.body)
        val afterB = request(HttpMethod.Get, "/restaurant-owner/profile", ownerB)
        assertEquals(200, afterB.status)
        assertEquals(beforeB.json()["id"], afterB.json()["id"])
        assertEquals(beforeB.json()["name"], afterB.json()["name"], "Owner A changed Owner B profile")
    }

    @Test
    @Order(3)
    fun `customer B cannot mutate customer A cart`() = runBlocking {
        val customerB = fixture("QA_CUSTOMER_B_TOKEN")
        val customerAItem = fixture("QA_CUSTOMER_A_CART_ITEM_ID")
        val update = request(
            HttpMethod.Patch,
            "/cart",
            customerB,
            """{"cartItemId":"$customerAItem","quantity":9}"""
        )
        assertTrue(update.status in setOf(403, 404), "Cross-customer cart update succeeded: ${update.body}")
        val delete = request(HttpMethod.Delete, "/cart/$customerAItem", customerB)
        assertTrue(delete.status in setOf(403, 404), "Cross-customer cart delete succeeded: ${delete.body}")
    }

    @Test
    @Order(4)
    fun `terminal delivered and cancelled orders are immutable`() = runBlocking {
        val owner = fixture("QA_TERMINAL_ORDER_OWNER_TOKEN")
        val rider = fixture("QA_TERMINAL_ORDER_RIDER_TOKEN")
        val delivered = fixture("QA_DELIVERED_ORDER_ID")
        val cancelled = fixture("QA_CANCELLED_ORDER_ID")

        val attempts = listOf(
            request(HttpMethod.Patch, "/restaurant-owner/orders/$delivered/status", owner, """{"status":"PREPARING"}"""),
            request(HttpMethod.Post, "/restaurant-owner/orders/$delivered/action", owner, """{"action":"ACCEPT"}"""),
            request(HttpMethod.Post, "/rider/deliveries/$delivered/status", rider, """{"status":"DELIVERED","cashReceived":999999}"""),
            request(HttpMethod.Patch, "/restaurant-owner/orders/$cancelled/status", owner, """{"status":"PREPARING"}"""),
            request(HttpMethod.Post, "/restaurant-owner/orders/$cancelled/action", owner, """{"action":"ACCEPT"}""")
        )
        attempts.forEach { response ->
            assertTrue(response.status in setOf(400, 404, 409), "Terminal order mutated: ${response.status} ${response.body}")
        }
    }

    @Test
    @Order(5)
    fun `five owner accept requests produce one winner`() = runBlocking {
        val owner = fixture("QA_PENDING_ORDER_OWNER_TOKEN")
        val orderId = fixture("QA_PENDING_ORDER_ID")
        val results = burst(5) {
            request(HttpMethod.Post, "/restaurant-owner/orders/$orderId/action", owner, """{"action":"ACCEPT"}""")
        }
        assertExactlyOneSuccess("owner accept", results)
    }

    @Test
    @Order(6)
    fun `five rider accept requests assign exactly one rider`() = runBlocking {
        val orderId = fixture("QA_READY_ORDER_ID")
        val configuredTokens = fixture("QA_RIDER_TOKENS").split(',').map(String::trim).filter(String::isNotBlank)
        assumeTrue(configuredTokens.isNotEmpty(), "QA_RIDER_TOKENS must contain at least one Rider token")
        val tokens = List(5) { configuredTokens[it % configuredTokens.size] }
        val results = tokens.map { token ->
            async(Dispatchers.IO) { request(HttpMethod.Post, "/rider/deliveries/$orderId/accept", token) }
        }.awaitAll()
        assertExactlyOneSuccess("rider assignment", results)
    }

    @Test
    @Order(7)
    fun `five COD checkout requests create only one order`() = runBlocking {
        val customer = fixture("QA_CHECKOUT_CUSTOMER_TOKEN")
        val address = fixture("QA_CHECKOUT_ADDRESS_ID")
        val before = customerOrders(customer).mapNotNull { it.string("id") }.toSet()
        val key = "qa-checkout-${UUID.randomUUID()}"
        val body = """{"addressId":"$address","paymentMethod":"COD","idempotencyKey":"$key"}"""
        val results = burst(5) { request(HttpMethod.Post, "/orders", customer, body) }
        assertTrue(results.count { it.isSuccess } >= 1, "No checkout request succeeded: $results")
        val after = customerOrders(customer).mapNotNull { it.string("id") }.toSet()
        assertEquals(1, (after - before).size, "Concurrent checkout created more than one order")
        val returnedIds = results.filter { it.isSuccess }.mapNotNull { it.jsonOrNull()?.string("id") }.toSet()
        assertTrue(returnedIds.size <= 1, "Idempotent responses returned different orders: $returnedIds")
    }

    @Test
    @Order(8)
    fun `five COD settlement requests create one settlement`() = runBlocking {
        val rider = fixture("QA_SETTLEMENT_RIDER_TOKEN")
        val before = request(HttpMethod.Get, "/rider/wallet", rider)
        assertEquals(200, before.status, before.body)
        val beforeWallet = before.json()
        val beforeCount = beforeWallet.array("settlements").size
        val amountBefore = beforeWallet.double("amountToSettle")
        assumeTrue(amountBefore > 0.0, "Settlement fixture must have collected COD cash")

        burst(5) { request(HttpMethod.Post, "/rider/wallet/settle", rider) }
        val after = request(HttpMethod.Get, "/rider/wallet", rider)
        assertEquals(200, after.status, after.body)
        val afterWallet = after.json()
        assertEquals(0.0, afterWallet.double("amountToSettle"), 0.0001)
        assertEquals(beforeCount + 1, afterWallet.array("settlements").size, "COD cash was settled more than once")
    }

    @Test
    @Order(9)
    fun `Stripe confirmation replay returns one order`() = runBlocking {
        val customer = fixture("QA_STRIPE_CUSTOMER_TOKEN")
        val paymentIntent = fixture("QA_SUCCEEDED_PAYMENT_INTENT_ID")
        val first = request(HttpMethod.Post, "/payments/confirm/$paymentIntent", customer)
        val second = request(HttpMethod.Post, "/payments/confirm/$paymentIntent", customer)
        assertEquals(200, first.status, first.body)
        assertEquals(200, second.status, second.body)
        assertEquals(first.json().string("orderId"), second.json().string("orderId"), "Replay created another order")
        assertEquals(
            1,
            customerOrders(customer).count { it.string("stripePaymentIntentId") == paymentIntent },
            "More than one order references the PaymentIntent"
        )
    }

    @Test
    @Order(10)
    fun `Stripe webhook replay creates one order`() = runBlocking {
        val customer = fixture("QA_STRIPE_CUSTOMER_TOKEN")
        val intent = fixture("QA_WEBHOOK_PAYMENT_INTENT_ID")
        val payload = Files.readString(Path.of(fixture("QA_STRIPE_EVENT_FIXTURE_PATH")))
        val secret = fixture("STRIPE_WEBHOOK_SECRET")
        val timestamp = Instant.now().epochSecond
        val signature = stripeSignature(secret, timestamp, payload)
        val first = request(HttpMethod.Post, "/payments/webhook", body = payload, headers = mapOf("Stripe-Signature" to signature))
        val second = request(HttpMethod.Post, "/payments/webhook", body = payload, headers = mapOf("Stripe-Signature" to signature))
        assertEquals(200, first.status, first.body)
        assertEquals(200, second.status, second.body)
        assertEquals(1, customerOrders(customer).count { it.string("stripePaymentIntentId") == intent })
    }

    @Test
    @Order(11)
    fun `concurrent paid-order rejection creates at most one refund`() = runBlocking {
        val owner = fixture("QA_PAID_PENDING_ORDER_OWNER_TOKEN")
        val order = fixture("QA_PAID_PENDING_ORDER_ID")
        val intent = fixture("QA_REFUND_PAYMENT_INTENT_ID")
        Stripe.apiKey = fixture("STRIPE_SECRET_KEY")
        val refundsBefore = refundsFor(intent)
        val results = burst(5) {
            request(HttpMethod.Post, "/restaurant-owner/orders/$order/action", owner, """{"action":"REJECT","reason":"QA replay test"}""")
        }
        assertExactlyOneSuccess("paid rejection", results)
        val refundsAfter = refundsFor(intent)
        assertEquals(1, refundsAfter - refundsBefore, "Replayed rejection produced duplicate or missing refunds")
    }

    private suspend fun burst(size: Int, block: suspend (Int) -> ApiResult): List<ApiResult> =
        coroutineScope {
            List(size) { index -> async(Dispatchers.IO) { block(index) } }.awaitAll()
        }

    private fun assertExactlyOneSuccess(name: String, results: List<ApiResult>) {
        assertEquals(1, results.count(ApiResult::isSuccess), "$name must have one winner: $results")
        results.filterNot(ApiResult::isSuccess).forEach {
            assertTrue(it.status in setOf(400, 403, 404, 409), "$name loser returned unexpected ${it.status}: ${it.body}")
        }
    }

    private suspend fun customerOrders(token: String): List<JsonObject> {
        val response = request(HttpMethod.Get, "/orders", token)
        assertEquals(200, response.status, response.body)
        return response.json().array("orders").map(JsonElement::jsonObject)
    }

    private fun refundsFor(paymentIntent: String): Int =
        Refund.list(mapOf("payment_intent" to paymentIntent, "limit" to 100)).data.size

    private suspend fun request(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): ApiResult {
        val response = client.request("$baseUrl$path") {
            this.method = method
            token?.let { header("Authorization", "Bearer $it") }
            headers.forEach { (name, value) -> header(name, value) }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return ApiResult(response.status.value, response.bodyAsText())
    }

    private fun fixture(name: String): String {
        val value = System.getenv(name)
        assumeTrue(!value.isNullOrBlank(), "Set $name to run this test")
        return value!!
    }

    private fun stripeSignature(secret: String, timestamp: Long, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }

    private data class ApiResult(val status: Int, val body: String) {
        val isSuccess: Boolean get() = status in 200..299
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
        fun jsonOrNull(): JsonObject? = runCatching(::json).getOrNull()
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
    private fun JsonObject.double(name: String): Double = this[name]?.jsonPrimitive?.content?.toDouble()
        ?: error("Missing numeric field $name")
    private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())
}
