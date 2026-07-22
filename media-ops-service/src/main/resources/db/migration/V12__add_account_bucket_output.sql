-- Output destination in a bucket of the customer's connected cloud account (the
-- account selected in the sidebar), written via the same cross-account IAM role
-- XamOps already uses to read that account elsewhere. This complements the
-- key-based user_s3_configs destination (output_s3_config_id):
--   * output_account_id — the AWS account number (provider account id)
--   * output_bucket      — the chosen bucket name in that account
--   * output_region      — resolved bucket region (cached for playback/download)
-- All NULL when output goes to XamOps storage or a key-based connection.
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS output_account_id VARCHAR(64);
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS output_bucket VARCHAR(255);
ALTER TABLE media_transcode_job ADD COLUMN IF NOT EXISTS output_region VARCHAR(64);
