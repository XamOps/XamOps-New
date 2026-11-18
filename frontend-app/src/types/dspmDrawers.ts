/**
 * Types for the XamLens SaaS drawer, SaaS file drawer, Endpoint drawer and the Data Flow Graph View.
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters.datastores` field for field (design 5.4). A
 * nested Java record is named after its parent here: `SaasFileCopies.Copy` is `SaasFileCopy`,
 * `SaasFileLineage.Lane` is `SaasFileLineageLane`, and so on (each interface names its record). Every
 * record is serialised with `JsonInclude.ALWAYS`, so a Java null arrives as `null`, never as a missing key.
 * Java `String`, `Long`, `Double` and `Boolean` fields are nullable here unless the mapper always sets
 * them; `long`, `int` and `boolean` never are, and lists are never null.
 *
 * Counts and times stay numeric (epoch seconds): the frontend formats them, as in
 * `types/dspmDatastores.ts`. The cache-only `EndpointSummarySnapshot` is never returned, so it has no
 * mirror.
 */

// ─── 1. SaaS drawer header (`/api/dspm/datastores/{sourceId}/saas/detail`) ──────────────────────

/** `SaasDrawerHeader`. The title and subtitle use the list row. */
export interface SaasDrawerHeader {
  /** `GMAIL`, `GOOGLE_WORKSPACE`: the Asset Type icon. */
  driveType: string | null;
  driveTypeLabel: string | null;
  accountName: string | null;
  /** The Backfill Scan chip's icon (`saasScanStateVisual`). */
  backfillState: string | null;
  backfillLabel: string | null;
  /** The Incremental Scan chip's icon. */
  incrementalState: string | null;
  incrementalLabel: string | null;
}

// ─── 2. SaaS records (`/saas/records?view=`) ────────────────────────────────────────────────────

/** The two views of the SaaS drawer's Sensitives tab, as the `view` parameter names them. */
export type SaasRecordsView = 'data_elements' | 'document_classes';

/** `SaasRecords.Row`. Rows with a blank name are dropped by the mapper. */
export interface SaasRecordRow {
  /** The data element (`EMAIL ADDRESS`) or the document class label (`Incident Reports`). */
  name: string;
  categoryLabel: string | null;
  count: number;
}

/** `SaasRecords`. */
export interface SaasRecords {
  /** Echoes the request. */
  view: SaasRecordsView;
  /** API order (count order, ties as the vendor sends them). */
  rows: SaasRecordRow[];
}

// ─── 3. SaaS file list (`/saas/files`) ──────────────────────────────────────────────────────────

/** `SaasFilePage.File`. */
export interface SaasFileRow {
  /** 24 hex, lower-cased; null when the vendor's id is not, and the row then opens nothing. */
  id: string | null;
  name: string | null;
  records: number;
}

/** `SaasFilePage`. */
export interface SaasFilePage {
  rows: SaasFileRow[];
  /** Echo the request. */
  skip: number;
  limit: number;
  total: number;
}

// ─── 4. SaaS Information (`/saas/scan-summary`) ─────────────────────────────────────────────────

/** `SaasScanSummary.Part`. */
export interface SaasScanSummaryPart {
  count: number;
  sizeBytes: number;
}

/** `SaasScanSummary.FileType`. */
export interface SaasScanSummaryFileType {
  /** The chip's React key. */
  extension: string;
  label: string | null;
  count: number;
}

/** `SaasScanSummary.Properties`. */
export interface SaasScanSummaryProperties {
  owner: string | null;
  datastoreSizeBytes: number | null;
  createdOn: number | null;
  lastScan: number | null;
}

/** `SaasScanSummary`. */
export interface SaasScanSummary {
  supported: SaasScanSummaryPart;
  unsupported: SaasScanSummaryPart;
  /** Count descending, then label, then extension (the mapper's order). */
  fileTypes: SaasScanSummaryFileType[];
  properties: SaasScanSummaryProperties;
}

// ─── 5. SaaS file overview (`/api/dspm/saas-files/{fileId}/overview`) ───────────────────────────

/** `SaasFileOverview.Sharing`. No component starts with "is", which Jackson would drop. */
export interface SaasFileSharing {
  internalEmails: string[];
  externalEmails: string[];
  internalGroups: string[];
  externalGroups: string[];
  privateShare: boolean;
  publicShare: boolean;
  orgWide: boolean;
  externalShare: boolean;
}

/** `SaasFileOverview.Sensitive`. */
export interface SaasFileSensitive {
  dataElement: string | null;
  categoryLabel: string | null;
  count: number;
}

/** `SaasFileOverview.MetadataEntry`: a text-valued metadata key ("Threadid"), in API order. */
export interface SaasFileMetadataEntry {
  /** The React key. */
  key: string;
  label: string | null;
  value: string;
}

