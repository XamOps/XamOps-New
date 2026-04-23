-- Migration: Add owner_admin_id to client table
-- Supports ROLE_BILLOPS_ADMIN_SUB scoping feature.
--
-- A client with owner_admin_id = NULL belongs to the primary ROLE_BILLOPS_ADMIN pool
-- (visible to all primary admins, NOT visible to any ROLE_BILLOPS_ADMIN_SUB).
-- A client with owner_admin_id = <id> is owned exclusively by that ROLE_BILLOPS_ADMIN_SUB user
-- and is NOT visible to other sub-admins (but IS visible to the primary ROLE_BILLOPS_ADMIN).
--
-- Run this once against your PostgreSQL database before deploying the updated application.

ALTER TABLE client
    ADD COLUMN IF NOT EXISTS owner_admin_id BIGINT NULL
        REFERENCES app_user(id) ON DELETE SET NULL;

-- Optional index for query performance (getSupportDashboard scoping)
CREATE INDEX IF NOT EXISTS idx_client_owner_admin_id ON client (owner_admin_id);
