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
}

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

export interface LabelAssignRequest {
  accountId: string;   // provider account ID (AWS account ID, GCP project ID, etc.)
  labelId: number;
  resources: ResourceRef[];
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
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  last: boolean;
}
