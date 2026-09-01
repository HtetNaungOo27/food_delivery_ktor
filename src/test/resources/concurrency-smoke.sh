#!/usr/bin/env bash
set -euo pipefail
: "${API_BASE_URL:?Set API_BASE_URL}"
: "${RIDER_TOKEN:?Set RIDER_TOKEN}"
: "${ORDER_ID:?Set ORDER_ID}"
for i in 1 2 3 4 5; do
  curl --silent --output "/tmp/swiftbite-accept-$i.json" --write-out "%{http_code}\n" \
    -X POST "$API_BASE_URL/rider/deliveries/$ORDER_ID/accept" \
    -H "Authorization: Bearer $RIDER_TOKEN" &
done
wait
echo "Exactly one request must succeed; all others must be rejected."
