/**
 * The Data Lineage page's URL state and every transition on it (build 4 design 4.6, 6.5; FR-NAV-3,
 * FR-INV-7). Pure, so the click rules are one function with one table of tests. Components parse the URL
 * with `parseLineageQuery`, apply a transition and write the result with `toSearchParams` and
 * `{ replace: true }`.
 */

import { MAX_SEARCH, normalizeSearch } from '../datastores/datastoreFilters';

/** The six exposure categories, in the console's order. */
export const LINEAGE_CATEGORY_IDS = [
  'SENSITIVE_FINDINGS', 'EXCESSIVE_EXPOSURE', 'HIGH_VOLUME_ACCESS', 'STALE_DATA', 'RISKY_USER', 'PUBLIC_EXPOSURE',
] as const;
export type LineageCategoryId = (typeof LINEAGE_CATEGORY_IDS)[number];

export type RowLevel = 'policy' | 'datastore' | 'file';
export type OpenLevel = RowLevel | 'event';

export interface LineageQuery {
  category: LineageCategoryId | null;
  policy: string | null;
  datastore: string | null;
  file: string | null;
  /** Each row's page, from 0. */
  page: Record<RowLevel, number>;
  /** Each level's search text; '' for none. */
  q: Record<OpenLevel, string>;
}

/** Step 0: no query at all ("New Investigation", D1). */
export const EMPTY_QUERY: LineageQuery = {
  category: null,
  policy: null,
  datastore: null,
  file: null,
  page: { policy: 0, datastore: 0, file: 0 },
  q: { policy: '', datastore: '', file: '', event: '' },
};

/** Nodes per row page. */
export const PAGE_SIZE = 7;
/** Policies and datastores a search returns in one row. */
export const SEARCH_LIMIT = 50;
/** Files fetched once and filtered by name in the browser. */
export const FILE_SEARCH_LIMIT = 100;
/** The last page: 1428 × 7 ≤ 10,000, the backend's highest skip. */
export const MAX_PAGE = 1428;

const ROW_LEVELS: readonly RowLevel[] = ['policy', 'datastore', 'file'];
const OPEN_LEVELS: readonly OpenLevel[] = ['policy', 'datastore', 'file', 'event'];

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const HEX24 = /^[0-9a-fA-F]{24}$/;
const EXPOSURE_ID = /^[A-Za-z0-9-]{1,64}$/;
const PAGE = /^\d{1,4}$/;

export function isLineageCategoryId(value: string | null): value is LineageCategoryId {
  return value !== null && (LINEAGE_CATEGORY_IDS as readonly string[]).includes(value);
}

/** Search text as kept: build 1's normalisation, cut to 200 characters; '' for none. */
function searchText(raw: string | null): string {
  if (raw === null) return '';
  return (normalizeSearch(raw) ?? '').slice(0, MAX_SEARCH);
}

function pageNumber(raw: string | null): number {
  if (raw === null || !PAGE.test(raw)) return 0;
  const page = Number(raw);
  return page <= MAX_PAGE ? page : 0;
}

/**
 * The query from the URL. A value that fails its rule is absent, and a missing parent removes everything
 * that requires it: `policy` needs `category`, `datastore` needs `policy`, `file` needs `datastore`; each
 * row's page needs that row's parent, each level's search the same parent (`eventQ` needs `file`).
 */
export function parseLineageQuery(params: URLSearchParams): LineageQuery {
  const rawCategory = params.get('category');
  const category = isLineageCategoryId(rawCategory) ? rawCategory : null;

  const rawPolicy = params.get('policy');
  const policy = category !== null && rawPolicy !== null && UUID.test(rawPolicy) ? rawPolicy.toLowerCase() : null;

  const rawDatastore = params.get('datastore');
  const datastore = policy !== null && rawDatastore !== null && EXPOSURE_ID.test(rawDatastore) ? rawDatastore : null;

  const rawFile = params.get('file');
  const file = datastore !== null && rawFile !== null && (HEX24.test(rawFile) || UUID.test(rawFile))
    ? rawFile.toLowerCase()
    : null;

  return {
    category,
    policy,
    datastore,
    file,
    page: {
      policy: category === null ? 0 : pageNumber(params.get('policyPage')),
      datastore: policy === null ? 0 : pageNumber(params.get('datastorePage')),
      file: datastore === null ? 0 : pageNumber(params.get('filePage')),
    },
    q: {
      policy: category === null ? '' : searchText(params.get('policyQ')),
      datastore: policy === null ? '' : searchText(params.get('datastoreQ')),
      file: datastore === null ? '' : searchText(params.get('fileQ')),
      event: file === null ? '' : searchText(params.get('eventQ')),
    },
  };
}

