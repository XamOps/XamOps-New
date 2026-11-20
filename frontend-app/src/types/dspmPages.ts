/**
 * Types for the XamLens Dashboard, Document Classes, Assists and Remediation pages.
 *
 * Mirrors the Java records in `com.xammer.cloud.dto.matters.pages` field for field (design 5.4). A nested
 * Java record is named after its parent here (`DspmDashboard.Environment` is `DashboardEnvironment`). Every
 * record is serialised with `JsonInclude.ALWAYS`, so a Java null arrives as `null`, never as a missing key.
 * Java `String` and `Long` fields are nullable here unless the mapper always sets them; `long`, `int` and
 * `boolean` never are, and lists and maps are never null. Times are epoch seconds.
 */
import type { DspmTrendPoint } from './dspm';
import type { DatastoreRow } from './dspmDatastores';

// ─── Dashboard (`/api/dspm/dashboard`) ──────────────────────────────────────────────────────────

/** An environment card's id: the backend's tab slug. */
export type DashboardEnvironmentId = 'cloud' | 'saas' | 'endpoint' | 'on_prem';

/** A Top Risky Datastores tab: All and the four environments. */
export type TopRiskyTab = 'all' | DashboardEnvironmentId;

/** `DspmDashboard.SubCategory`: one bar of a card. */
export interface DashboardSubCategory {
  /** The provider (cloud, on-prem), drive type (SaaS) or OS (endpoint) code. */
  key: string;
  label: string;
  sensitiveRecords: number;
  datastores: number;
}

/** `DspmDashboard.Environment`: one "Where does sensitive data live" card. */
export interface DashboardEnvironment {
  id: DashboardEnvironmentId;
  datastores: number;
  sensitiveRecords: number;
  /** The tab's daily sensitive-record totals from the summary, oldest first. */
  recordsTrend: DspmTrendPoint[];
  connected: boolean;
  /** The Datastores filter a bar opens; null on SaaS, whose bars open nothing. */
  filter: 'provider' | 'os' | null;
  subCategories: DashboardSubCategory[];
  /** The bars cover only the first rows of a large environment. */
  partial: boolean;
}

/**
 * `DspmDashboard.Region`: one "Active Sensitive Regions" region, the collected cloud rows of one
 * `source.region` (listed when one of them has sensitive records or is classified).
 */
export interface DashboardRegion {
  /** The raw region code, e.g. `eu-west-3`; the widget labels and places it. */
  region: string;
  datastores: number;
  /** Rows whose scan status is CLASSIFIED. */
  scannedDatastores: number;
  sensitiveRecords: number;
  /** Sensitive category labels, the most common first. */
  sensitiveLabels: string[];
}

/** `DspmDashboard`. Environments in the order cloud, saas, endpoint, on_prem. */
export interface DspmDashboard {
  environments: DashboardEnvironment[];
  topRisky: Record<TopRiskyTab, DatastoreRow[]>;
  /** Most sensitive records first. */
  regions: DashboardRegion[];
}

// ─── Document Classes (`/api/dspm/document-classes`) ────────────────────────────────────────────

/** `DocumentClassList.Row`. */
export interface DocumentClassRow {
  name: string;
  category: string;
}

/** `DocumentClassList`. */
export interface DocumentClassList {
  categories: string[];
  rows: DocumentClassRow[];
}

// ─── Assists (`/api/dspm/assists`) ──────────────────────────────────────────────────────────────

/** `AssistList.Assist`. */
export interface Assist {
  id: string | null;
  name: string | null;
  /** Null when the vendor's value looked like a credential. */
  clientId: string | null;
  healthStatus: string | null;
  statusLabel: string | null;
  assistType: string | null;
  assistTypeLabel: string | null;
  version: string | null;
  secretExpiresAt: number | null;
}

/** `AssistList`. */
export interface AssistList {
  assists: Assist[];
}

// ─── Remediation (`/api/dspm/remediation/activity`) ─────────────────────────────────────────────

/** `RemediationActivity.Summary`: the cards. */
export interface RemediationSummary {
  total: number;
  waitingApproval: number;
  inProgressScheduled: number;
  completed: number;
  failed: number;
}

/** `RemediationActivity.Person`. */
export interface RemediationPerson {
  name: string | null;
  email: string | null;
}

/** `RemediationActivity.Item`: one run. */
export interface RemediationItem {
  id: string | null;
  actionType: string | null;
  actionLabel: string | null;
  objectName: string | null;
  objectPath: string | null;
  datastoreType: string | null;
  datastoreLabel: string | null;
  sourceType: string | null;
  initiatedBy: RemediationPerson | null;
  triggerType: string | null;
  triggerLabel: string | null;
  createdAt: number | null;
  status: string | null;
  statusLabel: string | null;
  approvers: string[];
  scheduledAt: number | null;
}

/** `RemediationActivity`. */
export interface RemediationActivity {
  summary: RemediationSummary;
  statusCounts: Record<string, number>;
  total: number;
  skip: number;
  limit: number;
  items: RemediationItem[];
}
