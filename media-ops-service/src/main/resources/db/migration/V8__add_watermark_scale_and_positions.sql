-- Add watermark_scale column (percentage of video width, default 15)
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS watermark_scale INTEGER;

-- Extend watermark_position column to fit new position names (longest: BOTTOM_CENTER = 13 chars)
ALTER TABLE media_transcode_job ALTER COLUMN watermark_position TYPE VARCHAR(15);
