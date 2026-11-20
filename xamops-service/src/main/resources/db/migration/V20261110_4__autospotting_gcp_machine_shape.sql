-- AutoSpotting Phase B (B4 GCP, plan-phase-30.md task 30.1; design-ledger.md §4.2, PD-09) billed shape of each GCP
-- machine type the ledger prices: billed vCPU (the gcp-shared-core-v1 fraction for shared-core types, never guestCpus;
-- NULL when a shared-core type is missing from the table) and memory in GiB. Written by the GCP price-snapshot job,
-- read by the ledger inputs source and copied into every entry's stored inputs, so an entry reproduces from its inputs.
-- One additive, guarded, idempotent migration numbered above V20261110_3. No DROP, no CASCADE, no UPDATE. Rows are never
-- updated: a changed shape gets a new table_version row.
CREATE TABLE IF NOT EXISTS gcp_machine_shape (
  machine_type  VARCHAR(64)   NOT NULL,
  table_version VARCHAR(32)   NOT NULL,
  series        VARCHAR(16)   NOT NULL,
  billed_vcpu   NUMERIC(12,6),
  memory_gib    NUMERIC(12,6) NOT NULL,
  shared_core   BOOLEAN       NOT NULL,
  fetched_at    TIMESTAMPTZ   NOT NULL,
  PRIMARY KEY (machine_type, table_version)
);
