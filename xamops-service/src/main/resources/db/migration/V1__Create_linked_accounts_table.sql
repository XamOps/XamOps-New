-- Create linked_accounts table for storing CloudiDR linked accounts
CREATE TABLE IF NOT EXISTS linked_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    account_name VARCHAR(255),
    provider VARCHAR(100),
    status VARCHAR(50),
    linked_at TIMESTAMP,
    synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_user_account UNIQUE (user_id, account_id)
);

-- Create index on user_id for faster queries
CREATE INDEX IF NOT EXISTS idx_linked_accounts_user_id ON linked_accounts(user_id);
