-- Remembers which S3 object a job was created from, so transcoded output can be written
-- back under a path that mirrors the source instead of an opaque jobs/<id>/ folder.
--
-- Only set when the source was chosen through the S3 browser; direct URLs and uploads
-- leave it null and keep the jobs/<id>/ layout.
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS source_key TEXT;
