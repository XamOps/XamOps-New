-- ============================================================
-- MediaOps: Transcoding jobs, live streams, stored media files
-- ============================================================

CREATE TABLE media_transcode_job (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           BIGINT NOT NULL,
    name                VARCHAR(512) NOT NULL,
    source_url          TEXT NOT NULL,
    output_format       VARCHAR(20) NOT NULL,
    resolution          VARCHAR(20) NOT NULL,
    bitrate             VARCHAR(30),
    fps                 INTEGER,
    enable_adaptive     BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    progress            INTEGER NOT NULL DEFAULT 0,
    input_format        VARCHAR(20),
    input_size_bytes    BIGINT,
    output_size_bytes   BIGINT,
    duration_seconds    INTEGER,
    output_path         TEXT,
    ffmpeg_command      TEXT,
    error_message       TEXT,
    aws_equivalent_cost NUMERIC(10,4),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_media_transcode_job_client_id  ON media_transcode_job(client_id);
CREATE INDEX idx_media_transcode_job_status     ON media_transcode_job(status);
CREATE INDEX idx_media_transcode_job_created_at ON media_transcode_job(created_at DESC);

-- ============================================================

CREATE TABLE media_stream (
    id             BIGSERIAL PRIMARY KEY,
    client_id      BIGINT NOT NULL,
    name           VARCHAR(256) NOT NULL,
    stream_key     VARCHAR(128) NOT NULL UNIQUE,
    status         VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    resolution     VARCHAR(20) NOT NULL DEFAULT '1080p',
    bitrate        VARCHAR(30),
    rtmp_url       TEXT,
    hls_url        TEXT,
    hls_output_dir TEXT,
    viewers        INTEGER NOT NULL DEFAULT 0,
    bandwidth_bytes BIGINT NOT NULL DEFAULT 0,
    uptime_seconds  INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ
);

CREATE INDEX idx_media_stream_client_id ON media_stream(client_id);
CREATE INDEX idx_media_stream_status    ON media_stream(status);

-- ============================================================

CREATE TABLE media_storage_file (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT NOT NULL,
    name         VARCHAR(512) NOT NULL,
    file_type    VARCHAR(20) NOT NULL,
    format       VARCHAR(20) NOT NULL,
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    duration_seconds INTEGER,
    resolution   VARCHAR(30),
    file_path    TEXT NOT NULL,
    job_id       BIGINT REFERENCES media_transcode_job(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_media_storage_file_client_id ON media_storage_file(client_id);
CREATE INDEX idx_media_storage_file_type      ON media_storage_file(file_type);
