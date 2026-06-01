-- Migration: Create client_intake_submission table
-- Stores onboarding forms submitted by new XamOps users on first login.
--
-- Run this once against your PostgreSQL database before deploying the updated application.

CREATE TABLE IF NOT EXISTS client_intake_submission (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    company_name  VARCHAR(255) NOT NULL,
    email         VARCHAR(255),
    phone         VARCHAR(50),
    address       TEXT,
    notes         TEXT,
    account_ids   TEXT,
    submitted_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS idx_client_intake_submitted_at
    ON client_intake_submission (submitted_at DESC);
