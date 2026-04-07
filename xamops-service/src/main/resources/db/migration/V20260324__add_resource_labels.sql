-- Resource Labels: virtual XamOps-only tagging (not pushed to AWS)
-- Users can create labels with any name (team, department, project, environment, etc.)

CREATE TABLE resource_labels (
    id          BIGSERIAL    PRIMARY KEY,
    client_id   BIGINT       NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    color       VARCHAR(7)   NOT NULL DEFAULT '#6366f1',
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(100),
    CONSTRAINT uq_label_per_client UNIQUE (client_id, name)
);

CREATE INDEX idx_resource_labels_client ON resource_labels(client_id);

-- Maps cloud resources to labels (XamOps-internal only, no changes made to AWS)
CREATE TABLE resource_label_assignments (
    id               BIGSERIAL    PRIMARY KEY,
    cloud_account_id BIGINT       NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    resource_id      VARCHAR(255) NOT NULL,
    resource_type    VARCHAR(100) NOT NULL,
    resource_name    VARCHAR(255),
    region           VARCHAR(50),
    label_id         BIGINT       NOT NULL REFERENCES resource_labels(id) ON DELETE CASCADE,
    tagged_by        VARCHAR(100),
    tagged_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_resource_label UNIQUE (cloud_account_id, resource_id, label_id)
);

CREATE INDEX idx_rla_label      ON resource_label_assignments(label_id);
CREATE INDEX idx_rla_account    ON resource_label_assignments(cloud_account_id);
CREATE INDEX idx_rla_resource   ON resource_label_assignments(cloud_account_id, resource_id);
