-- Database Migration Script for Credit Request Attachments
-- Adds the column that stores supporting documents attached to a cloud credit request.
--
-- The column holds a JSON array of S3 object metadata, one entry per document:
--   [{"key": "credits/<uuid>/proposal.pdf", "name": "proposal.pdf", "type": "application/pdf", "size": 84213}]
--
-- Environments running spring.jpa.hibernate.ddl-auto=update (local, uat, preprod, prod)
-- pick this up automatically; run this script anywhere schema updates are disabled.

ALTER TABLE credit_requests ADD COLUMN IF NOT EXISTS attachments_json TEXT;
