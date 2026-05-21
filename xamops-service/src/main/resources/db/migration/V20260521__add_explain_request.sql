CREATE TABLE explain_request (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    cloud_account_id BIGINT       NOT NULL,
    agent_name       VARCHAR(255) NOT NULL,
    dsn_alias        VARCHAR(50)  NOT NULL,
    query_text       TEXT         NOT NULL,
    db_engine        VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    result_json      TEXT,
    error_message    VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     TIMESTAMP
);

CREATE INDEX idx_explain_request_agent_status
    ON explain_request (agent_name, status);

CREATE INDEX idx_explain_request_status_created
    ON explain_request (status, created_at);
