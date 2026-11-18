-- AutoSpotting Phase B (B4) billing-grade savings ledger schema, SHADOW mode (plan-phase-15.md task
-- 15.1; design-ledger.md §2.1-§2.4, §8.4). One additive, guarded, idempotent migration, numbered
-- above V20261107_1 (the plan migration) and above everything on origin/main and in every sibling
-- worktree at authoring time (impl/g4-migration-versions.md).
--
-- Shadow means: the tables exist and are append-only, contracts and the go-live instant can be
-- recorded, and nothing writes ledger entries yet (the writer flags stay false). Nothing here
-- invoices or shows a customer anything billable. The fee objects (autospotting_fee_invoice*, the
-- fee view and functions) are not in this file; they ship in Phase 34.
--
-- Rules (design-core.md §10, MigrationRulesTest, P01-R4): CREATE ... IF NOT EXISTS everywhere;
-- functions via CREATE OR REPLACE FUNCTION; triggers and constraints only after a to_regclass /
-- pg_constraint guard; no DROP of a table, column or CASCADE, no DELETE/UPDATE without WHERE; no FK
-- to cloud_account or client or anywhere (FR-LED-31, PO-21 retention); every money and rate column
-- is NUMERIC, every instant TIMESTAMPTZ (FR-LED-17, NFR-07). Running it twice changes nothing.
--
-- In UAT and prod the tables that have a JPA entity (spot_price_snapshot, savings_ledger_run,
-- savings_ledger_entry, savings_ledger_entry_item, savings_bill_fact, savings_statement_line,
-- autospotting_contract, savings_ledger_golive) are built by ddl-auto=update before Flyway runs, so
-- their CREATE TABLE below is then a no-op. Every named constraint, default and trigger is therefore
-- added by a guarded step AFTER the CREATE TABLEs, so it lands whichever way the table was made.

