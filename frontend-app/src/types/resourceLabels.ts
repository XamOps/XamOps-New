/**
 * Resource Labels — XamOps-internal virtual tagging
 * Labels are NOT pushed to AWS. They live only in XamOps.
 * Users can name labels anything: team, department, project, environment, etc.
 */

export interface ResourceLabel {
  id: number;
  name: string;
  color: string;         // hex color, e.g. "#6366f1"
  description?: string;
  createdAt: string;
  createdBy?: string;
  resourceCount: number;
  /**
   * The share of a resource's or service's cost this label takes, 0–100, when the label is read
   * as an assignment (the resource and service maps). Null or absent means the whole cost.
   */
  percent?: number | null;
}

/** One label and its share, as sent when saving the full set of labels on one item. */
export interface LabelShare {
  labelId: number;
  /** 0–100, at most two decimals. Null means the whole cost. */
  percent: number | null;
}

/** Replaces every label on one resource. Labels left out are removed. */
export interface ResourceLabelSplitRequest {
  accountId: string;
  resource: ResourceRef;
  labels: LabelShare[];
  source?: LabelChangeSource;
}

/** Replaces every label on one billing service. Labels left out are removed. */
export interface ServiceLabelSplitRequest {
  accountId: string;
  /** The service name exactly as the bill prints it. */
  service: string;
  labels: LabelShare[];
  source?: LabelChangeSource;
}

// service name → labels assigned to that service
export type ServiceLabelMap = Record<string, ResourceLabel[]>;

export interface LabeledResource {
  resourceId: string;
  resourceType: string;
  resourceName?: string;
  region?: string;
  taggedBy?: string;
  taggedAt: string;
}

export interface ResourceRef {
  resourceId: string;
  resourceType: string;
  resourceName?: string;
  region?: string;
}

/**
 * Which screen made a label change, recorded in the audit trail so its history can say where a
 * change came from. Optional on every write; the server stores anything it does not know as unknown.
 */
export type LabelChangeSource =
  | 'COST_RESOURCE_PANEL'
  | 'COST_SERVICE_ROW'
  | 'CASCADE'
  | 'CLOUD_LIST'
  | 'LABELS_PAGE';

export interface LabelAssignRequest {
  accountId: string;   // provider account ID (AWS account ID, GCP project ID, etc.)
  labelId: number;
  resources: ResourceRef[];
  source?: LabelChangeSource;
}

export interface ResourceMapRequest {
  accountId: string;   // same provider account ID used everywhere else in XamOps
  resourceIds: string[];
}

// resourceId → labels assigned to that resource
export type ResourceLabelMap = Record<string, ResourceLabel[]>;

export interface CreateLabelRequest {
  name: string;
  color: string;
  description?: string;
  source?: LabelChangeSource;
}

// ── Audit trail ──────────────────────────────────────────────────────────────

export type LabelAuditAction =
  | 'LABEL_CREATED'
  | 'LABEL_UPDATED'
  | 'LABEL_DELETED'
  | 'LABELS_SET'
  | 'LABEL_ASSIGNED'
  | 'LABEL_REMOVED';

export type LabelAuditTargetType = 'RESOURCE' | 'SERVICE' | 'LABEL';

/**
 * One thing an audit event changed, with its state either side as JSON text.
 *
 * <p>For a resource or service: the full set of labels on it, `[{labelId, name, color, percent}]`,
 * a null percent meaning the whole cost. For a label: its own `{id, name, color, description}`.
 * Null means there was none.
 */
export interface LabelAuditChange {
  targetType: LabelAuditTargetType;
  targetId: string | null;
  targetName: string | null;
  before: string | null;
  after: string | null;
}

export interface LabelAuditEvent {
  id: number;
  /** ISO-8601 instant. */
  at: string;
  actor: string | null;
  action: LabelAuditAction;
  /** REJECTED for an attempt the server refused; the reason is in `detail`. */
  outcome: 'OK' | 'REJECTED';
  source: LabelChangeSource | null;
  /** The label the event was about, as it was then. */
  labelId: number | null;
  labelName: string | null;
  labelColor: string | null;
  detail: string | null;
  /** Changes visible from this account, of which `changes` lists the first. */
  changeCount: number;
  changes: LabelAuditChange[];
}

export interface LabelHistoryPage {
  events: LabelAuditEvent[];
  hasMore: boolean;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  last: boolean;
}
