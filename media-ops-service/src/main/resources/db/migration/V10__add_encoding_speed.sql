-- Per-job encoding speed (x264 preset): fast | veryfast | superfast | ultrafast.
-- Faster presets trade picture quality for speed at the same bitrate;
-- veryfast is the balanced default.
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS encoding_speed VARCHAR(20) DEFAULT 'veryfast';
