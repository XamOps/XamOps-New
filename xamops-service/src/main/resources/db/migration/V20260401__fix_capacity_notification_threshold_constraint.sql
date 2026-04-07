-- Fix: Allow threshold_percent = 0 as a sentinel value for deployment notifications.
-- The original constraint only allowed 80 and 90, but CapacityMonitoringService uses
-- threshold_percent = 0 to track "capacity deployed" emails, breaking de-duplication.
-- Also drops the Hibernate-generated duplicate constraint (_threshold_percent_check)
-- which can appear when ddl-auto=update/create ran before this migration.
ALTER TABLE capacity_notification_history
    DROP CONSTRAINT IF EXISTS capacity_notification_history_threshold_check;

ALTER TABLE capacity_notification_history
    DROP CONSTRAINT IF EXISTS capacity_notification_history_threshold_percent_check;

ALTER TABLE capacity_notification_history
    ADD CONSTRAINT capacity_notification_history_threshold_check
        CHECK (threshold_percent IN (0, 80, 90));
