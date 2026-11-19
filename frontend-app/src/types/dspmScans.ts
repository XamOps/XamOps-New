/**
 * Types for XamLens Scan Management and the datastore drawer's scan extras (Scan Settings, sync status,
 * databases).
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters.scans` field for field (design 5.4). A nested
 * Java record is named after its parent here (`ScanSchedule.Timing` is `ScanScheduleTiming`). Every record
 * is serialised with `JsonInclude.ALWAYS`, so a Java null arrives as `null`, never as a missing key. Java
 * `String`, `Long`, `Integer`, `Double` and `Boolean` fields are nullable here unless the mapper always
 * sets them; `long`, `int` and `boolean` never are, and lists and maps are never null. Times are epoch
 * seconds. The backend returns whitelisted fields only: no credential or connector id has a field here.
 * The cache-only `AssetRowSnapshot` has no mirror.
 */
// ─── Shared ─────────────────────────────────────────────────────────────────────────────────────

/** `ScanTarget`: one datastore a schedule or scope covers. */
export interface ScanTarget {
  id: string | null;
  name: string | null;
  subtype: string | null;
  subtypeLabel: string | null;
}

// ─── Schedules (`/api/dspm/scan-management/schedules[/{id}]`) ───────────────────────────────────

/** `ScanSchedule.Timing`. */
export interface ScanScheduleTiming {
  type: string | null;
  triggerTime: number | null;
  recurrenceType: string | null;
  repeatOn: string[];
  dayOfMonth: number | null;
  endDate: number | null;
  timezone: string | null;
}

/** `ScanSchedule`. */
export interface ScanSchedule {
  id: string | null;
  name: string | null;
  platform: string | null;
  platformLabel: string | null;
  coverage: string | null;
  coverageLabel: string | null;
  totalDatastores: number | null;
  totalIsEstimate: boolean;
  state: string | null;
  legacy: boolean;
  timing: ScanScheduleTiming | null;
  lastRunAt: number | null;
  lastRunStatus: string | null;
  lastRunStatusLabel: string | null;
  nextRunAt: number | null;
}

/** `ScanScheduleDetail`. */
export interface ScanScheduleDetail {
  schedule: ScanSchedule;
  scopeMode: string | null;
  scopeNames: string[];
  targets: ScanTarget[];
  targetsTotal: number;
}

// ─── Scan history (`/api/dspm/scan-management/scan-history[/{runId}[/datastores]]`) ─────────────

/** `ScanRun`. */
export interface ScanRun {
  id: string | null;
  name: string | null;
  coverage: string | null;
  coverageLabel: string | null;
  platform: string | null;
  platformLabel: string | null;
  trigger: string | null;
  triggerLabel: string | null;
  scheduledTime: number | null;
  startedAt: number | null;
  endedAt: number | null;
  durationSeconds: number | null;
  status: string | null;
  statusLabel: string | null;
  phase: string | null;
  phaseLabel: string | null;
  statusCounts: Record<string, number>;
  completedDatastores: number;
  totalDatastores: number;
  skippedCount: number;
}

/** `ScanRunDetail.FacetValue`. */
export interface ScanRunFacetValue {
  value: string | null;
  count: number | null;
}

/** `ScanRunDetail`. */
export interface ScanRunDetail {
  run: ScanRun;
  scopeNames: string[];
  skippedExamples: string[];
  accounts: ScanRunFacetValue[];
}

/** `ScanRunDatastore`: one datastore of a run. */
export interface ScanRunDatastore {
  id: string | null;
  name: string | null;
  subtype: string | null;
  subtypeLabel: string | null;
  accountName: string | null;
  region: string | null;
  status: string | null;
  statusLabel: string | null;
  failureReason: string | null;
}

// ─── Scan scopes (`/api/dspm/scan-management/scan-scopes[/{id}]`) ───────────────────────────────

/** `ScanScope`. */
export interface ScanScope {
  id: string | null;
  name: string | null;
  platform: string | null;
  platformLabel: string | null;
  scopeMode: string | null;
  totalDatastores: number | null;
  updatedAt: number | null;
}

/** `ScanScopeDetail`. */
export interface ScanScopeDetail {
  scope: ScanScope;
  targets: ScanTarget[];
  targetsTotal: number;
}

// ─── SaaS scan configuration (`/api/dspm/scan-management/saas-integrations/…`) ──────────────────

/** `SaasIntegrationList.SaasIntegration`. */
export interface SaasIntegration {
  id: string;
  /** GOOGLE_WORKSPACE, SLACK, MICROSOFT_365, JIRA, CONFLUENCE or GITLAB. */
  provider: string;
  accountLabel: string | null;
  driveTypes: string[];
}

