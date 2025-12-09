#!/bin/bash

# AutoSpotting API Test Script
# This script tests different header formats to find which one works

API_KEY="sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2"
BASE_URL="https://do0ezmdybge0h.cloudfront.net/api"
ACCOUNT_ID="${1:-123456789012}"  # Replace with your AWS account ID

echo "========================================="
echo "AutoSpotting API Authentication Test"
echo "========================================="
echo "API Key: ${API_KEY:0:10}..."
echo "Account ID: $ACCOUNT_ID"
echo "========================================="
echo ""

# Test 1: X-Api-Key (mixed case - as per code comment)
echo "Test 1: Testing with 'X-Api-Key' header..."
echo "-----------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/response1.json -w "%{http_code}" \
  "${BASE_URL}/v1/costs?account_id=${ACCOUNT_ID}" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json")

echo "HTTP Status Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ SUCCESS! X-Api-Key works!"
  echo "Response:"
  cat /tmp/response1.json | python3 -m json.tool 2>/dev/null || cat /tmp/response1.json
  exit 0
else
  echo "❌ FAILED"
  echo "Response:"
  cat /tmp/response1.json
fi
echo ""

# Test 2: X-API-Key (all caps)
echo "Test 2: Testing with 'X-API-Key' header (all caps)..."
echo "------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/response2.json -w "%{http_code}" \
  "${BASE_URL}/v1/costs?account_id=${ACCOUNT_ID}" \
  -H "X-API-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json")

echo "HTTP Status Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ SUCCESS! X-API-Key works!"
  echo "Response:"
  cat /tmp/response2.json | python3 -m json.tool 2>/dev/null || cat /tmp/response2.json
  exit 0
else
  echo "❌ FAILED"
  echo "Response:"
  cat /tmp/response2.json
fi
echo ""

# Test 3: x-api-key (all lowercase)
echo "Test 3: Testing with 'x-api-key' header (all lowercase)..."
echo "-----------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/response3.json -w "%{http_code}" \
  "${BASE_URL}/v1/costs?account_id=${ACCOUNT_ID}" \
  -H "x-api-key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json")

echo "HTTP Status Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ SUCCESS! x-api-key works!"
  echo "Response:"
  cat /tmp/response3.json | python3 -m json.tool 2>/dev/null || cat /tmp/response3.json
  exit 0
else
  echo "❌ FAILED"
  echo "Response:"
  cat /tmp/response3.json
fi
echo ""

# Test 4: Authorization Bearer
echo "Test 4: Testing with 'Authorization: Bearer' header..."
echo "-------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/response4.json -w "%{http_code}" \
  "${BASE_URL}/v1/costs?account_id=${ACCOUNT_ID}" \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json")

echo "HTTP Status Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ SUCCESS! Authorization Bearer works!"
  echo "Response:"
  cat /tmp/response4.json | python3 -m json.tool 2>/dev/null || cat /tmp/response4.json
  exit 0
else
  echo "❌ FAILED"
  echo "Response:"
  cat /tmp/response4.json
fi
echo ""

echo "========================================="
echo "❌ ALL TESTS FAILED"
echo "========================================="
echo ""
echo "Possible issues:"
echo "1. The API key might be invalid or expired"
echo "2. The account ID might be incorrect"
echo "3. The API endpoint might have changed"
echo "4. Your IP might be blocked"
echo ""
echo "Please check:"
echo "- Verify your API key with AutoSpotting support"
echo "- Confirm your AWS account ID: $ACCOUNT_ID"
echo "- Check if there are any IP restrictions"
echo ""

# Cleanup
rm -f /tmp/response*.json
