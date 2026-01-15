-- ================================================================================
-- MASTER DATABASE MIGRATION SCRIPT - UPDATED
-- Purpose: Clean up tenant_config table handling foreign key constraints
-- Database: production_db (jdbc:postgresql://3.110.102.128:5432/production_db)
-- ================================================================================

-- STEP 1: BACKUP CURRENT DATA
-- ================================================================================
CREATE TABLE IF NOT EXISTS tenant_config_backup AS SELECT * FROM tenant_config;
CREATE TABLE IF NOT EXISTS global_users_backup AS SELECT * FROM global_users;

-- Verify backups
SELECT COUNT(*) as tenant_config_backup_count FROM tenant_config_backup;
SELECT COUNT(*) as global_users_backup_count FROM global_users_backup;

-- STEP 2: HANDLE GLOBAL_USERS REFERENCES
-- ================================================================================
-- Option A: Migrate users from environment tenants to 'system' tenant
-- This assumes all users in customer1, customer2, customer3 should be in system tenant

-- Show current user distribution
SELECT tenant_id, COUNT(*) as user_count FROM global_users GROUP BY tenant_id;

-- Migrate users from customer1, customer2, customer3 to 'system'
-- (Only if 'system' doesn't exist yet, otherwise they'll be migrated after system creation)
UPDATE global_users 
SET tenant_id = 'system'
WHERE tenant_id IN ('customer1', 'customer2', 'customer3');

-- Show updated distribution
SELECT tenant_id, COUNT(*) as user_count FROM global_users GROUP BY tenant_id;

-- STEP 3: REMOVE ENVIRONMENT TENANT ENTRIES
-- ================================================================================
-- Now we can safely delete since no users reference these tenants
DELETE FROM tenant_config 
WHERE tenant_id IN ('customer1', 'customer2', 'customer3', 'default');

-- Verify deletions
SELECT tenant_id, company_name FROM tenant_config WHERE active = true;

-- STEP 4: ENSURE PRODUCTION SYSTEM DATABASE ENTRY EXISTS
-- ================================================================================
-- Check if system entry already exists
SELECT * FROM tenant_config WHERE tenant_id = 'system';

-- If not exists, insert it (if exists, update it)
INSERT INTO tenant_config (
    tenant_id, 
    company_name, 
    db_url, 
    db_username, 
    db_password, 
    driver_class_name, 
    active
)
VALUES (
    'system',
    'XamOps Production System',
    'jdbc:postgresql://18.61.99.59:5432/postgres',
    'postgres',
    'XamOps@123',
    'org.postgresql.Driver',
    true
)
ON CONFLICT (tenant_id) DO UPDATE SET
    db_url = EXCLUDED.db_url,
    db_username = EXCLUDED.db_username,
    db_password = EXCLUDED.db_password,
    company_name = EXCLUDED.company_name,
    active = EXCLUDED.active;

-- STEP 5: VERIFY/UPDATE SISL CUSTOMER ENTRY
-- ================================================================================
-- customer4 should remain as SISL
-- Check current state
SELECT * FROM tenant_config WHERE tenant_id IN ('customer4', 'SISL');

-- If you want to rename customer4 to SISL (optional):
-- First update user references if any
-- UPDATE global_users SET tenant_id = 'SISL' WHERE tenant_id = 'customer4';
-- Then update tenant_config
-- UPDATE tenant_config SET tenant_id = 'SISL' WHERE tenant_id = 'customer4';

-- Or keep as customer4 - both work fine

-- STEP 6: FINAL VERIFICATION
-- ================================================================================
-- Should show: system, customer4 (or SISL), and any other real customers
SELECT 
    tenant_id, 
    company_name, 
    db_url, 
    db_username,
    active
FROM tenant_config 
WHERE active = true
ORDER BY tenant_id;

-- Verify users are properly distributed
SELECT 
    tenant_id, 
    COUNT(*) as user_count,
    STRING_AGG(DISTINCT username, ', ') as sample_users
FROM global_users 
GROUP BY tenant_id
ORDER BY tenant_id;

-- Expected output for tenant_config:
-- tenant_id  | company_name              | db_url                                                | db_username  | active
-- -----------|---------------------------|-------------------------------------------------------|--------------|-------
-- customer4  | SISL                      | jdbc:postgresql://16.112.110.22:5432/SISL-DB          | SISL-DB      | t
-- system     | XamOps Production System  | jdbc:postgresql://18.61.99.59:5432/postgres           | postgres     | t

-- ================================================================================
-- ROLLBACK PROCEDURE (if needed)
-- ================================================================================
/*
-- To rollback both tables:
DELETE FROM tenant_config;
DELETE FROM global_users;
INSERT INTO tenant_config SELECT * FROM tenant_config_backup;
INSERT INTO global_users SELECT * FROM global_users_backup;

-- Verify rollback:
SELECT * FROM tenant_config WHERE active = true;
SELECT tenant_id, COUNT(*) FROM global_users GROUP BY tenant_id;
*/

-- ================================================================================
-- ALTERNATIVE: If you want to keep users separate per environment
-- ================================================================================
/*
-- Instead of migrating all users to 'system', you could:

-- 1. Keep customer1 users in local (but these won't be accessible in prod)
-- 2. Keep customer2 users in UAT (but these won't be accessible in prod)  
-- 3. Migrate only customer3 users to system

UPDATE global_users SET tenant_id = 'system' WHERE tenant_id = 'customer3';

-- Then delete only the tenant_config entries for customer1 and customer2
DELETE FROM tenant_config WHERE tenant_id IN ('customer1', 'customer2', 'default');

-- This way customer3 users migrate to system, and you keep the customer3 entry
-- but rename it to something else or just delete it after migration
*/

-- ================================================================================
-- POST-MIGRATION STEPS
-- ================================================================================
-- 1. Deploy updated xamops-service with new MultiTenantDataSourceConfig
-- 2. Deploy updated billops with new MultiTenantDataSourceConfig  
-- 3. Restart applications with prod profile
-- 4. Check logs for:
--    - "PROFILE: PROD - Multi-Tenant Routing Mode"
--    - "Initialized X tenant datasources"
--    - "Tenant IDs: [system, customer4, ...]" (or [system, SISL, ...])
--    - "Default tenant: system"
-- 5. Verify application can connect to both system DB and customer DBs
-- 6. Test login and user operations work correctly
-- ================================================================================
