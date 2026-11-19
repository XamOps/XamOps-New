/**
 * The Data Lineage page's responses (`/api/dspm/lineage/…`), field for field the backend records of build 4
 * design 5.4. Epoch values are seconds. The filter options (`/filter-options`) use build 1's
 * `DatastoreFilterOptions`, a file's lineage (`/files/{id}/lineage`) build 2's `SaasFileLineage`.
 */

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