/** `SaasFileOverview`. The drawer is opened by the list row's id, so the overview's own id is not returned. */
export interface SaasFileOverview {
  name: string | null;
  /** Lower-cased; picks the header icon (`msg` is an e-mail). */
  fileExtension: string | null;
  /** "Outlook Message", "DOCX": the header's subtitle. */
  fileTypeLabel: string | null;
  /** The card's icon; `GMAIL` selects the Gmail lineage. */
  driveType: string | null;
  accountName: string | null;
  owner: string | null;
  categoryLabel: string | null;
  documentClassLabel: string | null;
  sizeBytes: number | null;
  structured: boolean | null;
  lastModifiedAt: number | null;
  lastModifiedBy: string | null;
  sensitiveRecords: number;
  policyViolations: number | null;
  sharing: SaasFileSharing;
  sensitives: SaasFileSensitive[];
  metadata: SaasFileMetadataEntry[];
}

// ─── 6. File Activity (`/saas-files/{fileId}/activity`) ─────────────────────────────────────────

/** `SaasFileActivity.Entry`. */
export interface SaasFileActivityEntry {
  title: string | null;
  status: string | null;
  actor: string | null;
  occurredAt: number | null;
}

/** `SaasFileActivity`. */
export interface SaasFileActivity {
  /** Newest first. */
  entries: SaasFileActivityEntry[];
}

// ─── 7. Lineage (`/saas-files/{fileId}/lineage`) ────────────────────────────────────────────────

/** `SaasFileLineage.Lane`: a copy of the file's content, in API order, then any timeline-only lane. */
export interface SaasFileLineageLane {
  /** Opaque React key (`copy-<i>`, `tl-<i>`), and the `laneKey` of its events. */
  key: string;
  /** Null on a timeline-only lane. */
  fileName: string | null;
  ownerName: string | null;
  /** The First Seen actor and the popover's Email. */
  ownerEmail: string | null;
  /** The lane icon: the first non-null of the three (`sourceIconKey`). */
  driveType: string | null;
  os: string | null;
  provider: string | null;
  /** The popover's Channel: "Gmail", "Google Drive", "Endpoint". */
  channelLabel: string | null;
  /** The opened file's own copy, by file id (design 4.4). */
  self: boolean;
  /** The copy's first-seen time: its "First Seen" event. Null on a timeline-only lane. */
  firstSeenAt: number | null;
}

/** `SaasFileLineage.Event`. */
export interface SaasFileLineageEvent {
  /** Null when the event matches no lane. */
  laneKey: string | null;
  typeLabel: string | null;
  actor: string | null;
  occurredAt: number | null;
}

/** `SaasFileLineage`. */
export interface SaasFileLineage {
  /** False when lineage answered 404 (lineage off for the account). */
  available: boolean;
  /** The lineage title's ⓘ on non-Gmail files. */
  coverageNote: string | null;
  lanes: SaasFileLineageLane[];
  /** API order. */
  events: SaasFileLineageEvent[];
}

// ─── 17. File Copies and Related Files (`/saas-files/{fileId}/copies`) ──────────────────────────

/** `SaasFileCopies.Copy`: a copy other than the opened file. */
export interface SaasFileCopy {
  /** The React key. */
  key: string;
  fileName: string | null;
  /** The file icon and the label under the name. */
  extension: string | null;
  /** File Path; null on SaaS copies. */
  path: string | null;
  /** The source icon: the first non-null of the three (`sourceIconKey`). */
  driveType: string | null;
  os: string | null;
  provider: string | null;
  /** Related Files' Source: "Google Drive", "Gmail", "Endpoint". */
  sourceLabel: string | null;
  /** The Datastore cell's second line: "SaaS · Gmail". */
  locationLabel: string | null;
  ownerName: string | null;
  /** Where, User/Device Name and the "Same person" rule. */
  ownerEmail: string | null;
  userOrDevice: string | null;
}

/** `SaasFileCopies`. */
export interface SaasFileCopies {
  /** False when lineage answered 404. */
  available: boolean;
  /** API order. */
  copies: SaasFileCopy[];
  /** Distinct lanes among the copies: the Locations card. */
  locations: number;
}

// ─── 8. Endpoint overview (`/api/dspm/datastores/{sourceId}/endpoint/overview`) ─────────────────

/** `EndpointOverview`. The header icon comes from the list row. */
export interface EndpointOverview {
  serialNumber: string | null;
  lastScanAt: number | null;
  lastAccessAt: number | null;
  severityLabel: string | null;
  agentStatusLabel: string | null;
  /** The status icon and the utilization rule. */
  agentOnline: boolean;
  users: string[];
  hostname: string | null;
  visibleIp: string | null;
  macAddress: string | null;
  deviceTypeLabel: string | null;
  osVersion: string | null;
  architecture: string | null;
  memory: string | null;
  cpuUsage: number | null;
  memoryUsage: number | null;
  agentVersion: string | null;
}

// ─── 10. Endpoint Information totals (`/endpoint/summary`) ──────────────────────────────────────

/** `EndpointSummary.ExtensionCount`. */
export interface EndpointExtensionCount {
  /** The chip icon. */
  extension: string;
  label: string | null;
  count: number;
}

/** `EndpointSummary.EntityCount`. */
export interface EndpointEntityCount {
  name: string | null;
  count: number;
}

/** `EndpointSummary.LabelGroup`. */
export interface EndpointLabelGroup {
  label: string | null;
  entities: EndpointEntityCount[];
}

