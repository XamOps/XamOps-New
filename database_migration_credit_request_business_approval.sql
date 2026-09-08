-- Database Migration Script for the Credit Request "Business Approval" stage
-- Renames the stored stage 'technical_verification' to 'business_approval'.
--
-- The pipeline is now the same on both sides of the product:
--   submitted -> documentation_review -> business_approval -> approved / rejected
-- Requesters see the approved stage as "Credited"; the admin who decides it sees "Approved".
--
-- This script is optional cleanup. CreditRequestService translates the old value on both read
-- and write, so requests still holding it render and progress correctly without it. Run it to
-- stop carrying the pre-rename value in the table.

UPDATE credit_requests SET status = 'business_approval' WHERE status = 'technical_verification';
