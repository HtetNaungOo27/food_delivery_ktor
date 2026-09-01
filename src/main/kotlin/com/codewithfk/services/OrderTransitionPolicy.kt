package com.codewithfk.services

import com.codewithfk.model.OrderStatus

enum class OrderActor { CUSTOMER, RESTAURANT, RIDER, SYSTEM }

object OrderTransitionPolicy {
    private val allowed = mapOf(
        OrderActor.CUSTOMER to mapOf(
            OrderStatus.PENDING_ACCEPTANCE to setOf(OrderStatus.CANCELLED)
        ),
        OrderActor.RESTAURANT to mapOf(
            OrderStatus.PENDING_ACCEPTANCE to setOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED),
            OrderStatus.ACCEPTED to setOf(OrderStatus.PREPARING),
            OrderStatus.PREPARING to setOf(OrderStatus.READY)
        ),
        OrderActor.RIDER to mapOf(
            OrderStatus.READY to setOf(OrderStatus.ASSIGNED),
            OrderStatus.ASSIGNED to setOf(OrderStatus.OUT_FOR_DELIVERY),
            OrderStatus.OUT_FOR_DELIVERY to setOf(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED)
        )
    )

    fun isAllowed(actor: OrderActor, from: OrderStatus, to: OrderStatus): Boolean =
        to in allowed[actor].orEmpty()[from].orEmpty()

    fun requireAllowed(actor: OrderActor, from: OrderStatus, to: OrderStatus) {
        require(isAllowed(actor, from, to)) { "$actor cannot transition an order from $from to $to" }
    }
}
