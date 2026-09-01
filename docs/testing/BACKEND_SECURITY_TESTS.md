# SwiftBite backend security and concurrency tests

The live API suite is located at:

`src/test/kotlin/com/codewithfk/security/LiveSecurityConcurrencyApiTest.kt`

It targets a running disposable QA server. No token, password, signing secret, or resource ID is stored in source control. Tests with missing fixtures are reported as skipped.

## Required base configuration

```bash
export SWIFTBITE_QA_BASE_URL=http://127.0.0.1:8081
```

## Authorization and IDOR fixtures

```bash
export QA_CUSTOMER_A_TOKEN=...
export QA_CUSTOMER_B_TOKEN=...
export QA_OWNER_A_TOKEN=...
export QA_OWNER_B_TOKEN=...
export QA_OWNER_B_MENU_ITEM_ID=...
export QA_CUSTOMER_A_CART_ITEM_ID=...
```

## Terminal-state fixtures

```bash
export QA_TERMINAL_ORDER_OWNER_TOKEN=...
export QA_TERMINAL_ORDER_RIDER_TOKEN=...
export QA_DELIVERED_ORDER_ID=...
export QA_CANCELLED_ORDER_ID=...
```

## Five-request concurrency fixtures

Every order below must be a dedicated fixture reset before each run.

```bash
# PENDING_ACCEPTANCE order owned by this Owner
export QA_PENDING_ORDER_OWNER_TOKEN=...
export QA_PENDING_ORDER_ID=...

# READY, unassigned order. Comma-separated Rider tokens may contain one or five accounts.
export QA_READY_ORDER_ID=...
export QA_RIDER_TOKENS=token1,token2,token3,token4,token5

# Customer with one non-empty cart and an unused address
export QA_CHECKOUT_CUSTOMER_TOKEN=...
export QA_CHECKOUT_ADDRESS_ID=...

# Rider with unsettled delivered COD orders
export QA_SETTLEMENT_RIDER_TOKEN=...
```

## Stripe replay fixtures

Use Stripe test mode only.

```bash
export QA_STRIPE_CUSTOMER_TOKEN=...
export QA_SUCCEEDED_PAYMENT_INTENT_ID=pi_...

# The JSON event must contain this PaymentIntent and correct SwiftBite user/address metadata.
export QA_WEBHOOK_PAYMENT_INTENT_ID=pi_...
export QA_STRIPE_EVENT_FIXTURE_PATH=/absolute/path/payment_intent_succeeded.json
export STRIPE_WEBHOOK_SECRET=...

# Paid PENDING_ACCEPTANCE order used once for the refund replay test
export QA_PAID_PENDING_ORDER_OWNER_TOKEN=...
export QA_PAID_PENDING_ORDER_ID=...
export QA_REFUND_PAYMENT_INTENT_ID=pi_...
export STRIPE_SECRET_KEY=...
```

## Execution

Run the Ktor server against an isolated QA MySQL database, then execute:

```bash
./gradlew test --tests com.codewithfk.security.LiveSecurityConcurrencyApiTest
```

For an individual case:

```bash
./gradlew test --tests 'com.codewithfk.security.LiveSecurityConcurrencyApiTest.five rider accept requests assign exactly one rider'
```

The suite deliberately changes order, cart, refund, and settlement state. Never point it at production, a shared demonstration database, or real Stripe payments.
