-- XamPulse alerting: rules, edge-triggered state, incidents, silences, channels.

CREATE TABLE IF NOT EXISTS xp_alert_rule (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    name VARCHAR(200) NOT NULL,
    severity VARCHAR(12) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    definition_json TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_xp_alert_rule_account ON xp_alert_rule (cloud_account_id);

CREATE TABLE IF NOT EXISTS xp_alert_state (
    rule_id BIGINT NOT NULL REFERENCES xp_alert_rule(id) ON DELETE CASCADE,
    group_key VARCHAR(512) NOT NULL,
    state VARCHAR(12) NOT NULL,
    since TIMESTAMPTZ NOT NULL,
    last_eval_at TIMESTAMPTZ,
    last_value DOUBLE PRECISION,
    PRIMARY KEY (rule_id, group_key)
);

CREATE TABLE IF NOT EXISTS xp_incident (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    title VARCHAR(300) NOT NULL,
    severity VARCHAR(12) NOT NULL,
    state VARCHAR(12) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    ack_by VARCHAR(200),
    snoozed_until TIMESTAMPTZ,
    ticket_id BIGINT,
    summary_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_xp_incident_account ON xp_incident (cloud_account_id, state);

CREATE TABLE IF NOT EXISTS xp_incident_event (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES xp_incident(id) ON DELETE CASCADE,
    ts TIMESTAMPTZ NOT NULL,
    kind VARCHAR(24) NOT NULL,
    payload_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_xp_incident_event_incident ON xp_incident_event (incident_id);

CREATE TABLE IF NOT EXISTS xp_silence (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    matcher_json TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    reason VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_xp_silence_account ON xp_silence (cloud_account_id);

CREATE TABLE IF NOT EXISTS xp_channel (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    kind VARCHAR(12) NOT NULL,
    name VARCHAR(200) NOT NULL,
    config_enc TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_xp_channel_account ON xp_channel (cloud_account_id);
