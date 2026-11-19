/**
 * Mirrors of the Files page DTOs (`/api/dspm/files/**` and `/api/dspm/endpoint-files/{fileId}/**`).
 * Times are epoch seconds. The Endpoint tab's list reuses build 2's `EndpointFilePage`, and the Endpoint
 * file drawer's File Copies build 2's `SaasFileCopies`.
 */
import type { SaasFileLineage, SaasFileLineageEvent } from './dspmDrawers';

// ─── 1. SaaS file list (`GET /api/dspm/files/saas`) ─────────────────────────────────────────────

/** `SaasFileListPage.SharingMode`. */
export interface SharingMode {
  /** `PUBLIC`, `EXTERNAL`, `INTERNAL` or `PRIVATE`. */
  code: string;
  /** "Anyone with the link", "Externally Shared", "Internally Shared" or "Private". */
  label: string;
}

/** `SaasFileListPage.Row`. */
export interface SaasFileListRow {
  /** The file's 24-hex id, which opens the SaaS file drawer; null when missing. */
  id: string | null;
  name: string | null;
  /** A short extension, or for a Gmail row the vendor's subject text. */
  extension: string | null;
  /** The app type code: `GMAIL`, `GOOGLE_WORKSPACE`, `ONE_DRIVE`, … */
  source: string | null;
  /** "Gmail", "Google Drive", "OneDrive", … */
  sourceLabel: string | null;
  categoryLabel: string | null;
  documentClassLabel: string | null;
  /** In the console's order; never empty ("Private" when nothing else applies). */
  sharingModes: SharingMode[];
  owner: string | null;
  lastModifiedAt: number | null;
}

/** `SaasFileListPage`: one keyset page. */
export interface SaasFileListPage {
  rows: SaasFileListRow[];
  limit: number;
  /** The cursor of the next page, or null on the last one. */
  nextCursor: string | null;
}

// ─── 2. Endpoint file drawer (`/api/dspm/endpoint-files/{fileId}/…`) ────────────────────────────

/** `EndpointFileOverview.Sensitive`. */
export interface EndpointFileSensitive {
  dataElement: string | null;
  categoryLabel: string | null;
  count: number;
}

/** `EndpointFileOverview`. */
export interface EndpointFileOverview {
  name: string | null;
  extension: string | null;
  owner: string | null;
  categoryLabel: string | null;
  documentClassLabel: string | null;
  sensitiveRecords: number;
  path: string | null;
  sizeBytes: number | null;
  device: string | null;
  modifiedAt: number | null;
  fileCreatedAt: number | null;
  accessedAt: number | null;
  tags: string[];
  sensitives: EndpointFileSensitive[];
}

/** `EndpointFileActivity.Entry`. */
export interface EndpointFileActivityEntry {
  action: string | null;
  actionLabel: string | null;
  selectedTag: string | null;
  previousTag: string | null;
  performedBy: string | null;
  occurredAt: number | null;
  keywordsWritten: boolean;
  mipLabelWritten: boolean;
}

/** `EndpointFileActivity`: API order. */
export interface EndpointFileActivity {
  entries: EndpointFileActivityEntry[];
}

/** `EndpointFileLineage.Asset`: the file's own lane. Its events carry `laneKey` null. */
export interface EndpointFileLineageAsset {
  title: string | null;
  device: string | null;
  osUser: string | null;
  firstSeenAt: number | null;
  events: SaasFileLineageEvent[];
}

/** `EndpointFileLineage`. */
export interface EndpointFileLineage {
  /** Null when the vendor has no lineage asset for the file. */
  asset: EndpointFileLineageAsset | null;
  /** Build 2's hash-lineage lanes of the file's copies. */
  copies: SaasFileLineage;
}