-- ── 2.1 measurement inputs (working tables, not money) ───────────────────────
CREATE TABLE IF NOT EXISTS spot_usage_interval (
  id                   UUID PRIMARY KEY,
  provider             VARCHAR(8)    NOT NULL,
  cloud_account_id     BIGINT        NOT NULL,
  group_id             UUID          NOT NULL,
  instance_id          VARCHAR(200)  NOT NULL,
  instance_name        VARCHAR(200),                 -- GCP resource.name
  instance_type        VARCHAR(64)   NOT NULL,
  zone                 VARCHAR(64),
  platform             VARCHAR(32),
  purchase_option      VARCHAR(12)   NOT NULL,       -- SPOT | ON_DEMAND
  capacity_weight      NUMERIC(12,6) NOT NULL,
  start_at             TIMESTAMPTZ   NOT NULL,
  start_source         VARCHAR(24),
  last_seen_at         TIMESTAMPTZ,                  -- end lower bound
  first_absent_at      TIMESTAMPTZ,                  -- end upper bound
  end_at               TIMESTAMPTZ,
  end_source           VARCHAR(24),                  -- ASG_ACTIVITY | GCE_OPERATION | CUR
  end_cause            VARCHAR(24),                  -- PROVIDER_INTERRUPTED | SCALE_IN | REPLACED | USER | UNKNOWN
  end_cause_source     VARCHAR(32),                  -- EC2_STATE_REASON | ASG_ACTIVITY_CAUSE | GCE_PREEMPTED_OPERATION
  provider_interrupted BOOLEAN       NOT NULL DEFAULT false,
  closed               BOOLEAN       NOT NULL DEFAULT false,
  created_at           TIMESTAMPTZ   NOT NULL,
  updated_at           TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sui_instance ON spot_usage_interval (provider, instance_id, start_at);
CREATE INDEX IF NOT EXISTS ix_sui_group_start ON spot_usage_interval (group_id, start_at);

CREATE TABLE IF NOT EXISTS spot_group_capacity_sample (    -- desired-capacity series (FR-LED-12)
  group_id             UUID          NOT NULL,
  observed_at          TIMESTAMPTZ   NOT NULL,
  desired_units        NUMERIC(12,6) NOT NULL,
  od_floor_units       NUMERIC(12,6) NOT NULL,
  original_spot_units  NUMERIC(12,6) NOT NULL,
  source               VARCHAR(16)   NOT NULL,         -- SWEEP | ASG_ACTIVITY | MIG_GET
  PRIMARY KEY (group_id, observed_at)
);

-- ── 2.2 prices, runs and entries (append-only) ───────────────────────────────
CREATE TABLE IF NOT EXISTS spot_price_snapshot (           -- AC-LED-18
  id                           UUID PRIMARY KEY,
  provider                     VARCHAR(8)     NOT NULL,
  source                       VARCHAR(24)    NOT NULL,    -- AWS_SPOT_HISTORY | AWS_PRICE_LIST | AWS_CUR | AWS_CE | GCP_CATALOG | GCP_EXPORT | CONTRACT
  price_kind                   VARCHAR(16)    NOT NULL,    -- SPOT | ON_DEMAND | SUD_RATE | DISCOUNT_PCT
  region                       VARCHAR(64)    NOT NULL,
  zone                         VARCHAR(64),
  sku_or_type                  VARCHAR(200)   NOT NULL,
  platform                     VARCHAR(32),
  rate                         NUMERIC(24,12) NOT NULL,    -- per unit, at least 10 dp (FR-LED-17)
  unit                         VARCHAR(16)    NOT NULL,    -- HOUR | VCPU_HOUR | GIB_HOUR | RATIO
  currency                     CHAR(3)        NOT NULL,
  effective_from               TIMESTAMPTZ    NOT NULL,
  effective_to                 TIMESTAMPTZ,                -- as stated by the source (§4.4)
  source_version               VARCHAR(200)   NOT NULL,
  source_effective_time_stated BOOLEAN        NOT NULL,
  fetched_at                   TIMESTAMPTZ    NOT NULL,
  payload_sha256               VARCHAR(64)    NOT NULL,
  payload                      JSONB          NOT NULL     -- raw source record
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sps_dedupe ON spot_price_snapshot
  (provider, source, price_kind, region, COALESCE(zone, ''), sku_or_type, COALESCE(platform, ''),
   effective_from, payload_sha256);
CREATE INDEX IF NOT EXISTS ix_sps_lookup ON spot_price_snapshot
  (provider, price_kind, region, sku_or_type, effective_from);

CREATE TABLE IF NOT EXISTS savings_ledger_run (
  id                   UUID PRIMARY KEY,
  provider             VARCHAR(8)   NOT NULL,
  cloud_account_id     BIGINT       NOT NULL,
  window_start         TIMESTAMPTZ  NOT NULL,
  window_end           TIMESTAMPTZ  NOT NULL,              -- one UTC day
  calc_version         VARCHAR(16)  NOT NULL,              -- e.g. 'L1.0.0'
  inputs_sha256        VARCHAR(64)  NOT NULL,
  supersedes_run_id    UUID,                               -- previous current run for the window, or null
  trigger              VARCHAR(24)  NOT NULL,              -- DAILY | BILL_ARRIVED | PRICE_RESOLVED | CALC_UPGRADE | MONTH_VERDICT
  job_lease_name       VARCHAR(64)  NOT NULL,
  lease_generation     BIGINT       NOT NULL,
  created_at           TIMESTAMPTZ  NOT NULL
);
-- Linear chain per window: one root, one successor per run. Two writers cannot fork it (FR-LED-16).
CREATE UNIQUE INDEX IF NOT EXISTS ux_slr_root ON savings_ledger_run (provider, cloud_account_id, window_start)
  WHERE supersedes_run_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_slr_chain ON savings_ledger_run (supersedes_run_id)
  WHERE supersedes_run_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS savings_ledger_entry (
  id                         UUID PRIMARY KEY,
  run_id                     UUID,                         -- null for ADJUSTMENT
  entry_type                 VARCHAR(12)    NOT NULL,      -- ACCRUAL | ADJUSTMENT
  provider                   VARCHAR(8)     NOT NULL,
  cloud_account_id           BIGINT         NOT NULL,
  client_id                  BIGINT         NOT NULL,
  group_id                   UUID           NOT NULL,
  period_start               TIMESTAMPTZ    NOT NULL,
  period_end                 TIMESTAMPTZ    NOT NULL,
  statement_month            DATE           NOT NULL,      -- first day of the UTC month it posts to
  calc_version               VARCHAR(16)    NOT NULL,
  tier                       VARCHAR(20)    NOT NULL,      -- BILL_VERIFIED | AGGREGATE_VERIFIED | MEASURED_ONLY | UNKNOWN
  reconciliation_status      VARCHAR(20)    NOT NULL,      -- PENDING | RECONCILED | VARIANCE_DETECTED
  reconciled_at              TIMESTAMPTZ,
  billable                   BOOLEAN        NOT NULL,
  non_billable_reason        VARCHAR(48),
  unknown_inputs             TEXT[]         NOT NULL DEFAULT '{}',
  currency                   CHAR(3)        NOT NULL,
  instance_seconds           BIGINT         NOT NULL,
  counterfactual_unit_seconds NUMERIC(24,6) NOT NULL,
  counterfactual_list_cost   NUMERIC(24,10) NOT NULL,
  effective_rate_adjustment  NUMERIC(24,10) NOT NULL,
  counterfactual_cost        NUMERIC(24,10) NOT NULL,
  spot_cost                  NUMERIC(24,10) NOT NULL,
  gross_savings              NUMERIC(24,10) NOT NULL,
  commitment_deduction       NUMERIC(24,10) NOT NULL,
  net_savings                NUMERIC(24,10) NOT NULL,      -- may be negative; clamped only on the statement line
  net_savings_lower_bound    NUMERIC(24,10),
  spot_cost_source           VARCHAR(24)    NOT NULL,      -- BILL | MEASURED
  price_snapshot_ids         UUID[]         NOT NULL,
  bill_import_ids            UUID[]         NOT NULL DEFAULT '{}',
  contract_id                UUID,
  fx_rate                    NUMERIC(24,10),
  fx_source                  VARCHAR(64),
  fx_at                      TIMESTAMPTZ,
  exclusions                 JSONB          NOT NULL,
  inputs                     JSONB          NOT NULL,
  inputs_sha256              VARCHAR(64)    NOT NULL,
  adjustment_ref             UUID,
  adjustment_request_id      UUID,
  lease_generation           BIGINT,
  created_at                 TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sle_run_group ON savings_ledger_entry (run_id, group_id) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_sle_account_month ON savings_ledger_entry (cloud_account_id, statement_month, group_id);
CREATE INDEX IF NOT EXISTS ix_sle_group_period  ON savings_ledger_entry (group_id, period_start);
CREATE INDEX IF NOT EXISTS ix_sle_adjref        ON savings_ledger_entry (adjustment_ref) WHERE adjustment_ref IS NOT NULL;

CREATE TABLE IF NOT EXISTS savings_ledger_entry_item (     -- per-instance detail; instance_id lives here
  entry_id                    UUID           NOT NULL,
  seq                         INT            NOT NULL,
  instance_id                 VARCHAR(200)   NOT NULL,
  interval_id                 UUID           NOT NULL,
  instance_type               VARCHAR(64)    NOT NULL,
  zone                        VARCHAR(64)    NOT NULL,
  platform                    VARCHAR(32)    NOT NULL,
  purchase_option             VARCHAR(12)    NOT NULL,
  capacity_weight             NUMERIC(12,6)  NOT NULL,
  seconds                     BIGINT         NOT NULL,
  billed_seconds              BIGINT         NOT NULL,     -- 0 when the provider did not charge
  counterfactual_unit_seconds NUMERIC(24,6)  NOT NULL,
  od_rate                     NUMERIC(24,12),
  od_rate_snapshot_id         UUID,
  od_rate_basis               VARCHAR(16),                 -- RAN_TYPE | PRE_ENABLE_TYPE
  spot_rate                   NUMERIC(24,12),
  spot_rate_snapshot_id       UUID,
  counterfactual_cost         NUMERIC(24,10) NOT NULL,
  spot_cost                   NUMERIC(24,10) NOT NULL,
  role                        VARCHAR(20)    NOT NULL,     -- MANAGED_SPOT | OVERLAP | OD_FLOOR | OD_FALLBACK | ORIGINAL_SHARE
  PRIMARY KEY (entry_id, seq)
);
CREATE INDEX IF NOT EXISTS ix_slei_instance ON savings_ledger_entry_item (instance_id);

-- ── 2.3 statements, adjustments, disputes, configuration ─────────────────────
CREATE TABLE IF NOT EXISTS savings_statement (             -- also the period lock (§8.2)
  id                   UUID PRIMARY KEY,
  provider             VARCHAR(8)     NOT NULL,
  cloud_account_id     BIGINT         NOT NULL,
  client_id            BIGINT         NOT NULL,
  statement_month      DATE           NOT NULL,
  status               VARCHAR(12)    NOT NULL,            -- OPEN | CLOSED | INVOICED (FR-LED-26)
  version              INT            NOT NULL DEFAULT 1,
  calc_version         VARCHAR(16),
  currency             CHAR(3)        NOT NULL DEFAULT 'USD',   -- GR-05: NOT NULL, USD-only (PD-01)
  run_ids              UUID[],
  invoiceable          BOOLEAN,
  invoiceable_reason   VARCHAR(64),
  share_pct            NUMERIC(9,6),
  contract_id          UUID,
  total_billable       NUMERIC(19,2),
  total_raw            NUMERIC(24,10),
  content_sha256       VARCHAR(64),
  annex                JSONB,
  closed_at            TIMESTAMPTZ,
  closed_by            VARCHAR(160),
  fee_invoice_id       BIGINT,                             -- a FINALIZED autospotting_fee_invoice (Phase 34)
  created_at           TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ss_period ON savings_statement (provider, cloud_account_id, statement_month);

CREATE TABLE IF NOT EXISTS savings_statement_line (
  statement_id         UUID           NOT NULL,
  line_no              INT            NOT NULL,
  line_kind            VARCHAR(12)    NOT NULL,            -- GROUP_PERIOD | ADJUSTMENT
  group_id             UUID           NOT NULL,
  group_key            VARCHAR(300)   NOT NULL,
  tier                 VARCHAR(20)    NOT NULL,
  billable             BOOLEAN        NOT NULL,
  non_billable_reason  VARCHAR(48),
  instance_hours       NUMERIC(24,6)  NOT NULL,
  counterfactual_cost  NUMERIC(24,10) NOT NULL,
  spot_cost            NUMERIC(24,10) NOT NULL,
  commitment_deduction NUMERIC(24,10) NOT NULL,
  raw_net              NUMERIC(24,10) NOT NULL,            -- shown even when negative (PO-26)
  billable_amount      NUMERIC(19,2)  NOT NULL,            -- rounded once, HALF_UP
  currency             CHAR(3)        NOT NULL,
  detail               JSONB          NOT NULL,
  entry_ids            UUID[]         NOT NULL,
  adjustment_ref_line  VARCHAR(80),                        -- '<statementId>#<lineNo>' for ADJUSTMENT
  PRIMARY KEY (statement_id, line_no)
);

CREATE TABLE IF NOT EXISTS savings_bill_import (           -- one row per bill query, append-only
  id                   UUID PRIMARY KEY,
  provider             VARCHAR(8)   NOT NULL,
  cloud_account_id     BIGINT       NOT NULL,
  source               VARCHAR(24)  NOT NULL,              -- AWS_CUR_RESOURCE | AWS_CUR_AGG | AWS_CE | GCP_DETAILED | GCP_STANDARD
  window_start         TIMESTAMPTZ  NOT NULL,
  window_end           TIMESTAMPTZ  NOT NULL,
  query_sha256         VARCHAR(64)  NOT NULL,
  source_version       VARCHAR(200) NOT NULL,
  rows_sha256          VARCHAR(64)  NOT NULL,
  bytes_scanned        BIGINT,
  imported_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS savings_bill_fact (             -- append-only
  import_id            UUID           NOT NULL,
  seq                  INT            NOT NULL,
  usage_day            DATE           NOT NULL,
  region               VARCHAR(64),
  instance_type        VARCHAR(64),
  resource_id          VARCHAR(400),
  fact_kind            VARCHAR(24)    NOT NULL,            -- SPOT_COST | SPOT_USAGE_HOURS | PUBLIC_OD_COST | NET_COST | SUD_CREDIT | OD_USAGE_HOURLY | CUD_CREDIT | CUD_COMMITTED_QTY | CUD_COVERED_QTY | COMMIT_FEE | COMMIT_UNUSED | SP_RATE
  amount               NUMERIC(24,10) NOT NULL,
  unit                 VARCHAR(16)    NOT NULL,            -- USD | NU_HOURS | VCPU_HOURS | GIB_HOURS | RATE
  currency             CHAR(3)        NOT NULL,
  detail               JSONB,
  PRIMARY KEY (import_id, seq)
);

CREATE TABLE IF NOT EXISTS savings_statement_event (       -- status history, append-only
  id                   BIGSERIAL PRIMARY KEY,
  statement_id         UUID         NOT NULL,
  from_status          VARCHAR(12),
  to_status            VARCHAR(12)  NOT NULL,
  actor                VARCHAR(160) NOT NULL,
  reason               VARCHAR(500),
  fee_invoice_id       BIGINT,
  occurred_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS savings_adjustment_request (
  id                   UUID PRIMARY KEY,
  kind                 VARCHAR(24)    NOT NULL,            -- BILL_LATE | DISPUTE_ACCEPTED | OPERATOR_CORRECTION | CALC_FIX
  original_entry_id    UUID,
  original_line        VARCHAR(80),
  amount               NUMERIC(24,10) NOT NULL,
  currency             CHAR(3)        NOT NULL,
  reason               VARCHAR(500)   NOT NULL,
  evidence             JSONB,
  requested_by         VARCHAR(160)   NOT NULL,
  requested_at         TIMESTAMPTZ    NOT NULL,
  decision             VARCHAR(12)    NOT NULL DEFAULT 'PENDING',   -- PENDING | APPROVED | REJECTED
  decided_by           VARCHAR(160),
  decided_at           TIMESTAMPTZ,
  posted_entry_id      UUID,
  dispute_id           UUID
);

CREATE TABLE IF NOT EXISTS savings_dispute (
  id                      UUID PRIMARY KEY,
  statement_id            UUID         NOT NULL,
  line_no                 INT          NOT NULL,
  raised_by_user_id       BIGINT       NOT NULL,
  reason                  VARCHAR(500) NOT NULL,
  raised_at               TIMESTAMPTZ  NOT NULL,
  status                  VARCHAR(24)  NOT NULL DEFAULT 'HELD',   -- HELD | RESOLVED_ACCEPTED | RESOLVED_REJECTED
  resolved_by             VARCHAR(160),
  resolved_at             TIMESTAMPTZ,
  resolution_note         VARCHAR(500),
  adjustment_request_id   UUID
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sd_open_line ON savings_dispute (statement_id, line_no) WHERE status = 'HELD';

CREATE TABLE IF NOT EXISTS savings_ledger_golive (         -- FR-LED-21; immutable once set
  provider             VARCHAR(8) PRIMARY KEY,
  go_live_at           TIMESTAMPTZ  NOT NULL,
  set_by               VARCHAR(160) NOT NULL,
  set_at               TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS savings_billing_hold (
  id                   UUID PRIMARY KEY,
  provider             VARCHAR(8)   NOT NULL,
  cloud_account_id     BIGINT       NOT NULL,
  statement_month      DATE         NOT NULL,
  reason               VARCHAR(48)  NOT NULL,              -- VARIANCE_BAND | RATE_MISMATCH | ...
  detail               JSONB,
  raised_at            TIMESTAMPTZ  NOT NULL,
  resolution           VARCHAR(500),
  resolved_by          VARCHAR(160),
  resolved_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_sbh_account_month ON savings_billing_hold (cloud_account_id, statement_month);

CREATE TABLE IF NOT EXISTS autospotting_contract (         -- PO-01, PO-06, PO-16; maintained by BillOps admins
  id                   UUID PRIMARY KEY,
  client_id            BIGINT        NOT NULL,
  share_pct            NUMERIC(9,6)  NOT NULL,
  monthly_cap          NUMERIC(19,2),                      -- null = no cap
  cap_currency         CHAR(3)       NOT NULL DEFAULT 'USD',
  declared_discount_pct NUMERIC(9,6),                      -- null = not declared, 0 = declared none (ledger §5.1)
  effective_from       TIMESTAMPTZ   NOT NULL,
  effective_to         TIMESTAMPTZ,
  created_by           VARCHAR(160)  NOT NULL,
  created_at           TIMESTAMPTZ   NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_asc_client ON autospotting_contract (client_id, effective_from);

CREATE TABLE IF NOT EXISTS client_shadow_clearance (       -- PD-05, PO-17; append-only, one row per (client, provider)
  client_id            BIGINT       NOT NULL,
  provider             VARCHAR(8)   NOT NULL,
  cleared_month        DATE         NOT NULL,
  evidence             JSONB        NOT NULL,
  cleared_at           TIMESTAMPTZ  NOT NULL,
  cleared_by           VARCHAR(160) NOT NULL,
  PRIMARY KEY (client_id, provider)
);

-- ── defaults that a ddl-auto table may lack ───────────────────────────────────
ALTER TABLE savings_ledger_entry       ALTER COLUMN unknown_inputs   SET DEFAULT '{}';
ALTER TABLE savings_ledger_entry       ALTER COLUMN bill_import_ids  SET DEFAULT '{}';
ALTER TABLE savings_statement          ALTER COLUMN currency         SET DEFAULT 'USD';
ALTER TABLE savings_statement          ALTER COLUMN currency         SET NOT NULL;
ALTER TABLE savings_statement          ALTER COLUMN version          SET DEFAULT 1;
ALTER TABLE savings_adjustment_request ALTER COLUMN decision         SET DEFAULT 'PENDING';
ALTER TABLE savings_dispute            ALTER COLUMN status           SET DEFAULT 'HELD';
ALTER TABLE autospotting_contract      ALTER COLUMN cap_currency     SET DEFAULT 'USD';
ALTER TABLE autospotting_contract      ALTER COLUMN cap_currency     SET NOT NULL;
ALTER TABLE spot_usage_interval        ALTER COLUMN provider_interrupted SET DEFAULT false;
ALTER TABLE spot_usage_interval        ALTER COLUMN closed           SET DEFAULT false;

-- ── named CHECK constraints, each guarded by pg_constraint ───────────────────
DO $$
DECLARE
  c RECORD;
BEGIN
  FOR c IN
    SELECT * FROM (VALUES
      ('savings_ledger_entry', 'ck_sle_type',
       '(entry_type = ''ACCRUAL'' AND run_id IS NOT NULL AND adjustment_ref IS NULL) OR (entry_type = ''ADJUSTMENT'' AND run_id IS NULL AND adjustment_ref IS NOT NULL)'),
      ('savings_ledger_entry', 'ck_sle_tier',
       'NOT billable OR tier IN (''BILL_VERIFIED'',''AGGREGATE_VERIFIED'')'),
      ('savings_ledger_entry', 'ck_sle_unknown',
       'tier <> ''UNKNOWN'' OR cardinality(unknown_inputs) > 0'),
      ('savings_ledger_entry', 'ck_sle_reason',
       'billable OR non_billable_reason IS NOT NULL'),
      ('savings_ledger_entry', 'ck_sle_math',
       'entry_type = ''ADJUSTMENT'' OR (gross_savings = counterfactual_cost - spot_cost AND net_savings = gross_savings - commitment_deduction AND commitment_deduction >= 0 AND spot_cost >= 0 AND counterfactual_cost = counterfactual_list_cost - effective_rate_adjustment)'),
      ('savings_statement', 'ck_ss_usd',
       'currency = ''USD'''),
      ('savings_statement_line', 'ck_ssl_nonneg',
       'billable_amount >= 0 OR line_kind = ''ADJUSTMENT'''),
      ('savings_statement_line', 'ck_ssl_usd',
       'currency = ''USD'''),
      ('autospotting_contract', 'ck_asc_share',
       'share_pct > 0 AND share_pct <= 100'),
      ('autospotting_contract', 'ck_asc_cap',
       'monthly_cap IS NULL OR monthly_cap > 0'),
      ('autospotting_contract', 'ck_asc_capcur',
       'cap_currency = ''USD'''),
      ('autospotting_contract', 'ck_asc_discount',
       'declared_discount_pct IS NULL OR (declared_discount_pct >= 0 AND declared_discount_pct < 100)'),
      ('autospotting_contract', 'ck_asc_range',
       'effective_to IS NULL OR effective_to > effective_from')
    ) AS t(tbl, name, expr)
  LOOP
    IF to_regclass(c.tbl) IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conname = c.name AND conrelid = to_regclass(c.tbl)) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)', c.tbl, c.name, c.expr);
    END IF;
  END LOOP;
END $$;

-- ── 2.4 immutability (FR-LED-13, AC-LED-05) ───────────────────────────────────
-- Ten tables are append-only: any UPDATE or DELETE raises P0001 'ledger rows are append-only'.
CREATE OR REPLACE FUNCTION autospotting_ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger rows are append-only (%, %)', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
  t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY['spot_price_snapshot', 'savings_bill_import', 'savings_bill_fact',
                           'savings_ledger_run', 'savings_ledger_entry', 'savings_ledger_entry_item',
                           'savings_statement_line', 'savings_statement_event',
                           'savings_ledger_golive', 'client_shadow_clearance']
  LOOP
    IF to_regclass(t) IS NOT NULL THEN
      EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_append_only ON %I', t, t);
      EXECUTE format('CREATE TRIGGER trg_%s_append_only BEFORE UPDATE OR DELETE ON %I '
                     'FOR EACH ROW EXECUTE FUNCTION autospotting_ledger_reject_mutation()', t, t);
    END IF;
  END LOOP;
END $$;

-- savings_statement: no DELETE; UPDATE only along OPEN -> CLOSED -> INVOICED -> CLOSED (§8.2, §2.4).
--   OPEN:     the close columns and status (to OPEN or CLOSED).
--   CLOSED:   status (to CLOSED or INVOICED) and fee_invoice_id, which is set only on CLOSED -> INVOICED.
--   INVOICED: status (to INVOICED or CLOSED) and fee_invoice_id: nulled on INVOICED -> CLOSED, or
--             repointed (not null) while it stays INVOICED (§10.4).
-- Identity columns (id, provider, account, client, month, created_at) never change. No path leads
-- back to OPEN.
CREATE OR REPLACE FUNCTION autospotting_statement_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'ledger rows are append-only (%, %)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['status', 'fee_invoice_id', 'version', 'calc_version', 'run_ids',
                            'invoiceable', 'invoiceable_reason', 'share_pct', 'contract_id',
                            'total_billable', 'total_raw', 'content_sha256', 'annex', 'closed_at',
                            'closed_by'])
     IS DISTINCT FROM
     (to_jsonb(OLD) - ARRAY['status', 'fee_invoice_id', 'version', 'calc_version', 'run_ids',
                            'invoiceable', 'invoiceable_reason', 'share_pct', 'contract_id',
                            'total_billable', 'total_raw', 'content_sha256', 'annex', 'closed_at',
                            'closed_by']) THEN
    RAISE EXCEPTION 'ledger rows are append-only: statement identity and currency are fixed'
      USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'OPEN' THEN
    IF NEW.status NOT IN ('OPEN', 'CLOSED') OR NEW.fee_invoice_id IS NOT NULL THEN
      RAISE EXCEPTION 'ledger rows are append-only: illegal statement move % -> %', OLD.status, NEW.status
        USING ERRCODE = 'P0001';
    END IF;
  ELSIF OLD.status = 'CLOSED' THEN
    IF NEW.status NOT IN ('CLOSED', 'INVOICED') THEN
      RAISE EXCEPTION 'ledger rows are append-only: illegal statement move % -> %', OLD.status, NEW.status
        USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'fee_invoice_id']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'fee_invoice_id']) THEN
      RAISE EXCEPTION 'ledger rows are append-only: a closed statement keeps its content'
        USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'INVOICED' AND NEW.fee_invoice_id IS NULL THEN
      RAISE EXCEPTION 'ledger rows are append-only: INVOICED needs a fee invoice' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'CLOSED' AND NEW.fee_invoice_id IS DISTINCT FROM OLD.fee_invoice_id THEN
      RAISE EXCEPTION 'ledger rows are append-only: fee_invoice_id moves only with INVOICED' USING ERRCODE = 'P0001';
    END IF;
  ELSIF OLD.status = 'INVOICED' THEN
    IF NEW.status NOT IN ('INVOICED', 'CLOSED') THEN
      RAISE EXCEPTION 'ledger rows are append-only: illegal statement move % -> %', OLD.status, NEW.status
        USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'fee_invoice_id']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'fee_invoice_id']) THEN
      RAISE EXCEPTION 'ledger rows are append-only: an invoiced statement keeps its content'
        USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'CLOSED' AND NEW.fee_invoice_id IS NOT NULL THEN
      RAISE EXCEPTION 'ledger rows are append-only: a voided statement drops its fee invoice'
        USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'INVOICED' AND NEW.fee_invoice_id IS NULL THEN
      RAISE EXCEPTION 'ledger rows are append-only: INVOICED needs a fee invoice' USING ERRCODE = 'P0001';
    END IF;
  ELSE
    RAISE EXCEPTION 'ledger rows are append-only: unknown statement status %', OLD.status
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- savings_adjustment_request: no DELETE; only the decision columns, PENDING -> APPROVED | REJECTED,
-- and posted_entry_id once, on an APPROVED request (§8.5).
CREATE OR REPLACE FUNCTION autospotting_adjustment_request_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'ledger rows are append-only (%, %)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['decision', 'decided_by', 'decided_at', 'posted_entry_id'])
     IS DISTINCT FROM
     (to_jsonb(OLD) - ARRAY['decision', 'decided_by', 'decided_at', 'posted_entry_id']) THEN
    RAISE EXCEPTION 'ledger rows are append-only: only the decision columns can change'
      USING ERRCODE = 'P0001';
  END IF;
  IF OLD.decision = 'PENDING' THEN
    IF NEW.decision NOT IN ('PENDING', 'APPROVED', 'REJECTED') OR NEW.posted_entry_id IS NOT NULL THEN
      RAISE EXCEPTION 'ledger rows are append-only: illegal decision move % -> %', OLD.decision, NEW.decision
        USING ERRCODE = 'P0001';
    END IF;
  ELSIF OLD.decision = 'APPROVED' THEN
    IF NEW.decision <> 'APPROVED' OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
       OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
       OR (OLD.posted_entry_id IS NOT NULL AND NEW.posted_entry_id IS DISTINCT FROM OLD.posted_entry_id) THEN
      RAISE EXCEPTION 'ledger rows are append-only: an approved request keeps its decision'
        USING ERRCODE = 'P0001';
    END IF;
  ELSE
    RAISE EXCEPTION 'ledger rows are append-only: a % request is final', OLD.decision USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- savings_dispute: no DELETE; only the resolution columns, HELD -> RESOLVED_ACCEPTED | RESOLVED_REJECTED (§11.3).
