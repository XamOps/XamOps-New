-- Environment cost allocation: split a month of the AWS bill across the client's environments
-- (Prod, UAT, R&D, ...) with ordered rules, and keep an audit-ready record of every split.
--
-- env_allocation_environment   the client's own environment list. Seeded with defaults on first
--                              use; every row can be renamed or deleted.
-- env_allocation_rule_set      one row per client holding the editable DRAFT of the rule list
--                              as JSON, and the number of the latest published version.
-- env_allocation_rule_version  an immutable snapshot of the rules AND the environments they named,
--                              taken at publish. Runs point here, so a past month can always be
--                              explained by the exact rules that produced it.
-- env_allocation_run           one month of one AWS account put through one version.
-- env_allocation_result        the run's output, one row per (environment, rule, service, usage
--                              type, region, charge type, matched tag/label value, percentage).
-- env_allocation_audit         who changed, published, ran, locked or exported what, and when.
--
-- Hibernate ddl-auto=update may create these tables first (UAT, preprod and prod all set it), so
-- every statement is IF NOT EXISTS, column types match what Hibernate maps the entities to, and
-- foreign keys are added only when missing. Idempotent; safe to run twice.
--
-- To reverse:
--   DROP TABLE IF EXISTS env_allocation_result, env_allocation_run, env_allocation_rule_version,
--     env_allocation_rule_set, env_allocation_environment, env_allocation_audit;

CREATE TABLE IF NOT EXISTS env_allocation_environment (
    id            BIGSERIAL     PRIMARY KEY,
    client_id     BIGINT        NOT NULL,
    name          VARCHAR(60)   NOT NULL,
    color         VARCHAR(7)    NOT NULL,
    -- Comma-separated tag or label values that mean this environment, e.g. 'prod,production'.
    match_values  VARCHAR(500),
    sort_order    INTEGER       NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP(6),
    CONSTRAINT uk_env_allocation_environment_name UNIQUE (client_id, name)
);

CREATE TABLE IF NOT EXISTS env_allocation_rule_set (
    id                BIGSERIAL     PRIMARY KEY,
    client_id         BIGINT        NOT NULL,
    draft_json        TEXT          NOT NULL,
    draft_updated_at  TIMESTAMP(6),
    draft_updated_by  VARCHAR(255),
    latest_version    INTEGER       NOT NULL,
    CONSTRAINT uk_env_allocation_rule_set_client UNIQUE (client_id)
);

CREATE TABLE IF NOT EXISTS env_allocation_rule_version (
    id                 BIGSERIAL     PRIMARY KEY,
    client_id          BIGINT        NOT NULL,
    version_number     INTEGER       NOT NULL,
    rules_json         TEXT          NOT NULL,
    environments_json  TEXT          NOT NULL,
    note               VARCHAR(500),
    published_at       TIMESTAMP(6)  NOT NULL,
    published_by       VARCHAR(255),
    CONSTRAINT uk_env_allocation_rule_version UNIQUE (client_id, version_number)
);

CREATE TABLE IF NOT EXISTS env_allocation_run (
    id                BIGSERIAL       PRIMARY KEY,
    client_id         BIGINT          NOT NULL,
    cloud_account_id  BIGINT          NOT NULL,
    aws_account_id    VARCHAR(12)     NOT NULL,
    billing_period    VARCHAR(7)      NOT NULL,
    cost_basis        VARCHAR(20)     NOT NULL,
    rule_version_id   BIGINT          NOT NULL,
    version_number    INTEGER         NOT NULL,
    status            VARCHAR(20)     NOT NULL,
    error             TEXT,
    total_cost        NUMERIC(20,6),
    allocated_cost    NUMERIC(20,6),
    unallocated_cost  NUMERIC(20,6),
    line_count        INTEGER,
    cur_source_id     BIGINT,
    queued_at         TIMESTAMP(6)    NOT NULL,
    started_at        TIMESTAMP(6),
    finished_at       TIMESTAMP(6),
    started_by        VARCHAR(255),
    locked            BOOLEAN         NOT NULL,
    locked_at         TIMESTAMP(6),
    locked_by         VARCHAR(255),
    CONSTRAINT ck_env_allocation_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_env_allocation_run_month
    ON env_allocation_run (client_id, aws_account_id, billing_period);

CREATE TABLE IF NOT EXISTS env_allocation_result (
    id                BIGSERIAL       PRIMARY KEY,
    run_id            BIGINT          NOT NULL,
    environment_name  VARCHAR(60)     NOT NULL,
    environment_color VARCHAR(7),
    allocation_kind   VARCHAR(20)     NOT NULL,
    rule_id           VARCHAR(40),
    rule_name         VARCHAR(120),
    rule_position     INTEGER,
    service           VARCHAR(255),
    usage_type        VARCHAR(255),
    region            VARCHAR(64),
    charge_type       VARCHAR(64),
    match_detail      VARCHAR(255),
    percent           NUMERIC(9,4),
    source_cost       NUMERIC(20,6)   NOT NULL,
    amount            NUMERIC(20,6)   NOT NULL,
    line_count        INTEGER         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_env_allocation_result_run ON env_allocation_result (run_id);

CREATE TABLE IF NOT EXISTS env_allocation_audit (
    id           BIGSERIAL     PRIMARY KEY,
    client_id    BIGINT        NOT NULL,
    actor        VARCHAR(255),
    action       VARCHAR(40)   NOT NULL,
    entity_type  VARCHAR(40),
    entity_ref   VARCHAR(100),
    detail       TEXT,
    created_at   TIMESTAMP(6)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_env_allocation_audit_client
    ON env_allocation_audit (client_id, created_at);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'env_allocation_result'::regclass
          AND confrelid = 'env_allocation_run'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE env_allocation_result
            ADD CONSTRAINT fk_env_allocation_result_run FOREIGN KEY (run_id)
            REFERENCES env_allocation_run (id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'env_allocation_run'::regclass
          AND confrelid = 'env_allocation_rule_version'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE env_allocation_run
            ADD CONSTRAINT fk_env_allocation_run_version FOREIGN KEY (rule_version_id)
            REFERENCES env_allocation_rule_version (id);
    END IF;
END $$;
