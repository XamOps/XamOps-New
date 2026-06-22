CREATE TABLE user_s3_configs (
    id            BIGSERIAL PRIMARY KEY,
    client_id     BIGINT NOT NULL,
    nickname      VARCHAR(255) NOT NULL,
    bucket_name   VARCHAR(255) NOT NULL,
    region        VARCHAR(100) NOT NULL,
    access_key    TEXT,
    secret_key    TEXT,
    prefix_filter VARCHAR(500),
    endpoint_url  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_s3_configs_client_id ON user_s3_configs(client_id);
