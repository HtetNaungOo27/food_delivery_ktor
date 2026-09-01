#!/usr/bin/env bash
set -euo pipefail

# Destructive security regression tests. Use only an isolated seeded QA database.
# Required:
# BASE_URL CUSTOMER_A_TOKEN CUSTOMER_B_TOKEN OWNER_A_TOKEN OWNER_B_TOKEN
# RIDER_A_TOKEN RIDER_B_TOKEN
# CART_A_ITEM_ID NOTIFICATION_A_ID ORDER_A_ID OWNER_A_ORDER_ID READY_ORDER_ID
# MENU_A_ITEM_ID RESTAURANT_A_ID ADDRESS_A_ID PAYMENT_INTENT_ID

: "${BASE_URL:=http://127.0.0.1:8081}"
required=(CUSTOMER_A_TOKEN CUSTOMER_B_TOKEN OWNER_A_TOKEN OWNER_B_TOKEN RIDER_A_TOKEN RIDER_B_TOKEN CART_A_ITEM_ID NOTIFICATION_A_ID ORDER_A_ID OWNER_A_ORDER_ID READY_ORDER_ID MENU_A_ITEM_ID RESTAURANT_A_ID ADDRESS_A_ID PAYMENT_INTENT_ID)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing environment variable: $name" >&2
    exit 2
  fi
done

pass=0
fail=0
request_status() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-sS -o /dev/null -w '%{http_code}' -X "$method" "$BASE_URL$path")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}"
}

assert_denied() {
  local name="$1" method="$2" path="$3" token="$4" body="${5:-}"
  local code
  code="$(request_status "$method" "$path" "$token" "$body")"
  if [[ "$code" =~ ^(400|401|403|404|409)$ ]]; then
    echo "PASS $name ($code)"; pass=$((pass + 1))
  else
    echo "FAIL $name expected denial, got $code"; fail=$((fail + 1))
  fi
}

assert_one_success_parallel() {
  local name="$1" method="$2" path="$3" body="$4" token_a="$5" token_b="$6"
  local dir code_a code_b successes
  dir="$(mktemp -d)"
  request_status "$method" "$path" "$token_a" "$body" >"$dir/a" &
  local pid_a=$!
  request_status "$method" "$path" "$token_b" "$body" >"$dir/b" &
  local pid_b=$!
  wait "$pid_a" || true
  wait "$pid_b" || true
  code_a="$(<"$dir/a")"; code_b="$(<"$dir/b")"
  successes=0
  [[ "$code_a" =~ ^2 ]] && successes=$((successes + 1))
  [[ "$code_b" =~ ^2 ]] && successes=$((successes + 1))
  rm -r "$dir"
  if [[ "$successes" -eq 1 ]]; then
    echo "PASS $name ($code_a/$code_b)"; pass=$((pass + 1))
  else
    echo "FAIL $name expected exactly one success, got $code_a/$code_b"; fail=$((fail + 1))
  fi
}

echo "Authorization / IDOR"
assert_denied "anonymous menu create" POST "/restaurants/$RESTAURANT_A_ID/menu" "" '{"name":"Injected","description":"QA","price":1,"restaurantId":"ignored"}'
assert_denied "anonymous menu update" PATCH "/menu/$MENU_A_ITEM_ID" "" '{"price":1}'
assert_denied "customer cannot create restaurant" POST "/restaurants" "$CUSTOMER_A_TOKEN" '{"name":"Injected","address":"Yangon","latitude":"16.8","longitude":"96.1","categoryId":"00000000-0000-0000-0000-000000000000"}'
assert_denied "customer cannot create global category" POST "/categories" "$CUSTOMER_A_TOKEN" '{"name":"Injected category"}'
assert_denied "customer cannot act as rider" POST "/rider/location" "$CUSTOMER_A_TOKEN" '{"latitude":16.8,"longitude":96.1,"address":""}'
assert_denied "customer B cannot edit A cart" PATCH "/cart" "$CUSTOMER_B_TOKEN" "{\"cartItemId\":\"$CART_A_ITEM_ID\",\"quantity\":9}"
assert_denied "customer B cannot delete A cart" DELETE "/cart/$CART_A_ITEM_ID" "$CUSTOMER_B_TOKEN"
assert_denied "customer B cannot read A order" GET "/orders/$ORDER_A_ID" "$CUSTOMER_B_TOKEN"
assert_denied "customer B cannot mark A notification" POST "/notifications/$NOTIFICATION_A_ID/read" "$CUSTOMER_B_TOKEN"
assert_denied "rider B cannot read A delivery path" GET "/rider/deliveries/$ORDER_A_ID/path" "$RIDER_B_TOKEN"
assert_denied "owner B cannot mutate A menu" PATCH "/restaurant-owner/menu/$MENU_A_ITEM_ID" "$OWNER_B_TOKEN" '{"price":1}'

echo "State machine bypasses"
assert_denied "direct unpaid CARD order" POST "/orders" "$CUSTOMER_A_TOKEN" "{\"addressId\":\"$ADDRESS_A_ID\",\"paymentMethod\":\"CARD\"}"
assert_denied "owner cannot skip to READY" PATCH "/restaurant-owner/orders/$OWNER_A_ORDER_ID/status" "$OWNER_A_TOKEN" '{"status":"READY"}'
assert_denied "unassigned rider cannot deliver" POST "/rider/deliveries/$READY_ORDER_ID/status" "$RIDER_A_TOKEN" '{"status":"DELIVERED"}'
assert_denied "wrong owner cannot accept order" POST "/restaurant-owner/orders/$OWNER_A_ORDER_ID/action" "$OWNER_B_TOKEN" '{"action":"ACCEPT"}'

echo "Concurrency / double tap"
assert_one_success_parallel "only one rider accepts READY order" POST "/rider/deliveries/$READY_ORDER_ID/accept" '' "$RIDER_A_TOKEN" "$RIDER_B_TOKEN"
assert_one_success_parallel "owner action executes once" POST "/restaurant-owner/orders/$OWNER_A_ORDER_ID/action" '{"action":"ACCEPT"}' "$OWNER_A_TOKEN" "$OWNER_A_TOKEN"

echo "Idempotency / replay"
first="$(request_status POST "/payments/confirm/$PAYMENT_INTENT_ID" "$CUSTOMER_A_TOKEN")"
second="$(request_status POST "/payments/confirm/$PAYMENT_INTENT_ID" "$CUSTOMER_A_TOKEN")"
if [[ "$first" =~ ^2 && "$second" =~ ^2 ]]; then
  echo "CHECK payment replay returned success twice; verify both responses reference the same order and DB has one order/notification"
else
  echo "CHECK payment replay statuses $first/$second; acceptable only if DB still has exactly one order"
fi

reject_one="$(request_status POST "/rider/deliveries/$READY_ORDER_ID/reject" "$RIDER_A_TOKEN")"
reject_two="$(request_status POST "/rider/deliveries/$READY_ORDER_ID/reject" "$RIDER_A_TOKEN")"
echo "CHECK duplicate rejection statuses $reject_one/$reject_two; DB must contain at most one (rider_id, order_id) row"

echo "Historical immutability"
assert_denied "ordered address cannot mutate in place" PUT "/addresses/$ADDRESS_A_ID" "$CUSTOMER_A_TOKEN" '{"userId":"ignored","addressLine1":"Changed after checkout","city":"Yangon","state":"Yangon","zipCode":"11181","country":"Myanmar","latitude":16.8,"longitude":96.1}'

echo "Summary: $pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
