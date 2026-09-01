package com.codewithfk.services

import com.codewithfk.model.OrderStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderTransitionPolicyTest {
    @Test
    fun `restaurant workflow allows only sequential preparation states`() {
        assertTrue(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, OrderStatus.PENDING_ACCEPTANCE, OrderStatus.ACCEPTED))
        assertTrue(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, OrderStatus.ACCEPTED, OrderStatus.PREPARING))
        assertTrue(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, OrderStatus.PREPARING, OrderStatus.READY))
        assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, OrderStatus.PENDING_ACCEPTANCE, OrderStatus.READY))
    }

    @Test
    fun `rider cannot skip pickup or mutate terminal states`() {
        assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RIDER, OrderStatus.ASSIGNED, OrderStatus.DELIVERED))
        assertTrue(OrderTransitionPolicy.isAllowed(OrderActor.RIDER, OrderStatus.ASSIGNED, OrderStatus.OUT_FOR_DELIVERY))
        assertTrue(OrderTransitionPolicy.isAllowed(OrderActor.RIDER, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED))
        OrderStatus.entries.filter { it in setOf(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED, OrderStatus.REJECTED, OrderStatus.CANCELLED) }
            .forEach { terminal ->
                OrderStatus.entries.forEach { target ->
                    assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RIDER, terminal, target))
                    assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, terminal, target))
                }
            }
    }

    @Test
    fun `actors cannot perform another role's transition`() {
        assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RESTAURANT, OrderStatus.READY, OrderStatus.ASSIGNED))
        assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.RIDER, OrderStatus.PENDING_ACCEPTANCE, OrderStatus.ACCEPTED))
        assertFalse(OrderTransitionPolicy.isAllowed(OrderActor.CUSTOMER, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED))
    }
}
