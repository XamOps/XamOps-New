-- ============================================================
-- Add advanced transcoding columns to media_transcode_job
-- Supports: codecs, priority, batch, presets, trimming,
-- subtitles, watermarks, thumbnails, audio-only, custom params,
-- scheduling, and encryption.
-- ============================================================

-- Codec selection (H264, H265, AV1, VP9)
ALTER TABLE media_transcode_job ADD COLUMN codec VARCHAR(20);

-- Job priority (HIGH, NORMAL, LOW)
ALTER TABLE media_transcode_job ADD COLUMN priority VARCHAR(10) DEFAULT 'NORMAL';

-- Batch and preset references
ALTER TABLE media_transcode_job ADD COLUMN batch_id BIGINT;
ALTER TABLE media_transcode_job ADD COLUMN preset_id BIGINT;

-- Trimming (start/end time in HH:MM:SS or seconds)
ALTER TABLE media_transcode_job ADD COLUMN start_time VARCHAR(20);
ALTER TABLE media_transcode_job ADD COLUMN end_time VARCHAR(20);

-- Subtitle support
ALTER TABLE media_transcode_job ADD COLUMN subtitle_path TEXT;
ALTER TABLE media_transcode_job ADD COLUMN subtitle_mode VARCHAR(10);

-- Watermark overlay
ALTER TABLE media_transcode_job ADD COLUMN watermark_image_path TEXT;
ALTER TABLE media_transcode_job ADD COLUMN watermark_text VARCHAR(500);
ALTER TABLE media_transcode_job ADD COLUMN watermark_position VARCHAR(15);
ALTER TABLE media_transcode_job ADD COLUMN watermark_opacity DOUBLE PRECISION;

-- Thumbnail generation
ALTER TABLE media_transcode_job ADD COLUMN thumbnail_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE media_transcode_job ADD COLUMN thumbnail_interval_seconds INTEGER;
ALTER TABLE media_transcode_job ADD COLUMN thumbnail_timestamp VARCHAR(20);
ALTER TABLE media_transcode_job ADD COLUMN thumbnail_paths TEXT;

-- Audio-only transcoding parameters
ALTER TABLE media_transcode_job ADD COLUMN audio_bitrate VARCHAR(20);
ALTER TABLE media_transcode_job ADD COLUMN audio_sample_rate INTEGER;
ALTER TABLE media_transcode_job ADD COLUMN audio_channels INTEGER;

-- Custom FFmpeg parameters
ALTER TABLE media_transcode_job ADD COLUMN custom_ffmpeg_params TEXT;

-- Job scheduling
ALTER TABLE media_transcode_job ADD COLUMN scheduled_at TIMESTAMPTZ;

-- DRM / Encryption (AES-128 for HLS)
ALTER TABLE media_transcode_job ADD COLUMN encryption_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE media_transcode_job ADD COLUMN encryption_key_path TEXT;
ALTER TABLE media_transcode_job ADD COLUMN encryption_key_url TEXT;

-- Index for priority queue ordering
CREATE INDEX idx_media_transcode_job_priority_queue
    ON media_transcode_job(priority, created_at ASC)
    WHERE status = 'QUEUED';

-- Index for scheduled job polling
CREATE INDEX idx_media_transcode_job_scheduled
    ON media_transcode_job(scheduled_at)
    WHERE status = 'SCHEDULED';

-- Index for batch lookups
CREATE INDEX idx_media_transcode_job_batch_id
    ON media_transcode_job(batch_id)
    WHERE batch_id IS NOT NULL;
