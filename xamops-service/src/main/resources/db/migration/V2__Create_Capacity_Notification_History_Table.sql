-- Create table for tracking capacity notification history
CREATE TABLE IF NOT EXISTS capacity_notification_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    cidr_id VARCHAR(100) NOT NULL,
    threshold_percent INTEGER NOT NULL,
    actual_percent DOUBLE PRECISION NOT NULL,
    was_extended BOOLEAN NOT NULL DEFAULT FALSE,
    notified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    capacity_name VARCHAR(500),
    user_email VARCHAR(100),

    -- Indexes for faster queries
    CONSTRAINT capacity_notification_history_threshold_check CHECK (threshold_percent IN (80, 90))
);

-- Create indexes for better query performance
CREATE INDEX idx_capacity_notification_cidr_threshold
    ON capacity_notification_history(cidr_id, threshold_percent);

CREATE INDEX idx_capacity_notification_user_id
    ON capacity_notification_history(user_id);

CREATE INDEX idx_capacity_notification_notified_at
    ON capacity_notification_history(notified_at);

-- Add comments for documentation
COMMENT ON TABLE capacity_notification_history IS 'Tracks email notifications sent for FlexCompute capacity threshold alerts (80%, 90%)';
COMMENT ON COLUMN capacity_notification_history.cidr_id IS 'CloudiDR Capacity/Policy ID';
COMMENT ON COLUMN capacity_notification_history.threshold_percent IS 'Threshold percentage that triggered the notification (80 or 90)';
COMMENT ON COLUMN capacity_notification_history.actual_percent IS 'Actual consumption percentage when notification was sent';
COMMENT ON COLUMN capacity_notification_history.was_extended IS 'Whether the capacity was previously extended';
COMMENT ON COLUMN capacity_notification_history.notified_at IS 'Timestamp when the notification email was sent';
