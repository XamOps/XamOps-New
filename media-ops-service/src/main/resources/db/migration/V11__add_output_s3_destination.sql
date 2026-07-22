-- Per-job output destination in a customer's own S3 bucket.
-- output_s3_config_id references a user_s3_configs row (the customer's connected
-- bucket + credentials). When NULL, output stays in XamOps storage (local disk /
-- shared bucket) exactly as before. output_bucket_prefix records the key prefix
-- under which the job's files were written in that customer bucket, so playback
-- and download can locate them.
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS output_s3_config_id BIGINT;
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS output_bucket_prefix TEXT;
