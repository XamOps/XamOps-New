/**
 * Types for the Matters.ai DSPM add-on.
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters` field for field. Kept as one file so a
 * backend record change has one obvious place to land — the XamSecure equivalent grew to 997 lines
 * spread across a service that returns untyped maps, which means the only description of that contract
 * is the frontend's guess at it. These shapes are declared on both sides.
 */

// ─── Asset list and detail ───────────────────────────────────────────────────

/**
 * Severity band. `NONE` is not a null placeholder we invented — the vendor's filter dimension reports
 * it as a literal string for assets with no evaluated policy, while the corresponding list rows return
 * null. The backend folds null to `NONE` so a row's value matches the value the filter sends back.
 */
export type DspmSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

/**
 * Scan status. `NOT_SCANNED` is absent from the published documentation but is the single most common
 * value in practice, and the documented `SCHEDULED` never appears. Treat the set as open.
 */
export type DspmScanStatus =
  | 'NOT_SCANNED' | 'SCANNING' | 'CLASSIFIED' | 'FAILED' | 'STOPPED'
  | 'SCHEDULED' | 'ACTIVE' | 'SUSPENDED' | (string & {});

export interface DspmRisk {
  /** Null for most assets — only ~10% of live rows carry a score. */
  score: number | null;
  level: DspmSeverity;
  /**
   * Failing policy checks. Independent of `score` and `level`: a live asset carries 35 violations with
   * both of those null, so one cannot be inferred from the other.
   */
  policyViolationCount: number | null;
  /** True when `level` is a real band rather than `NONE`. */
  hasFinding: boolean;
}

export interface DspmSensitives {
  /** Meaningless unless `known` is true. See below. */
  recordCount: number;
  /** **Total** objects under the asset, not the sensitive subset. Label this "Objects" in the UI. */
  objectCount: number;
  /** Raw category codes, e.g. `["PII","SECRETS"]`. */
  types: string[];
  /** Console wording for the same, e.g. `["Personal","IT & Security"]`. Render these. */
  typeLabels: string[];
  level: string | null;
  /**
   * Whether `recordCount` can be trusted — true only once the scan reached `CLASSIFIED`.
   *
   * This is the most important flag in the file. In the live project 71% of rows report
   * `recordCount: 0` while being unscanned, failed or stopped; only 11% are genuinely scanned and
   * clean. Rendering the raw zero would tell a customer their data is clean when nobody has looked.
   * Computed server-side so no component re-derives it.
   */
  known: boolean;
}

export interface DspmSource {
  provider: string | null;
  providerLabel: string | null;
  /**
   * The Matters *integration* id, not a cloud account number. The vendor console resolves this to a
   * friendly name; the API does not expose that mapping, so the id is all we can show.
   */
  account: string | null;
  /** Cloud region, or `self-hosted`. Null for roughly half of live rows. */
  region: string | null;
}

export interface DspmScan {
  status: DspmScanStatus;
  /** Console wording, e.g. "Scan stopped". Render this rather than `status`. */
  statusLabel: string;
  stage: string | null;
  /** Unix epoch **seconds**, not milliseconds. Multiply by 1000 before `new Date()`. */
  lastScannedAt: number | null;
}

/**
 * Detail-only fields attached to list rows where the table needs them (SaaS today). The SaaS Assets,
 * Total Files and Total Size columns are the account email and `facet.drive_type`,
 * `facet.total_inventories` and `facet.total_size_bytes`, none of which the list returns.
 */
export interface DspmRowProfile {
  externalId: string | null;
  facet: Record<string, unknown>;
}

export interface DspmAssetRow {
  /** Null for surfaces that do not need it, and for a row whose lookup failed. */
  profile?: DspmRowProfile | null;
  id: string;
  name: string;
  subtype: string | null;
  subtypeLabel: string | null;
  category: 'cloud' | 'on_prem' | 'saas' | 'endpoint' | (string & {});
  resourceType: string | null;
  /** External identifier — ARN, bucket name, email, serial. Present on the list, so no extra fetch. */
  sourceId: string | null;
  lifecycle: string | null;
  risk: DspmRisk;
  sensitives: DspmSensitives;
  source: DspmSource;
  scan: DspmScan;
}

export interface DspmFinding {
  entityType: string;
  category: string | null;
  categoryLabel: string | null;
  count: number;
}

