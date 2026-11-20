/**
 * Types for the XamLens Misconfigurations page and its row drawer.
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters.misconfigurations` field for field. Every
 * record is serialised with `JsonInclude.ALWAYS`, so a Java null arrives as `null`, never as a missing key.
 *
 * The backend builds both from the per-datastore issues route over every cloud and on-prem datastore,
 * grouped by policy id. Rows arrive in display order: severity (Critical, High, Medium, Low, other), then
 * Affected Resources descending, then title, then policy id.
 */

import type { DatastoreRow } from './dspmDatastores';

/** Policies per severity; a severity outside the four counts in `total` only. */
export interface SeverityCounts {
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
}

/** One policy across the datastores that report it. */
export interface MisconfigurationRow {
  /** Matches `^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`; rows with another id are dropped by the backend. */
  policyId: string;
  title: string | null;
  /** `UPWIND` or `MATTERS`, upper-cased. Treat the set as open. */
  source: string | null;
  /** `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, upper-cased, not checked against a set. */
  severity: string | null;
  severityLabel: string | null;
  /** Distinct datastores with the policy. */
  affectedResources: number;
  /** Sum of the affected datastores' sensitive record counts. */
  sensitiveRecords: number;
  /** Union of the affected datastores' sensitive type labels, first seen first. */
  labels: string[];
  /** Distinct compliance framework names, first seen first. */
  frameworks: string[];
  /** Account labels of the affected cloud datastores (never on-prem ones). */
  cloudAccounts: string[];
}

/** `GET /api/dspm/misconfigurations`. */
export interface MisconfigurationList {
  /** Distinct datastores with any policy. */
  totalAffectedResources: number;
  open: SeverityCounts;
  /** Datastores whose issues were read. */
  datastoresRead: number;
  /** True when the project has more datastores than the backend reads. */
  capped: boolean;
  rows: MisconfigurationRow[];
}

/** One datastore that reports the policy. */
export interface AffectedDatastore {
  /** The same row the Datastores list serves. */
  datastore: DatastoreRow;
  /** Epoch seconds of the datastore's last scan, or null. */
  lastScannedAt: number | null;
}

/** `GET /api/dspm/misconfigurations/policies/{policyId}`. */
export interface MisconfigurationDetail {
  policyId: string;
  title: string | null;
  source: string | null;
  severity: string | null;
  severityLabel: string | null;
  /** Markdown. */
  description: string | null;
  /** Markdown. */
  remediation: string | null;
  /** The CLI fix, shown as a Bash block. */
  cli: string | null;
  /** An absolute http or https URL, else null. */
  learnMore: string | null;
  frameworks: string[];
  /** In the backend's datastore order. */
  affected: AffectedDatastore[];
}