/**
 * The canonical URL query: `category, policy, datastore, file, policyPage, policyQ, datastorePage,
 * datastoreQ, filePage, fileQ, eventQ`, without pages of 0 and empty searches. A parsed-and-written URL is
 * therefore canonical.
 */
export function toSearchParams(query: LineageQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.category !== null) params.set('category', query.category);
  if (query.policy !== null) params.set('policy', query.policy);
  if (query.datastore !== null) params.set('datastore', query.datastore);
  if (query.file !== null) params.set('file', query.file);
  for (const level of ROW_LEVELS) {
    if (query.page[level] > 0) params.set(`${level}Page`, String(query.page[level]));
    if (query.q[level] !== '') params.set(`${level}Q`, query.q[level]);
  }
  if (query.q.event !== '') params.set('eventQ', query.q.event);
  return params;
}

/** The deepest open level: the first level without a choice. Null is step 0 (no category). */
export function openLevel(query: LineageQuery): OpenLevel | null {
  if (query.category === null) return null;
  if (query.policy === null) return 'policy';
  if (query.datastore === null) return 'datastore';
  if (query.file === null) return 'file';
  return 'event';
}

/** A click's new query and the rows whose filters reset. */
export interface Transition {
  query: LineageQuery;
  /** Rows whose filters reset. */
  cleared: RowLevel[];
}

/** The id chosen at a row level. */
const chosenAt = (query: LineageQuery, level: RowLevel): string | null => query[level];

/** The row levels after `level` (`category` is above every row). */
function rowsBelow(level: 'category' | RowLevel): RowLevel[] {
  return level === 'category' ? [...ROW_LEVELS] : ROW_LEVELS.slice(ROW_LEVELS.indexOf(level) + 1);
}

/** The open levels after `level`. */
function openLevelsBelow(level: 'category' | RowLevel): OpenLevel[] {
  return level === 'category' ? [...OPEN_LEVELS] : OPEN_LEVELS.slice(OPEN_LEVELS.indexOf(level) + 1);
}

/** The query without any choice, page or search below `level`. */
function clearBelow(query: LineageQuery, level: 'category' | RowLevel): LineageQuery {
  const next: LineageQuery = { ...query, page: { ...query.page }, q: { ...query.q } };
  for (const row of rowsBelow(level)) {
    next[row] = null;
    next.page[row] = 0;
  }
  for (const open of openLevelsBelow(level)) next.q[open] = '';
  return next;
}

/**
 * A click on a node (FR-INV-7, D3):
 * - the category node clears every level below it (every choice, page and search) when a policy is
 *   chosen, and does nothing otherwise;
 * - an unchosen node at row L is chosen, and everything below L is cleared;
 * - the chosen node with a chosen node below it stays chosen, and everything below it is cleared;
 * - the deepest chosen node is unchosen; its row keeps its page and search.
 * `cleared` lists the rows below the clicked one whose filters reset.
 */
export function clickNode(query: LineageQuery, level: 'category' | RowLevel, id: string): Transition {
  if (level === 'category') {
    if (query.policy === null) return { query, cleared: [] };
    return { query: clearBelow(query, 'category'), cleared: ['policy', 'datastore'] };
  }
  const cleared = rowsBelow(level);
  const below = clearBelow(query, level);
  if (chosenAt(query, level) !== id) return { query: { ...below, [level]: id }, cleared };
  const deeper = rowsBelow(level).some(row => query[row] !== null);
  if (deeper) return { query: below, cleared };
  return { query: { ...below, [level]: null }, cleared };
}

/** The row at `page`, clamped to 0..MAX_PAGE. */
export function setPage(query: LineageQuery, level: RowLevel, page: number): LineageQuery {
  const clamped = Math.min(MAX_PAGE, Math.max(0, Math.trunc(page)));
  return { ...query, page: { ...query.page, [level]: clamped } };
}

/** A level's search text, normalised and cut to 200; a row's page goes back to 0. */
export function setSearch(query: LineageQuery, level: OpenLevel, text: string): LineageQuery {
  const next: LineageQuery = { ...query, q: { ...query.q, [level]: searchText(text) } };
  if (level !== 'event') next.page = { ...query.page, [level]: 0 };
  return next;
}
