-- Create Notification table for in-app notifications
CREATE TABLE media_notification (
    id BIGINT PRIMARY KEY,
    client_id BIGINT NOT NULL,
    job_id BIGINT,
    type VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE SEQUENCE media_notification_id_seq START 1 INCREMENT 1;

CREATE INDEX idx_notification_client_unread ON media_notification(client_id, is_read, created_at DESC);