/** `SaasIntegrationList`. */
export interface SaasIntegrationList {
  integrations: SaasIntegration[];
  partial: boolean;
}

/** `SaasScanConfiguration.Scope`: one drive type's scan scope. */
export interface SaasScanScope {
  driveType: string | null;
  driveLabel: string | null;
  scopeType: string | null;
  totalActiveUsers: number | null;
  supportedDomains: string[];
}

/** `SaasScanConfiguration`. */
export interface SaasScanConfiguration {
  integrationId: string | null;
  realtimeEnabled: boolean;
  backfillingEnabled: boolean;
  samplingPercentage: number | null;
  provisioningComplete: boolean;
  lastSavedAt: number | null;
  lastSavedBy: string | null;
  scopes: SaasScanScope[];
}

/** `SaasScanUser`. */
export interface SaasScanUser {
  id: string | null;
  email: string | null;
  status: string | null;
  statusLabel: string | null;
  selected: boolean;
}

/** `SaasChannel`: one Slack channel. */
export interface SaasChannel {
  id: string | null;
  name: string | null;
  channelType: string | null;
  channelStatus: string | null;
  memberCount: number | null;
  externalShared: boolean;
  topic: string | null;
  isNew: boolean;
  selected: boolean;
}

// ─── Endpoint configuration (`/api/dspm/scan-management/endpoint-configuration`) ────────────────

/** `EndpointScanConfiguration`. */
export interface EndpointScanConfiguration {
  configured: boolean;
  scanMode: string | null;
  /** `HH:MM` as the vendor sends it. */
  reconciliationStart: string | null;
  reconciliationEnd: string | null;
  includePaths: string[];
  excludePaths: string[];
  fileTypes: string[];
  allFileTypes: string[];
}

// ─── Datastore drawer extras (`/api/dspm/datastores/{sourceId}/…`) ──────────────────────────────

/** `DatastoreScanSettings.Parameters`. */
export interface DatastoreScanParameters {
  numFoldersLeafNode: number | null;
  parallelism: number | null;
  timeOutHours: number | null;
  rowSamplingLimit: number | null;
  samplingPercentageLimit: number | null;
}

/** `DatastoreScanSettings` (`/scan-settings`). Assists are named, never identified. */
export interface DatastoreScanSettings {
  parameters: DatastoreScanParameters | null;
  inclusionPaths: string[];
  exclusionPaths: string[];
  allDatabases: boolean;
  selectedDatabases: string[];
  accessible: boolean | null;
  exportAssistName: string | null;
  discoveryAssistName: string | null;
  host: string | null;
  port: number | null;
  serviceName: string | null;
  credentialsSaved: boolean;
}

/** `DatastoreDatabases` (`/scan-settings/databases`). */
export interface DatastoreDatabases {
  databases: string[];
}

/** `DatastoreSyncStatus` (`/sync-status`). `status` null: no sync job. */
export interface DatastoreSyncStatus {
  status: string | null;
  updatedAt: number | null;
}

// ─── Query objects of the list endpoints ────────────────────────────────────────────────────────

/** Paging of the Scan Management lists: page from 1, pageSize up to 100. */
export interface ScanPaging {
  page: number;
  pageSize: number;
}

/** `/schedules` filters. */
export interface ScanScheduleQuery extends ScanPaging {
  q?: string | null;
  platform?: string | null;
  status?: string | null;
  coverage?: string | null;
}

/** `/scan-history` filters. */
export interface ScanRunQuery extends ScanPaging {
  q?: string | null;
  coverage?: string | null;
  platform?: string | null;
  status?: string | null;
  trigger?: string | null;
}

/** `/scan-history/{runId}/datastores` filters. */
export interface ScanRunDatastoreQuery extends ScanPaging {
  q?: string | null;
  account?: string | null;
  status?: string | null;
}

/** `/scan-scopes` filters. */
export interface ScanScopeQuery extends ScanPaging {
  q?: string | null;
  platform?: string | null;
}

/** `/saas-integrations/{id}/users` filters; `driveType` is required. */
export interface SaasUserQuery extends ScanPaging {
  driveType: string;
  q?: string | null;
  status?: string | null;
  domain?: string | null;
}

/** `/saas-integrations/{id}/channels` filters. */
export interface SaasChannelQuery extends ScanPaging {
  q?: string | null;
  channelType?: string | null;
  channelStatus?: string | null;
  scanState?: string | null;
  isNew?: boolean | null;
}
