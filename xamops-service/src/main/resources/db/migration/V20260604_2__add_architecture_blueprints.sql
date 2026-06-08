CREATE TABLE IF NOT EXISTS architecture_blueprints (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    graph_data  TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
