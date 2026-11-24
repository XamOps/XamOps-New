-- Label audit trail: who changed which labels and shares, on what, when, and what they were before.
--
-- label_audit_event: one row per user action — a label created, edited or deleted, the labels on a
-- resource or service saved, a label added to or removed from resources. A rejected attempt is an
-- event too, with outcome REJECTED and the reason in detail.
--
-- label_audit_change: one row per thing an event changed, with that target's full set of labels
-- and shares before and after, as JSON. Applying a label to forty connected resources is one event
-- with forty changes, so it reads as one entry in the account's history and still appears in the
-- history of each resource.
--
-- Append-only. No foreign keys to clients, accounts or labels, on purpose: deleting a label or an
-- account must neither be blocked by its history nor erase it. Names are copied in for the same
-- reason. cloud_account_id is null for client-level events (label create, edit, delete) and on the
-- label's own change row; account_ref keeps the account id as the caller sent it.
--
-- IF NOT EXISTS throughout, because Hibernate ddl-auto may have created both first.
--
-- To reverse:
--   DROP TABLE IF EXISTS label_audit_change;
--   DROP TABLE IF EXISTS label_audit_event;

CREATE TABLE IF NOT EXISTS label_audit_event (
    id               BIGSERIAL     PRIMARY KEY,
    client_id        BIGINT        NOT NULL,
    cloud_account_id BIGINT,
    account_ref      VARCHAR(64),
    actor            VARCHAR(100),
    action           VARCHAR(40)   NOT NULL,
    outcome          VARCHAR(16)   NOT NULL,
    source           VARCHAR(32),
    label_id         BIGINT,
    label_name       VARCHAR(100),
    label_color      VARCHAR(7),
    detail           VARCHAR(1000),
    change_count     INTEGER       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_label_audit_event_client  ON label_audit_event(client_id, id);
CREATE INDEX IF NOT EXISTS idx_label_audit_event_account ON label_audit_event(cloud_account_id, id);

CREATE TABLE IF NOT EXISTS label_audit_change (
    id               BIGSERIAL     PRIMARY KEY,
    event_id         BIGINT        NOT NULL REFERENCES label_audit_event(id),
    cloud_account_id BIGINT,
    target_type      VARCHAR(16)   NOT NULL,
    target_id        VARCHAR(512),
    target_name      VARCHAR(255),
    before_json      TEXT,
    after_json       TEXT
);

CREATE INDEX IF NOT EXISTS idx_label_audit_change_event  ON label_audit_change(event_id);
CREATE INDEX IF NOT EXISTS idx_label_audit_change_target ON label_audit_change(cloud_account_id, target_type, target_id);
