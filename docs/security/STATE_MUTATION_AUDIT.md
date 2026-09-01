# State Mutation Security Audit

Scope: all mutating Ktor routes and their service/database implementations. The review focuses on terminal-state enforcement, transition ordering, concurrency, idempotency, and actor authorization.

## Remediation status — 2026-08-31

The findings below have been implemented in the working tree. Controls now include database-backed role checks, actor-bound predicates, a central tested transition policy, atomic compare-and-set updates, checkout/payment idempotency keys, row-locked cart consumption, unique replay constraints, exact-set COD settlement, immutable used addresses, order-item snapshots, delivered-purchase review checks, owner-scoped media paths, authenticated tracking, BCrypt password migration, environment-managed secrets, and a durable notification outbox.

The black-box script still requires an isolated running QA database and fixture tokens before it can be executed safely. Unit tests for terminal and skipped state transitions run through Gradle.

## Executive summary

The delivered-order replay was fixed with an atomic old-state predicate. The same defensive pattern is still missing from several other workflows. The highest-risk findings are unauthenticated menu writes, missing role authorization, cross-account cart mutation, Stripe/order replay, and non-atomic order assignment/status changes.

Severity counts: **9 critical/high**, **10 medium**, **4 low/defense-in-depth**.

## Canonical lifecycle

Orders must follow exactly one legal path:

```text
PENDING_ACCEPTANCE -> ACCEPTED -> PREPARING -> READY -> ASSIGNED
    -> OUT_FOR_DELIVERY -> DELIVERED
                          \-> DELIVERY_FAILED
PENDING_ACCEPTANCE -> REJECTED
PENDING_ACCEPTANCE -> CANCELLED
```

Terminal states are `DELIVERED`, `DELIVERY_FAILED`, `REJECTED`, and `CANCELLED`. No route may change them. Side effects must occur only after an atomic state claim succeeds.

## Confirmed vulnerabilities

### SB-01 — Critical — unauthenticated menu creation and mutation

- Surface: `POST /restaurants/{id}/menu`, `PATCH /menu/{itemId}`.
- Code: `MenuItemRoutes.kt` registers both writes outside `authenticate` and calls owner-agnostic `MenuItemService` functions.
- Impact: anyone can create or modify any restaurant's menu and price.
- Pattern: unauthorized state transition.
- Required fix: remove the public writes. Keep reads public and route writes only through `/restaurant-owner/menu`, deriving restaurant ownership from the JWT user ID.

### SB-02 — Critical — role escalation through signup and role-blind JWT authorization

- Surface: `/auth/signup`, `/auth/oauth`, every `/rider/*`, `/restaurant-owner/*`, and restaurant creation.
- Code: signup accepts caller-provided `role`; JWT contains only `userId`; protected routes do not verify the persisted role.
- Impact: a customer can register as owner/rider or call rider operations with an ordinary token.
- Pattern: unauthorized transition.
- Required fix: public signup must always create `CUSTOMER`. Owner/rider provisioning must be administrative. Put a normalized role claim in the JWT and verify it against the database for privileged routes.

### SB-03 — High — cross-account cart item mutation (IDOR)

- Surface: `PATCH /cart`, `DELETE /cart/{cartItemId}`.
- Code: the route does not bind the item to the JWT user; service predicates use only `cartItemId`.
- Impact: any authenticated user who learns an item UUID can change or delete another customer's cart.
- Pattern: unauthorized state transition.
- Required fix: accept `userId` in service methods and update/delete with `(id = ? AND user_id = ?)`.

### SB-04 — High — payment/order replay and duplicate order creation

- Surface: `POST /payments/confirm/{id}`, Stripe webhook, `POST /orders`.
- Code: `placeOrder` always inserts; `stripe_payment_intent_id` is not unique; controller expects an `"Order already exists"` exception that the service never throws. Webhook and confirm can race. COD/direct order retries can consume the same cart twice depending on isolation timing.
- Impact: duplicate orders, duplicate owner notifications, inconsistent cart and payment reconciliation.
- Pattern: concurrency and idempotency failure.
- Required fix: unique non-null payment intent ID, persisted Stripe event IDs, client idempotency key for COD orders, and one atomic checkout transaction that claims the cart/version before insertion.

### SB-05 — High — unpaid CARD order can be placed directly

- Surface: `POST /orders`.
- Code: caller may send `paymentMethod=CARD` with no verified PaymentIntent; order is still created with payment status Pending and enters fulfillment.
- Impact: food can be fulfilled without successful payment.
- Pattern: state-machine bypass.
- Required fix: direct `/orders` accepts COD only. CARD orders may be created only from a verified `payment_intent.succeeded` event or verified confirmation flow.

### SB-06 — High — restaurant status transitions are check-then-update