export interface DspmObject {
  id: string;
  name: string | null;
  objectType: string | null;
  source: string | null;
  path: string | null;
  extension: string | null;
  sizeBytes: number | null;
  sensitive: boolean;
  sensitiveCount: number;
  findings: DspmFinding[];
}

export interface DspmEntityCount {
  entityType: string;
  category: string | null;
  categoryLabel: string | null;
  /** Instances across the returned objects. A lower bound when the detail's `breakdownPartial` is set. */
  count: number;
  /** Returned objects containing the entity. Same lower-bound caveat as `count`. */
  fileCount: number;
  /**
   * False for an entity the asset's facet names but none of the (capped) returned objects contain:
   * detected, but not counted. Render its count as unavailable, never as zero.
   */
  sampled: boolean;
}

export interface DspmAssetDetail {
  id: string;
  name: string;
  subtype: string | null;
  subtypeLabel: string | null;
  category: string;
  resourceType: string | null;
  externalId: string | null;
  lifecycle: string | null;
  risk: DspmRisk;
  sensitives: DspmSensitives;
  source: DspmSource;
  scan: DspmScan;
  /**
   * Type-specific attributes, untyped on purpose: keys differ per asset type and empty ones are
   * omitted upstream, so the live shapes range from 2 keys to 16 and the vendor adds more over time.
   * Render whatever is present.
   */
  facet: Record<string, unknown>;
  /** Capped at 50 upstream, with no object-level pagination available. */
  objects: DspmObject[];
  objectTotal: number;
  /** True when `objects.length < objectTotal`. Say so in the UI — 50 of 5,211 is not "the contents". */
  objectsTruncated: boolean;
  entityBreakdown: DspmEntityCount[];
  /** True when the breakdown was computed from a capped object list, so counts are lower bounds. */
  breakdownPartial: boolean;
}

// ─── Paging, filters, summary ────────────────────────────────────────────────

