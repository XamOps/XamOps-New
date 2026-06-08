CREATE TABLE IF NOT EXISTS architecture_blueprints (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    graph_data  LONGTEXT,
    created_at  DATETIME,
    updated_at  DATETIME
);
