/**
 * Types for the XamLens Datastores page and the cloud / on-prem datastore drawer.
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters.datastores` field for field. Every record is
 * serialised with `JsonInclude.ALWAYS`, so a Java null arrives as `null`, never as a missing key. Java
 * `String`, `Long`, `Double` and `Integer` fields are nullable here unless the mapper always sets them;
 * `long`, `int` and `boolean` never are.
 *
 * Counts and times stay numeric on purpose: formatting (abbreviations, relative times, sizes) is done by
 * the frontend in `components/Dspm/datastores/datastoreFormat.ts`, so it can follow the viewer's clock and
 * time zone.
 */

import type { DspmTrendPoint } from './dspm';

/** Vendor category of a row. Treat the set as open. */
export type DatastoreCategory = 'cloud' | 'on_prem' | 'saas' | 'endpoint' | (string & {});

// ─── 1. List rows (`GET /api/dspm/datastores`, wrapped in `DspmPage<DatastoreRow>`) ──────────────

export interface DatastoreRowRisk {
  /** Raw score; formatted to one decimal by `formatRiskScore`. Null on rows without a score (SaaS, Endpoint). */
  score: number | null;
  /** `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, or null when the row has no risk level. Upper-cased, not checked against a set. */
  level: string | null;
  levelLabel: string | null;
}

export interface DatastoreRowSensitives {
  recordCount: number;
  /** Console wording of the sensitive types, in API order ("Personal", "IT & Security"). */
  typeLabels: string[];
}

export interface DatastoreRowSource {
  provider: string | null;
  providerLabel: string | null;
  /** Resolved account label. Null renders as "-". */
  accountLabel: string | null;
  region: string | null;
}

export interface DatastoreRowScan {
  status: string | null;
  statusLabel: string | null;
  /** Epoch **seconds** the Scan Status cell shows, chosen by the backend per status. */
  displayAt: number | null;
}

export interface DatastoreRowAgent {
  serialNumber: string | null;
  status: string | null;
  statusLabel: string | null;
  version: string | null;
}

export interface DatastoreRow {
  /** Vendor uuid, used for `/api/dspm/assets/{id}` (SaaS and Endpoint drawers). */
  id: string | null;
  /** 24-hex datastore id, used by the cloud / on-prem drawer. */
  sourceId: string | null;
  name: string | null;
  category: DatastoreCategory | null;
  subtype: string | null;
  subtypeLabel: string | null;
  risk: DatastoreRowRisk;
  sensitives: DatastoreRowSensitives;
  source: DatastoreRowSource;
  scan: DatastoreRowScan;
  /** Null unless `category` is `endpoint`. */
  agent: DatastoreRowAgent | null;
}

// ─── 2. Filter options (`GET /api/dspm/datastores/filter-options`) ───────────────────────────────

export interface DatastoreFilterOption {
  /** Sent back verbatim as the filter value. `""` for a blank vendor value. */
  value: string;
  label: string;
  /** Null when the vendor sends no count (`hostname`, `user`). */
  count: number | null;
}

export interface DatastoreFilterDimension {
  key: string;
  options: DatastoreFilterOption[];
  hasMore: boolean;
}

export interface DatastoreFilterOptions {
  /** Vendor order. Counts are disjunctive: a dimension's own selection never shrinks its own options. */
  dimensions: DatastoreFilterDimension[];
}

// ─── 3. KPIs (`GET /api/dspm/datastores/kpis`) ───────────────────────────────────────────────────

export interface DatastoreKpiMetric {
  value: number;
  /** Daily readings, oldest first. Empty means "no history". */
  trend: DspmTrendPoint[];
}

export interface DatastoreKpis {
  /** The tab these values belong to: `all`, `cloud`, `saas`, `endpoint` or `on_prem`. */
  tab: string;
  /** Asset count per tab, keyed `all`, `cloud`, `saas`, `endpoint`, `on_prem`. */
  tabCounts: Record<string, number>;
  totalSensitiveRecords: DatastoreKpiMetric;
  assetCount: DatastoreKpiMetric;
  classified: number;
  scheduled: number;
  failed: number;
  stopped: number;
  criticalHigh: number;
}

// ─── 4. Overview (`/api/dspm/datastores/{sourceId}/overview`) ────────────────────────────────────

export interface DatastoreFileType {
  key: string;
  label: string;
  count: number;
}

export interface DatastoreFileSummary {
  totalFiles: number | null;
  /** Display order. */
  types: DatastoreFileType[];
}

export interface DatastoreEntityCount {
  name: string;
  count: number;
}

export interface DatastoreLabelGroup {
  code: string;
  label: string | null;
  entities: DatastoreEntityCount[];
}

export interface DatastoreSensitiveRecords {
  total: number | null;
  /** API order. */
  groups: DatastoreLabelGroup[];
}

export interface DatastoreProperty {
  key: string;
  label: string;
  text: string | null;
  /** Set only for `datastore_size`. */
  bytes: number | null;
}

export interface DatastoreSecurityConfiguration {
  key: string;
  label: string;
  /** Prettified: "Enabled", "Disabled", "Sse S3". */
  value: string;
}

export interface DatastoreOverview {
  /** Validated console link; render only when `consoleLinkLabel` accepts it. */
  consoleUrl: string | null;
  scanningStatus: string | null;
  scanningStatusLabel: string | null;
  canDiscoverSchema: boolean;
  /** Null when the vendor sends no file types. */
  fileSummary: DatastoreFileSummary | null;
  sensitiveRecords: DatastoreSensitiveRecords;
  /** Region, datastore size, then API order. */
  properties: DatastoreProperty[];
  /** Null when the vendor sends none. */
  securityConfigurations: DatastoreSecurityConfiguration[] | null;
}

