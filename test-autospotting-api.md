# AutoSpotting API Key Debugging Guide

## Issue
Getting "invalid or missing API key" error when calling AutoSpotting API

## Changes Made

### 1. Fixed Header Name Case Sensitivity
**Changed from:** `X-API-Key` (all caps)  
**Changed to:** `X-Api-Key` (mixed case)

The comment in the code mentioned "Using exact header format from working dashboard: X-Api-Key" but the code was using `X-API-Key`. This case sensitivity can cause authentication failures.

### 2. Added API Key Trimming
The API key is now trimmed to remove any leading/trailing whitespace that might have been accidentally included in the configuration file.

### 3. Enhanced Logging
Added detailed logging to show:
- The exact header name being used
- The full API key value (for debugging only - remove in production)
- The length of the API key

## Testing Steps

### Step 1: Verify API Key Configuration
Check that the API key is correctly set in your properties file:

```properties
# In application.properties or application-local.properties
autospotting.api.key=sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2
```

### Step 2: Start the Backend Service
```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
./mvnw spring-boot:run
```

### Step 3: Check the Logs
When the service starts, look for these log messages:
```
🚀 AutoSpotting API Client Configuration
🔗 Base URL: https://do0ezmdybge0h.cloudfront.net/api
🔑 Config autospotting.api.key: 'sHX4wpgTY...'
✅ API Key configured: YES (length=32, first 10 chars: 'sHX4wpgTYZ')
```

### Step 4: Make a Test API Call
When you make an API call, you should see:
```
✅ X-Api-Key header added successfully (length=32)
🔑 Header name: 'X-Api-Key', value (first 10): sHX4wpgTYZ...
```

### Step 5: If Still Failing

If you still get authentication errors, try these alternatives:

#### Option A: Try Different Header Names
The AutoSpotting API might accept different header names. Common variations:
- `X-Api-Key` (current)
- `X-API-Key` (previous)
- `x-api-key` (all lowercase)
- `Authorization: Bearer <key>`

#### Option B: Verify the API Key is Valid
1. Check if the API key has expired
2. Verify you're using the correct API key for your AutoSpotting subscription
3. Contact AutoSpotting support to verify the key is active

#### Option C: Test with curl
Test the API directly with curl to isolate the issue:

```bash
# Test with X-Api-Key header
curl -X GET \
  'https://do0ezmdybge0h.cloudfront.net/api/v1/costs?account_id=YOUR_ACCOUNT_ID' \
  -H 'X-Api-Key: sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2' \
  -H 'Content-Type: application/json' \
  -v

# Test with X-API-Key header (all caps)
curl -X GET \
  'https://do0ezmdybge0h.cloudfront.net/api/v1/costs?account_id=YOUR_ACCOUNT_ID' \
  -H 'X-API-Key: sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2' \
  -H 'Content-Type: application/json' \
  -v

# Test with lowercase header
curl -X GET \
  'https://do0ezmdybge0h.cloudfront.net/api/v1/costs?account_id=YOUR_ACCOUNT_ID' \
  -H 'x-api-key: sHX4wpgTYZhdBI8LxrM8lhkY8eFgsIg2' \
  -H 'Content-Type: application/json' \
  -v
```

Replace `YOUR_ACCOUNT_ID` with your actual AWS account ID.

## Common Issues

### Issue 1: Whitespace in API Key
**Symptom:** API key looks correct but authentication fails  
**Solution:** The code now trims whitespace automatically

### Issue 2: Wrong Header Case
**Symptom:** 401 Unauthorized error  
**Solution:** Changed to use `X-Api-Key` as mentioned in code comments

### Issue 3: API Key Not Loaded
**Symptom:** Logs show "API KEY IS MISSING"  
**Solution:** Check that:
- The property `autospotting.api.key` is set in your active profile's properties file
- No typos in the property name
- The file is in the correct location: `src/main/resources/application-local.properties`

### Issue 4: Using Wrong Environment
**Symptom:** API key works locally but not in production  
**Solution:** Make sure the API key is set in the correct environment's properties file:
- Local: `application-local.properties`
- UAT: `application-uat.properties`  
- Production: `application-prod.properties`

## Next Steps

1. Restart your backend service to pick up the changes
2. Check the startup logs to verify the API key is loaded
3. Make a test API call and check the detailed logs
4. If still failing, try the curl tests to determine if it's a code issue or API key issue
