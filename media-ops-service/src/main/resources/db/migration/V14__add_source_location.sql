-- Where a job's source object lives, so its presigned source_url can be signed again at
-- the moment FFmpeg starts rather than replayed from submit time.
--
-- source_url alone is not enough to run a job twice: it expires (X-Amz-Expires) and, when
-- signed with STS session credentials, stops working when that session ends, whichever
-- comes first. A job that waited in the queue, ran on a schedule, or was retried the next
-- day therefore failed with "HTTP error 403 Forbidden" however many times it was retried.
--   * source_account_id + source_bucket — a bucket in the customer's connected AWS
--     account, read through that account's onboarding IAM role
--   * source_s3_config_id               — a key-based connected bucket (user_s3_configs)
--   * source_region                     — resolved bucket region, cached for signing
-- All NULL for pasted URLs and local uploads, which have nothing to re-sign. Also NULL on
-- rows created before this migration; those are recovered by probing the client's
-- connected accounts once and writing the answer back.
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS source_bucket VARCHAR(255);
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS source_region VARCHAR(64);
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS source_account_id VARCHAR(64);
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS source_s3_config_id BIGINT;