CREATE OR REPLACE FUNCTION autospotting_dispute_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'ledger rows are append-only (%, %)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['status', 'resolved_by', 'resolved_at', 'resolution_note', 'adjustment_request_id'])
     IS DISTINCT FROM
     (to_jsonb(OLD) - ARRAY['status', 'resolved_by', 'resolved_at', 'resolution_note', 'adjustment_request_id']) THEN
    RAISE EXCEPTION 'ledger rows are append-only: only the resolution columns can change'
      USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'HELD' THEN
    IF NEW.status NOT IN ('HELD', 'RESOLVED_ACCEPTED', 'RESOLVED_REJECTED') THEN
      RAISE EXCEPTION 'ledger rows are append-only: illegal dispute move % -> %', OLD.status, NEW.status
        USING ERRCODE = 'P0001';
    END IF;
  ELSIF NEW.status IS DISTINCT FROM OLD.status OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
        OR NEW.resolution_note IS DISTINCT FROM OLD.resolution_note
        OR NEW.adjustment_request_id IS DISTINCT FROM OLD.adjustment_request_id THEN
    RAISE EXCEPTION 'ledger rows are append-only: a resolved dispute is final' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- savings_billing_hold: no DELETE; only the resolution columns, once (§7.4).
CREATE OR REPLACE FUNCTION autospotting_billing_hold_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'ledger rows are append-only (%, %)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['resolution', 'resolved_by', 'resolved_at'])
     IS DISTINCT FROM
     (to_jsonb(OLD) - ARRAY['resolution', 'resolved_by', 'resolved_at']) THEN
    RAISE EXCEPTION 'ledger rows are append-only: only the resolution columns can change'
      USING ERRCODE = 'P0001';
  END IF;
  IF OLD.resolution IS NOT NULL
     AND (NEW.resolution IS DISTINCT FROM OLD.resolution OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
          OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at) THEN
    RAISE EXCEPTION 'ledger rows are append-only: a resolved hold is final' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
  g RECORD;
