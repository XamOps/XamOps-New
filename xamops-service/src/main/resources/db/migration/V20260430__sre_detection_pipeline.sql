-- slo_definition: per-client signal config
CREATE TABLE slo_definition (
  id                  BIGSERIAL PRIMARY KEY,
  client_id           BIGINT NOT NULL REFERENCES client(id),
  entity              TEXT NOT NULL,
  signal              TEXT NOT NULL,
  stream_name         TEXT NOT NULL,
  query_template      TEXT NOT NULL,
  window_seconds      INT NOT NULL DEFAULT 300,
  zscore_threshold    DOUBLE PRECISION NOT NULL DEFAULT 3.0,
  cooldown_seconds    INT NOT NULL DEFAULT 600,
  enabled             BOOLEAN NOT NULL DEFAULT TRUE,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_slo_definition_client_entity_signal UNIQUE (client_id, entity, signal)
);

-- detection_event: audit log of every anomaly before grouping
CREATE TABLE detection_event (
  id                  BIGSERIAL PRIMARY KEY,
  client_id           BIGINT NOT NULL REFERENCES client(id),
  slo_definition_id   BIGINT REFERENCES slo_definition(id),
  entity              TEXT NOT NULL,
  signal              TEXT NOT NULL,
  score               DOUBLE PRECISION NOT NULL,
  metric_value        DOUBLE PRECISION,
  metric_baseline     DOUBLE PRECISION,
  evidence_logs       TEXT,
  investigation_id    BIGINT REFERENCES sre_investigation(id),
  kafka_offset        BIGINT,
  detected_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- add incident lifecycle fields to existing sre_investigation
-- incident_status values match Java IncidentStatus enum serialised via @Enumerated(EnumType.STRING)
ALTER TABLE sre_investigation
  ADD COLUMN IF NOT EXISTS incident_id      TEXT,
  ADD COLUMN IF NOT EXISTS version          INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS incident_status  TEXT NOT NULL DEFAULT 'OPEN'
    CHECK (incident_status IN ('OPEN','FINAL','CLOSED')),
  ADD COLUMN IF NOT EXISTS closed_at        TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_slo_definition_enabled ON slo_definition(enabled) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_detection_event_client ON detection_event(client_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_detection_event_investigation ON detection_event(investigation_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sre_investigation_incident
  ON sre_investigation(incident_id) WHERE incident_id IS NOT NULL;
