-- Database Migration Script for Credit Request Priority
-- Adds the column that stores the urgency the requester picked on the submission form.
--
-- Values are 'low', 'medium' or 'high'. New requests default to 'medium', the same value the
-- form starts on. Rows created before this column existed stay NULL: the priority was never
-- captured for them, and both the requester view and the admin table render no priority rather
-- than showing a value nobody chose.
--
-- Environments running spring.jpa.hibernate.ddl-auto=update (local, uat, preprod, prod)
-- pick this up automatically; run this script anywhere schema updates are disabled.

ALTER TABLE credit_requests ADD COLUMN IF NOT EXISTS priority VARCHAR(20);