// ─── 5. Scan progress (`/{sourceId}/scan-status`) ────────────────────────────────────────────────

export interface DatastoreScanProgress {
  /** Null unless the vendor's `scan_progress` is numeric. */
  progress: number | null;
}

// ─── 6. Sensitives (`/{sourceId}/sensitives`) ────────────────────────────────────────────────────

export interface DatastoreSensitiveEntity {
  name: string;
  count: number;
  categoryLabel: string | null;
  /** True for the vendor's blank entity, which cannot be expanded. */
  blank: boolean;
}

export interface DatastoreColumnFinding {
  columnName: string | null;
  entityType: string | null;
  labels: string[];
  count: number;
  falsePositive: boolean;
}

export interface DatastoreSensitiveTable {
  name: string | null;
  schemaName: string | null;
  count: number;
  labels: string[];
  expandable: boolean;
  findings: DatastoreColumnFinding[];
}

export interface DatastoreSensitiveDatabase {
  id: string | null;
  name: string | null;
  /** Count descending, stable. */
  tables: DatastoreSensitiveTable[];
}

export interface DatastoreSensitives {
  database: boolean;
  entities: DatastoreSensitiveEntity[];
  databases: DatastoreSensitiveDatabase[];
}

// ─── 7. File View (`/{sourceId}/sensitives/files`) ───────────────────────────────────────────────

export interface DatastoreFileEntity {
  /** `""` for the blank entity. */
  name: string;
  label: string | null;
}

export interface DatastoreFileRow {
  path: string;
  name: string;
  count: number;
  labels: string[];
  entities: DatastoreFileEntity[];
}

export interface DatastoreFilePage {
  rows: DatastoreFileRow[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  /** True when the backend capped the file rows it reads. */
  truncated: boolean;
}

// ─── 8. Occurrences (`/{sourceId}/sensitives/occurrences`): never cached, never logged ───────────

export interface DatastoreOccurrence {
  fileName: string | null;
  url: string | null;
  databaseName: string | null;
  schemaName: string | null;
  tableName: string | null;
  columnName: string | null;
  falsePositive: boolean;
  /** Masked by xamops-service (build 9), rendered only through `drawer/SampleValue.tsx`. Never null. */
  samples: string[];
}

export interface DatastoreOccurrencePage {
  entityName: string;
  total: number;
  /** Echo the request. */
  skip: number;
  limit: number;
  rows: DatastoreOccurrence[];
}

// ─── 9. Risks (`/{sourceId}/risks`) ──────────────────────────────────────────────────────────────

export interface DatastoreMisconfiguration {
  title: string | null;
  source: string | null;
  severity: string | null;
  severityLabel: string | null;
  /** Markdown. */
  description: string | null;
  /** Markdown. */
  remediation: string | null;
  cli: string | null;
}

export interface DatastoreRisks {
  items: DatastoreMisconfiguration[];
}

// ─── 10. Access graph (`/{sourceId}/access-graph`) ───────────────────────────────────────────────

export interface DatastoreAccessGroup {
  id: string;
  permissions: string[];
  /** `ROLE`, `USER` or `GROUP`. */
  principalType: string | null;
  actionOnly: boolean;
  count: number;
  /** Sorted case-insensitively. */
  principals: string[];
}

export interface DatastoreAccessGraph {
  rootName: string | null;
  rootType: string | null;
  principalCount: number;
  /** Display order. */
  groups: DatastoreAccessGroup[];
}

// ─── 11. Scan history (`/{sourceId}/scan-history`) ───────────────────────────────────────────────

export interface DatastoreScanRun {
  executionNumber: number | null;
  /** Epoch seconds. */
  startTime: number | null;
  sensitiveRecords: number | null;
  sensitivityLevel: string | null;
  severityLabel: string | null;
  triggeredBy: string | null;
  status: string | null;
  statusLabel: string | null;
}

export interface DatastoreScanHistory {
  /** Newest first, as returned. */
  runs: DatastoreScanRun[];
}

// ─── 12-16. Data Flow ────────────────────────────────────────────────────────────────────────────

export interface DataFlowDatabase {
  id: string | null;
  name: string | null;
  engine: string | null;
  schemaless: boolean;
}

export interface DataFlowDatabases {
  items: DataFlowDatabase[];
  truncated: boolean;
}

export interface DataFlowSchema {
  id: string | null;
  name: string | null;
}

export interface DataFlowSchemas {
  items: DataFlowSchema[];
  truncated: boolean;
}

export interface DataFlowTable {
  id: string | null;
  name: string | null;
  rowCountEstimate: number | null;
  foreignKeyCount: number | null;
  sensitiveCount: number | null;
}

export interface DataFlowTablePage {
  rows: DataFlowTable[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  /** True when a search stopped at the backend's table cap. */
  searchCapped: boolean;
}

export interface DataFlowColumn {
  id: string | null;
  name: string | null;
  nativeType: string | null;
  sensitiveCount: number | null;
  dataElements: string[];
  /** The diagram's orange "PK" badge. */
  primaryKey: boolean;
  /** The diagram's blue "FK" badge: the column is part of a foreign key. */
  foreignKey: boolean;
}

export interface DataFlowColumnPage {
  rows: DataFlowColumn[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}