/** Note `page` is **1-based**, matching the vendor rather than Spring Data. */
export interface DspmPage<T> {
  data: T[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

export interface DspmFilterOption {
  /** Send this back verbatim as the filter value. May be the `NONE` severity sentinel. */
  value: string;
  label: string;
  count: number;
}

export interface DspmFilters {
  /**
   * Dimension → options, ordered by count descending. Nine dimensions come back live, three of them
   * undocumented: `data_element` (the specific detector, and the most useful one here), `lifecycle`,
   * and `paging` which the backend lifts out into `hasMore`.
   *
   * Counts are **disjunctive**: each dimension ignores its own selection, so the rail stays populated
   * after a user filters on it. Call `/cloud/filters` with the same params as the list to preserve that.
   */
  dimensions: Record<string, DspmFilterOption[]>;
  /** Dimension → whether its option list was truncated upstream. Surface it; do not hide it. */
  hasMore: Record<string, boolean>;
}

export interface DspmTrendPoint {
  /** `YYYY-MM-DD`. */
  date: string;
  value: number;
}

export interface DspmMetric {
  value: number;
  /** Empty means "no history", not "a history of zeroes". Do not draw a flat line at zero. */
  trend: DspmTrendPoint[];
}

export interface DspmSummaryTab {
  value: number;
  trend: DspmTrendPoint[];
  criticalHigh: number;
  sensitiveRecords: number;
}

export interface DspmSummary {
  totalAssets: DspmMetric;
  criticalHigh: DspmMetric;
  totalSensitiveRecords: DspmMetric;
  /** Scan status → asset count. The basis for the coverage figure that leads the page. */
  byStatus: Record<string, number>;
  /** Category → counts, so the category strip costs no extra requests. */
  tabs: Record<string, DspmSummaryTab>;
}

// ─── Connection status ───────────────────────────────────────────────────────

/**
 * Machine-readable failure code. **Branch on this, never on the message** — the message is prose and
 * will be reworded.
 */
export type DspmReason =
  | 'NOT_ENTITLED' | 'NOT_CONFIGURED' | 'DISABLED'
  | 'KEY_EXPIRED' | 'INVALID_KEY' | 'WRONG_ENVIRONMENT' | 'FORBIDDEN_SCOPE'
  | 'DECRYPT_FAILED' | 'MALFORMED_KEY'
  | 'RATE_LIMITED' | 'ASSET_NOT_FOUND'
  | 'UPSTREAM_UNAVAILABLE' | 'BAD_UPSTREAM_RESPONSE';

export interface DspmStatus {
  ready: boolean;
  /** Whether the org holds the add-on. Distinct from `configured` — entitled but unmapped is normal. */
  entitled: boolean;
  configured: boolean;
  reason: DspmReason | null;
  message: string | null;
  /** Project *name*, never the id. */
  projectName: string | null;
  lastVerifiedAt: string | null;
}

/** Error body from any DSPM endpoint. */
export interface DspmError {
  code: DspmReason;
  message: string;
  retryAfterSeconds?: number;
}

// ─── Admin ──────────────────────────────────────────────────────────────────

/**
 * A client in the mapping picker.
 *
 * Served by a dedicated lightweight endpoint rather than the Accounts screen's client list, which fans
 * out to Cost Explorer plus the GCP and Azure cost APIs per row.
 */
export interface DspmClientOption {
  clientId: number;
  clientName: string | null;
  entitled: boolean;
  /** Already has a mapping, so the picker can offer editing rather than silent overwriting. */
  mapped: boolean;
}

export type DspmExpiryState = 'NONE' | 'OK' | 'WARNING' | 'EXPIRED';

export type DspmConnectionStatus =
  | 'UNVERIFIED' | 'CONNECTED' | 'INVALID_KEY' | 'FORBIDDEN' | 'UNREACHABLE' | 'DECRYPT_FAILED';

/**
 * One mapping as the admin page sees it. Carries **no credential** — only the environment, key id and
 * last four characters, which identify which key is in use without being usable.
 */
export interface DspmMappingAdminView {
  id: number;
  clientId: number;
  clientName: string | null;
  mattersProjectId: string;
  mattersProjectName: string | null;
  mattersAccountId: string | null;
  baseUrl: string;
  apiKeyEnv: string | null;
  apiKeyId: string | null;
  apiKeyLastFour: string | null;
  apiKeyExpiresAt: string | null;
  /** Negative once past. Null when no expiry was recorded. */
  daysUntilExpiry: number | null;
  expiryState: DspmExpiryState;
  /** Operational switch. */
  enabled: boolean;
  /** Commercial grant. Separate from `enabled`, and both must hold for reads to work. */
  entitled: boolean;
  /**
   * Whether a key was ever stored. With `connectionStatus: 'DECRYPT_FAILED'` this is what separates
   * "never configured" from "configured but unreadable".
   */
  credentialStored: boolean;
  connectionStatus: DspmConnectionStatus;
  lastVerifiedAt: string | null;
  lastError: string | null;
  lastErrorAt: string | null;
  updatedBy: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mapping form payload.
 *
 * `apiKey` is write-only. Leave it **blank on update to keep the existing key** — the form never
 * renders a stored key back, so there is nothing to resubmit, and treating blank as "clear" would wipe
 * a working connection every time someone fixed a typo in the project name.
 */
export interface DspmMappingRequest {
  mattersProjectId: string;
  mattersProjectName?: string | null;
  mattersAccountId?: string | null;
  baseUrl: string;
  apiKey?: string | null;
  apiKeyExpiresAt?: string | null;
  enabled?: boolean;
}

export type DspmAuditAction =
  | 'CREDENTIAL_USED'
  | 'MAPPING_CREATED' | 'MAPPING_UPDATED' | 'MAPPING_DELETED' | 'MAPPING_VERIFIED'
  | 'KEY_ROTATED' | 'PROJECT_REPOINTED'
  | 'ENTITLEMENT_GRANTED' | 'ENTITLEMENT_WITHDRAWN';

export interface DspmAuditView {
  id: number;
  clientId: number | null;
  clientName: string | null;
  actorUserId: number | null;
  actorUsername: string | null;
  actorRole: string | null;
  /** Scan this first: a customer reading their own data is routine, an operator doing so is not. */
  actorType: 'CUSTOMER' | 'OPERATOR' | 'SYSTEM' | null;
  impersonated: boolean;
  action: DspmAuditAction | (string & {});
  /** Non-secret key-id segment. The key itself is never recorded. */
  apiKeyId: string | null;
  mattersProjectId: string | null;
  endpoint: string | null;
  outcome: 'SUCCESS' | 'FAILURE';
  failureReason: string | null;
  detail: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  durationMs: number | null;
  createdAt: string;
}

export interface DspmAuditPage {
  data: DspmAuditView[];
  /** 0-based here — this one is a Spring Data page, unlike `DspmPage`. */
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}
