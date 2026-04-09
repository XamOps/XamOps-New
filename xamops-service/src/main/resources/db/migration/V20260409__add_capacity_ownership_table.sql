CREATE TABLE IF NOT EXISTS capacity_ownership (
    id BIGSERIAL PRIMARY KEY,
    cidr_id VARCHAR(100) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    purchased_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_capacity_ownership_user_id ON capacity_ownership(user_id);
