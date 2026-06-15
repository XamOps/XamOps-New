-- Add output_url to store S3/CDN base URL for completed jobs.
-- When NULL, the service falls back to serving files from local storage.
ALTER TABLE media_transcode_job ADD COLUMN output_url TEXT;
