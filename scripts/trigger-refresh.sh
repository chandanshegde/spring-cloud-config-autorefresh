#!/bin/bash

# Script to trigger configuration refresh
# Simulates a GitHub webhook call to the Config Server's /monitor endpoint

CONFIG_SERVER_URL="${CONFIG_SERVER_URL:-http://localhost:8888}"

echo "========================================="
echo "Triggering Config Refresh"
echo "========================================="
echo "Config Server URL: $CONFIG_SERVER_URL"
echo ""

# Trigger the /monitor endpoint (simulates GitHub webhook)
# Note: /monitor is a regular endpoint, not under /actuator
# It accepts POST requests with GitHub webhook payloads
echo "Calling /monitor..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$CONFIG_SERVER_URL/monitor" \
  -H "X-GitHub-Event: push" \
  -H "Content-Type: application/json" \
  -d '{}')

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "204" ]; then
  echo "✓ Config Server notified successfully (HTTP $HTTP_CODE)"
else
  echo "✗ Failed to notify Config Server (HTTP $HTTP_CODE)"
  echo "Response: $BODY"
fi

echo ""
echo "Waiting for Spring Cloud Bus to propagate..."
sleep 3

echo ""
echo "========================================="
echo "Verifying Client Apps"
echo "========================================="

# Check Client 1
echo -n "Client 1 (8080): "
CLIENT1_STATUS=$(curl -s -w "%{http_code}" -o /dev/null http://localhost:8080/actuator/health)
if [ "$CLIENT1_STATUS" = "200" ]; then
  echo "✓ Healthy"
else
  echo "✗ Not available (HTTP $CLIENT1_STATUS)"
fi

# Check Client 2
echo -n "Client 2 (8081): "
CLIENT2_STATUS=$(curl -s -w "%{http_code}" -o /dev/null http://localhost:8081/actuator/health)
if [ "$CLIENT2_STATUS" = "200" ]; then
  echo "✓ Healthy"
else
  echo "✗ Not available (HTTP $CLIENT2_STATUS)"
fi

echo ""
echo "========================================="
echo "Current Configuration Values"
echo "========================================="

echo ""
echo "Client 1 greeting endpoint:"
curl -s http://localhost:8080/api/greeting || echo "(not available)"

echo ""
echo "Client 2 greeting endpoint:"
curl -s http://localhost:8081/api/greeting || echo "(not available)"

echo ""
echo "========================================="
echo "Refresh Complete"
echo "========================================="
echo ""
echo "To manually refresh a specific client:"
echo "  curl -X POST http://localhost:8080/actuator/refresh"
echo "  curl -X POST http://localhost:8081/actuator/refresh"
echo ""
echo "To manually trigger bus refresh:"
echo "  curl -X POST $CONFIG_SERVER_URL/actuator/busrefresh"