BEGIN
  FOR g IN
    SELECT * FROM (VALUES
      ('savings_statement',          'autospotting_statement_guard'),
      ('savings_adjustment_request', 'autospotting_adjustment_request_guard'),
      ('savings_dispute',            'autospotting_dispute_guard'),
      ('savings_billing_hold',       'autospotting_billing_hold_guard')
    ) AS t(tbl, fn)
  LOOP
    IF to_regclass(g.tbl) IS NOT NULL AND to_regprocedure(g.fn || '()') IS NOT NULL THEN
      EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_guard ON %I', g.tbl, g.tbl);
      EXECUTE format('CREATE TRIGGER trg_%s_guard BEFORE UPDATE OR DELETE ON %I '
                     'FOR EACH ROW EXECUTE FUNCTION %I()', g.tbl, g.tbl, g.fn);
    END IF;
  END LOOP;
END $$;

-- ── autospotting_contract: overlap, no-backdate, immutable terms (§2.3, PO-01) ─
-- BEFORE INSERT OR UPDATE takes pg_advisory_xact_lock(client_id), so two concurrent inserts for one
-- client serialize and the second sees the first. Contracts of one client never overlap on
-- [effective_from, effective_to) (an open end is unbounded). A new or changed date earlier than the
-- time of the insert or update is rejected, so a record never reaches a past period. After insert only
-- effective_to may change, and only to end the contract earlier at a future instant: a change of terms
-- is a new record from a later month start. A contract is never deleted: it is history.
CREATE OR REPLACE FUNCTION autospotting_contract_guard() RETURNS trigger AS $$
DECLARE
  now_ts  TIMESTAMPTZ := now();
  clash   UUID;
