/**
 * The Data Lineage page's responses (`/api/dspm/lineage/…`), field for field the backend records of build 4
 * design 5.4. Epoch values are seconds. The filter options (`/filter-options`) use build 1's
 * `DatastoreFilterOptions`, a file's lineage (`/files/{id}/lineage`) build 2's `SaasFileLineage`.
 */
import type { SaasFileLineage } from './dspmDrawers';
import type { EndpointFileLineageAsset } from './dspmFiles';

/** One exposure category card. */
export interface LineageCategory {
  /** The category id (`SENSITIVE_FINDINGS`, …): the URL value and the icon. */
  id: string;
  name: string;
  /** The card's count; null while the page is unavailable. */
  exposures: number | null;
}

/** `GET /api/dspm/lineage/categories`. */
export interface LineageCategories {
  /** False while the exposure routes are switched off: step 0 shows greyed cards. */
  available: boolean;
  /** In the console's order. */
  categories: LineageCategory[];
}

/** One policy node (one exposure is one policy). */
export interface LineagePolicy {
  /** A lower-cased UUID; null: the node is not choosable. */
  id: string | null;
  name: string | null;
  /** CRITICAL | HIGH | MEDIUM | LOW: the dot colour; null otherwise. */
  severity: string | null;
  /** "Medium". */
  severityLabel: string | null;
  /** The target service, upper-cased: the icon. */
  service: string | null;
  /** "N datastores". */
  datastores: number | null;
}

/** `GET /api/dspm/lineage/policies?category=…`. */
export interface LineagePolicyPage {
  available: boolean;
  rows: LineagePolicy[];
  /** The category node's chip. */
  total: number;
  skip: number;
  limit: number;
}

/** One datastore node of a policy. */
export interface LineageDatastore {
  /** The exposure id: opens the files; null when not choosable. */
  id: string | null;
  name: string | null;
  /** The datastore service, upper-cased: the icon. */
  service: string | null;
  /** "N files". */
  files: number | null;
}

/** `GET /api/dspm/lineage/policies/{policyId}/datastores`. */
export interface LineageDatastorePage {
  available: boolean;
  rows: LineageDatastore[];
  total: number;
  skip: number;
  limit: number;
}

/** One file node of a datastore. */
export interface LineageFile {
  /** 24 hex or a UUID, lower-cased: opens the lineage; null when not choosable. */
  id: string | null;
  name: string | null;
  /** The file icon. */
  extension: string | null;
  /** "Actor: …". */
  actor: string | null;
  /** The node date. */
  modifiedAt: number | null;
}

/** `GET /api/dspm/lineage/datastores/{exposureId}/files`. */
export interface LineageFilePage {
  available: boolean;
  rows: LineageFile[];
  /** All files of the datastore: the "›" rule. */
  total: number;
  skip: number;
  limit: number;
}

// ─── The public path (`/api/dspm/lineage/public/…`, build 7 design 5.2) ─────────────────────────

/** One datastore node of the public path: an endpoint, or one person's drive. */
export interface PublicLineageDatastore {
  /** `ep-<agentId>` or `dr-<integrationId>-<resourceId>`: opens the files; null when not choosable. */
  id: string | null;
  kind: 'ENDPOINT' | 'DRIVE';
  name: string | null;
  /** `ENDPOINT`, `GOOGLE_DRIVE`, `ONE_DRIVE`, `SHARE_POINT`, another provider code, or null: the icon. */
  service: string | null;
  /** "N files"; null when the count could not be read. */
  files: number | null;
}

/** `GET /api/dspm/lineage/public/datastores`. */
export interface PublicLineageDatastorePage {
  rows: PublicLineageDatastore[];
  /** After the search: the "›" rule. */
  total: number;
  skip: number;
  limit: number;
}

/** One file node of a public datastore. */
export interface PublicLineageFile {
  /** Endpoint: a lower-cased UUID; drive: the doc id as returned; null when not choosable. */
  id: string | null;
  name: string | null;
  /** The file icon. */
  extension: string | null;
  /** Endpoint: the current user; drive: null (no such field). */
  actor: string | null;
  /** The node date: endpoint the modified time, drive the last-seen time. */
  dateAt: number | null;
  /** Endpoint: the creation time ("File Created"); drive: null. */
  createdAt: number | null;
}

/** `GET /api/dspm/lineage/public/datastores/{datastoreId}/files`. */
export interface PublicLineageFilePage {
  rows: PublicLineageFile[];
  total: number;
  skip: number;
  limit: number;
}

/** `GET /api/dspm/lineage/public/datastores/{datastoreId}/files/{fileId}/lineage`. */
export interface PublicFileLineage {
  kind: 'ENDPOINT' | 'DRIVE';
  /** Build 3's lineage asset; null when the route has none for the file. */
  asset: EndpointFileLineageAsset | null;
  /** Endpoint: build 2's hash lineage; drive: unavailable. */
  copies: SaasFileLineage;
  /** Drive: the asset's owner e-mail (the lane caption and the First Seen actor); endpoint: null. */
  ownerEmail: string | null;
}
