@echo off
echo ============================================
echo XamOps Tenant Isolation Verification Script
echo ============================================
echo.

echo Checking application logs for tenant filtering...
echo.

REM Check if logs directory exists
if not exist "xamops-service\logs" (
    echo ERROR: logs directory not found. Make sure the application has run at least once.
    echo.
    pause
    exit /b 1
)

echo Looking for environment filter messages in logs...
findstr /C:"Environment filter active" xamops-service\logs\application.log
echo.

echo Looking for tenant processing messages...
findstr /C:"Found tenant" xamops-service\logs\application.log
echo.

echo Checking scheduled job execution...
findstr /C:"Starting global dashboard aggregation" xamops-service\logs\application.log
findstr /C:"Starting Cost Data Refresh" xamops-service\logs\application.log
echo.

echo Checking current Spring profile...
findstr /C:"scheduling.current-tenant-id" xamops-service\src\main\resources\application-local.properties
echo.

echo ============================================
echo Verification complete!
echo.
echo Expected Results:
echo - Should see "Environment filter active: Only processing tenant 'customer1'"
echo - Should NOT see references to customer2 or customer3 in scheduled jobs
echo ============================================
pause