BEGIN
  PERFORM pg_advisory_xact_lock(NEW.client_id);
  IF TG_OP = 'INSERT' THEN
    IF NEW.effective_from < now_ts OR (NEW.effective_to IS NOT NULL AND NEW.effective_to < now_ts) THEN
      RAISE EXCEPTION 'contract dates cannot be backdated' USING ERRCODE = 'P0001';
    END IF;
  ELSE
    IF (to_jsonb(NEW) - 'effective_to') IS DISTINCT FROM (to_jsonb(OLD) - 'effective_to') THEN
      RAISE EXCEPTION 'contract terms are immutable; end the contract and create a new one'
        USING ERRCODE = 'P0001';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
      IF NEW.effective_to IS NULL OR NEW.effective_to < now_ts THEN
        RAISE EXCEPTION 'contract dates cannot be backdated' USING ERRCODE = 'P0001';
      END IF;
      IF OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to THEN
        RAISE EXCEPTION 'a contract can only be ended earlier, never extended' USING ERRCODE = 'P0001';
      END IF;
    END IF;
  END IF;
  SELECT c.id INTO clash
    FROM autospotting_contract c
   WHERE c.client_id = NEW.client_id
     AND c.id <> NEW.id
     AND tstzrange(c.effective_from, c.effective_to, '[)') && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
   LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'contract overlaps another contract of the client' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION autospotting_contract_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'contract records are never deleted; end the contract instead' USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
  IF to_regclass('autospotting_contract') IS NOT NULL
     AND to_regprocedure('autospotting_contract_guard()') IS NOT NULL
     AND to_regprocedure('autospotting_contract_no_delete()') IS NOT NULL THEN
    EXECUTE 'DROP TRIGGER IF EXISTS trg_autospotting_contract_guard ON autospotting_contract';
    EXECUTE 'CREATE TRIGGER trg_autospotting_contract_guard BEFORE INSERT OR UPDATE ON autospotting_contract '
            'FOR EACH ROW EXECUTE FUNCTION autospotting_contract_guard()';
    EXECUTE 'DROP TRIGGER IF EXISTS trg_autospotting_contract_no_delete ON autospotting_contract';
    EXECUTE 'CREATE TRIGGER trg_autospotting_contract_no_delete BEFORE DELETE ON autospotting_contract '
            'FOR EACH ROW EXECUTE FUNCTION autospotting_contract_no_delete()';
  END IF;
END $$;
