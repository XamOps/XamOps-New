CREATE SEQUENCE sre_investigation_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE sre_notification_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE sre_investigation (
    id                      BIGINT PRIMARY KEY DEFAULT nextval('sre_investigation_id_seq'),
    client_id               BIGINT NOT NULL REFERENCES client(id),
    cloud_account_id        BIGINT REFERENCES cloud_account(id),
    trigger_type            VARCHAR(10) NOT NULL CHECK (trigger_type IN ('AUTO', 'MANUAL')),
    resource_type           VARCHAR(20) NOT NULL CHECK (resource_type IN ('K8S_POD', 'EC2', 'RDS', 'AZURE_VM', 'GCP_VM')),
    resource_id             VARCHAR(255) NOT NULL,
    resource_name           VARCHAR(255),
    status                  VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    alert_payload           TEXT,
    report_summary          TEXT,
    report_root_cause       TEXT,
    report_recommendations  TEXT,
    report_full_markdown    TEXT,
    opensre_thread_id       VARCHAR(255),
    error_message           TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at              TIMESTAMP,
    completed_at            TIMESTAMP
);

CREATE INDEX idx_sre_investigation_client_id ON sre_investigation(client_id);
CREATE INDEX idx_sre_investigation_status ON sre_investigation(status);

CREATE TABLE sre_notification (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('sre_notification_id_seq'),
    client_id           BIGINT NOT NULL REFERENCES client(id),
    investigation_id    BIGINT NOT NULL REFERENCES sre_investigation(id),
    title               VARCHAR(500) NOT NULL,
    message             TEXT,
    is_read             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sre_notification_client_id ON sre_notification(client_id);
CREATE INDEX idx_sre_notification_is_read ON sre_notification(client_id, is_read);