- Surface: `POST /restaurant-owner/orders/{id}/action`, `PATCH /restaurant-owner/orders/{id}/status`.
- Code: current state is read first, followed by an unconditional `UPDATE id = ?`. Two requests can both pass and both notify. `OrderService.updateOrderStatus` also accepts arbitrary raw status strings and has no owner predicate.
- Impact: duplicate notifications and conflicting/skipped transitions.
- Pattern: race condition and state-machine bypass.
- Required fix: central transition service and `UPDATE ... WHERE id=? AND restaurant_id IN(owner restaurants) AND status=?`; require affected row count exactly one before emitting an outbox event.

### SB-07 — High — rider acceptance race

- Surface: `POST /rider/deliveries/{id}/accept`.
- Code: availability is selected first, then order is updated by ID only. Two riders can both pass the read and both report success/notify; last writer owns the order.
- Impact: two riders may travel to the same pickup.
- Pattern: double-tap/concurrency and unauthorized assignment.
- Required fix: atomic `UPDATE` predicate including `status=READY AND rider_id IS NULL`, then emit side effects only for the single winner.

### SB-08 — High — order details and delivery path IDOR

- Surface: `GET /orders/{id}`, `GET /rider/deliveries/{id}/path`.
- Code: neither endpoint verifies customer ownership or assigned rider ownership.
- Impact: authenticated users can retrieve customer address/order information.
- Pattern: unauthorized target access.
- Required fix: query with actor predicate (`order.user_id = customer`, `order.rider_id = rider`) and role check.

### SB-09 — Medium — cross-account notification mutation

- Surface: `POST /notifications/{id}/read`.
- Code: JWT user ID is read but not used; update predicate is notification ID only.
- Impact: one user can mark another user's alert as read.
- Required fix: `(notification.id = ? AND notification.user_id = ?)`.

### SB-10 — Medium — duplicate rider rejection and delivery-request rows

- Surface: rider reject and internal `createDeliveryRequest`.
- Code: no `(rider_id, order_id)` unique index/upsert; repeated requests insert repeatedly. `RiderRejectionsTable` incorrectly declares `UsersTable.id` as its primary key.
- Impact: duplicate rows, repeated pushes, and possible schema/insert failures.
- Required fix: correct primary key and unique composite indexes; use insert-ignore/upsert; create requests only while order is `READY` and unassigned.

### SB-11 — Medium — settlement can clear cash that was not included in the settlement

- Surface: `POST /rider/wallet/settle`.
- Code: rows are selected to calculate amount, then every currently-collected row is cleared with a broader second predicate. A COD delivery committed between those statements can be cleared without being included in `amount`.
- Impact: lost settlement liability.
- Pattern: race condition/financial integrity.
- Required fix: create a settlement ID first and atomically claim a fixed set of order IDs; calculate amount from claimed rows. Add `settlement_id` to orders rather than overloading a boolean.

### SB-12 — Medium — addresses remain mutable historical order data

- Surface: `PUT/DELETE /addresses/{id}`.
- Code: orders reference the live address row. Editing an address after checkout changes active and completed order history/destination.
- Impact: rider can be redirected after acceptance and audit history changes.
- Required fix: snapshot delivery address fields into the order at checkout, or prohibit mutation while referenced and copy-on-write for edits.

### SB-13 — Medium — menu records remain mutable historical order data

- Surface: owner menu updates.
- Code: order items reference live menu name/price; no snapshot price/name exists in `OrderItemsTable`.
- Impact: receipts and historical analytics change after menu edits; fulfillment may show a new item name.
- Required fix: store `item_name`, `unit_price`, and relevant options on each order item at checkout.

### SB-14 — Medium — review authorization lacks purchase/delivery requirement

- Surface: `POST /restaurants/{id}/reviews`.
- Code: any authenticated account can review any existing restaurant.
- Impact: rating manipulation.
- Required fix: require a `DELIVERED` order for `(user, restaurant)` and optionally bind review to a unique order ID.

### SB-15 — Medium — image deletion has no ownership boundary

- Surface: `DELETE /images/{imageUrl}`.
- Code: any authenticated user can request deletion; upload role is hard-coded to owner.
- Impact: deletion of another restaurant/user image.
- Required fix: store uploaded asset ownership in a table and delete with owner/role predicate; do not accept an arbitrary full URL as authority.

### SB-16 — Medium — restaurant creation is available to any authenticated account

- Surface: `POST /restaurants`.
- Code: no owner role check or one-restaurant constraint.
- Impact: customers can create restaurants and owners can create unlimited records despite product rules.
- Required fix: OWNER-only route plus unique `restaurants.owner_id` if the product supports one restaurant per owner.

### SB-17 — Low — cart add is non-atomic and trusts inconsistent identifiers

- Surface: `POST /cart`.
- Code: select-then-insert/update has no unique `(user_id, menu_item_id)` constraint; supplied restaurant ID is not checked against the menu item; quantity may be negative; availability/open status is ignored.
- Impact: duplicate rows, lost quantities, cross-restaurant forged cart records.
- Required fix: validate positive bounded quantity, derive restaurant from menu item, require available/open, and use a unique key/upsert.

### SB-18 — Low — notification side effects are not transactional

