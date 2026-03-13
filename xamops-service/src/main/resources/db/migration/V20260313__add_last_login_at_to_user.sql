-- Track when each user last successfully logged in
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP NULL;
