-- Free-form watermark positioning: center of watermark as % of video dimensions
-- Overrides the enum watermark_position column when both are non-null
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS watermark_x DOUBLE PRECISION;
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS watermark_y DOUBLE PRECISION;
