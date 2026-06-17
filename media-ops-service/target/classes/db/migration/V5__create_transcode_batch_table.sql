-- Create TranscodeBatch table for batch/bulk processing
CREATE TABLE media_transcode_batch (
    id BIGINT PRIMARY KEY,
    client_id BIGINT NOT NULL,
    name VARCHAR(255),
    total_jobs INTEGER NOT NULL,
    completed_jobs INTEGER DEFAULT 0,
    failed_jobs INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE SEQUENCE media_transcode_batch_id_seq START 1 INCREMENT 1;

CREATE INDEX idx_media_transcode_batch_client_id ON media_transcode_batch(client_id);