- Surface: all order transitions.
- Code: DB mutation and Firebase call occur in the same service flow without a durable outbox/idempotency key.
- Impact: rollback after push or retry after commit can produce missing/duplicate messages.
- Required fix: transactional outbox with unique `(aggregate_id, transition, version)` and an asynchronous retrying publisher.

### SB-19 — Low — location updates accept invalid coordinates and wrong roles

- Surface: `POST /rider/location`.
- Code: no role validation or coordinate bounds.
- Impact: ordinary users become discoverable riders; invalid values poison distance calculations.
- Required fix: RIDER role, latitude `[-90,90]`, longitude `[-180,180]`, finite-number checks.

### SB-20 — Defense in depth — plaintext credentials and hard-coded JWT secret

- Surface: authentication.
- Code: supplied password is stored and compared directly; JWT secret is source-controlled.
- Impact: database/source compromise immediately exposes every account/token-signing capability.
- Required fix: Argon2id/bcrypt hashes, secret from environment/secret manager, key rotation, rate limiting, and sanitized logs.

### SB-21 — Critical — unauthenticated WebSocket can forge rider locations

- Surface: `WS /track/{orderId}`.
- Code: tracking routes are outside authentication. The client supplies its own `role`, `riderId`, and `orderId`; `TrackingService.updateLocation` writes that rider ID directly.
- Impact: anyone can read active delivery path data, impersonate a rider, overwrite rider coordinates, and broadcast false tracking information.
- Pattern: unauthorized mutation and target access.
- Required fix: authenticate the WebSocket during handshake, derive actor ID/role from JWT, require the rider to be assigned to the active order before accepting location frames, allow only order customer/owner/assigned rider to subscribe, and reject terminal orders.

### SB-22 — Medium — category administration is available to every account

- Surface: `POST /categories`.
- Code: authentication is present but no ADMIN authorization exists.
- Impact: customers, owners, and riders can alter global discovery taxonomy.
- Pattern: unauthorized mutation.
- Required fix: ADMIN-only claim/database role check and an audit record for taxonomy changes.

## Reference implementation patterns

### Central transition policy

```kotlin
object OrderTransitions {
    private val allowed = mapOf(
        PENDING_ACCEPTANCE to setOf(ACCEPTED, REJECTED, CANCELLED),
        ACCEPTED to setOf(PREPARING),
        PREPARING to setOf(READY),
        READY to setOf(ASSIGNED),
        ASSIGNED to setOf(OUT_FOR_DELIVERY),
        OUT_FOR_DELIVERY to setOf(DELIVERED, DELIVERY_FAILED)
    )

    fun requireAllowed(from: OrderStatus, to: OrderStatus) {
        require(to in allowed[from].orEmpty()) { "Invalid transition: $from -> $to" }
    }
}
```

### Atomic owner transition

```kotlin
val changed = OrdersTable.update({
    (OrdersTable.id eq orderId) and
    (OrdersTable.status eq expected.name) and
    (OrdersTable.restaurantId inSubQuery RestaurantsTable
        .slice(RestaurantsTable.id)
        .select { RestaurantsTable.ownerId eq ownerId })
}) {
    it[status] = next.name
    it[updatedAt] = CurrentDateTime
}
check(changed == 1) { "Order changed already or actor is unauthorized" }
enqueueOutboxOnce(orderId, next)
```

### Idempotent payment/order creation

```kotlin
// Database constraints:
// UNIQUE orders(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL
// UNIQUE processed_webhooks(provider, event_id)

transaction {
    if (!ProcessedWebhooksTable.insertIgnore { eventId = stripeEventId }) return@transaction existingOrder()
    OrdersTable.insert { stripePaymentIntentId = verifiedIntent.id /* snapshot cart */ }
    OutboxTable.insert { uniqueKey = "order:${verifiedIntent.id}:created" }
}
```

### Actor-bound mutation

```kotlin
fun updateQuantity(userId: UUID, itemId: UUID, quantity: Int): Boolean = transaction {
    require(quantity in 1..99)
    CartTable.update({ (CartTable.id eq itemId) and (CartTable.userId eq userId) }) {
        it[CartTable.quantity] = quantity
    } == 1
}
```

## Automated regression suite

Run `scripts/security/state_mutation_regression.sh` only against an isolated seeded QA database. It includes authorization, invalid-transition, replay, and parallel-request tests. Required fixture/token variables are documented at the top of the script.

Additional database-level assertions for CI:

1. Exactly one winner from 20 parallel rider accept requests.
2. Exactly one order for a Stripe PaymentIntent after webhook/confirm replay.
3. Exactly one notification/outbox row per aggregate transition/version.
4. No terminal order changes under any endpoint.
5. No mutation succeeds when actor ownership/role differs.
6. Settlement claimed order IDs exactly match the settlement amount.

## Release gate

Do not treat a mobile button becoming disabled as protection. Release only when all rules are enforced by atomic backend predicates and the regression script passes against the same database engine/isolation level used in deployment.