/** `EndpointSummary.CategoryGroup`. */
export interface EndpointCategoryGroup {
  label: string | null;
  documentClasses: EndpointEntityCount[];
}

/** `EndpointSummary`. */
export interface EndpointSummary {
  totalFiles: number | null;
  extensions: EndpointExtensionCount[];
  totalSensitives: number | null;
  labelGroups: EndpointLabelGroup[];
  fileCategories: EndpointCategoryGroup[];
}

// ─── 11. Entity View rows (`/endpoint/sensitives`) ──────────────────────────────────────────────

/** `EndpointMetric`. */
export interface EndpointMetric {
  /** The row layout and the chip colour. */
  kind: 'ENTITY' | 'DOCUMENT_CLASS';
  name: string;
  categoryLabel: string | null;
  count: number;
}

/** `EndpointSensitiveMetrics`. */
export interface EndpointSensitiveMetrics {
  /** Count descending, stable. */
  metrics: EndpointMetric[];
}

// ─── 12. Entity occurrences (`/endpoint/occurrences`): never cached, never logged ───────────────

/** `EndpointOccurrencePage.Occurrence`. */
export interface EndpointOccurrence {
  fileName: string | null;
  path: string | null;
  tags: string[];
  falsePositive: boolean;
  /** Raw (Matters masks endpoint samples itself), rendered only through `drawer/SampleValue.tsx`. */
  samples: string[];
}

/** `EndpointOccurrencePage`. */
export interface EndpointOccurrencePage {
  /** Echoes the request. */
  entityName: string;
  total: number;
  skip: number;
  limit: number;
  rows: EndpointOccurrence[];
}

// ─── 13. Endpoint File View and the document-class expansion (`/endpoint/files`) ────────────────

/** `EndpointFilePage.File`. No samples. */
export interface EndpointFileRow {
  name: string | null;
  /** The file icon. */
  extension: string | null;
  path: string | null;
  user: string | null;
  sizeBytes: number | null;
  sensitiveCount: number;
  /** Display labels. */
  labels: string[];
  /** Title case: "It & Technical". */
  categoryLabel: string | null;
  /** Title case: "System Documentation". */
  documentClassLabel: string | null;
  tags: string[];
  modifiedAt: number | null;
}

/** `EndpointFilePage`. */
export interface EndpointFilePage {
  rows: EndpointFileRow[];
  skip: number;
  limit: number;
  total: number;
}

// ─── 14. Endpoint Scan History (`/endpoint/scans`) ──────────────────────────────────────────────

/** `EndpointScanPage.Scan`. */
export interface EndpointScan {
  startTime: number | null;
  endTime: number | null;
  /** The status icon. */
  status: string | null;
  statusLabel: string | null;
  sensitiveFiles: number | null;
  /** Null when the vendor reports a negative count (an in-progress scan). */
  totalFiles: number | null;
}

/** `EndpointScanPage`. */
export interface EndpointScanPage {
  /** Newest first, as returned. */
  rows: EndpointScan[];
  skip: number;
  limit: number;
  total: number;
}

// ─── 15, 16. Schema diagram (`/api/dspm/data-flow/(schemas|databases)/{id}/erd`) ────────────────

/** `DataFlowErd.Node`. */
export interface DataFlowErdNode {
  /** Opens the table panel and loads the expanded card's columns. */
  id: string;
  name: string | null;
  sensitiveCount: number | null;
  /** The FK chips, in edge order. */
  keyColumns: string[];
}

/** `DataFlowErd.Edge`. */
export interface DataFlowErdEdge {
  /** `fk-<index>`: the React key. */
  id: string;
  sourceTableId: string;
  /** A node id, or a `DataFlowErdExternalTable` key. */
  targetKey: string;
  /** Drawn dashed. */
  external: boolean;
}

/** `DataFlowErd.ExternalTable`. */
export interface DataFlowErdExternalTable {
  /** `ext:<schema>.<table>`. */
  key: string;
  schemaName: string;
  tableName: string;
}

/** `DataFlowErd`. */
export interface DataFlowErd {
  nodes: DataFlowErdNode[];
  edges: DataFlowErdEdge[];
  externalTables: DataFlowErdExternalTable[];
  /** The 1,000-table cap was hit. */
  nodesTruncated: boolean;
  /** The 3,000-key cap was hit. */
  edgesTruncated: boolean;
}

// ─── 18. Table relations (`/api/dspm/data-flow/tables/{tableId}/relations`) ─────────────────────

/** `DataFlowRelations.ColumnPair`. */
export interface DataFlowColumnPair {
  sourceColumn: string | null;
  targetColumn: string | null;
}

/** `DataFlowRelations.Relation`. */
export interface DataFlowRelation {
  schemaName: string | null;
  /** The other table. */
  tableName: string;
  columns: DataFlowColumnPair[];
}

/** `DataFlowRelations`: the table panel's Relationships tab. */
export interface DataFlowRelations {
  /** API order. */
  outgoing: DataFlowRelation[];
  /** API order. */
  incoming: DataFlowRelation[];
}
