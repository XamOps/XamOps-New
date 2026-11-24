/**
 * The public path's URL state and its transitions (build 7 design 6.3): the project root, then the
 * datastores, then the chosen datastore's files, then the chosen file's lineage. Pure, as build 4's
 * `lineageQuery.ts`, whose page size, last page and search rules it reuses. The category and policy
 * levels do not exist here, so their keys are dropped.
 */

import { MAX_SEARCH, normalizeSearch } from '../datastores/datastoreFilters';
import { MAX_PAGE } from './lineageQuery';

export type PublicRowLevel = 'datastore' | 'file';
export type PublicOpenLevel = PublicRowLevel | 'event';

export interface PublicLineageQuery {
  /** `ep-<agentId>` or `dr-<integrationId>-<resourceId>`. */
  datastore: string | null;
  /** Endpoint: a lower-cased UUID; drive: the doc id as sent. */
  file: string | null;
  /** Each row's page, from 0. */
  page: Record<PublicRowLevel, number>;
  /** Each level's search text; '' for none. */
  q: Record<PublicOpenLevel, string>;
}

/** The datastore step under the root ("New Investigation"). */
export const EMPTY_PUBLIC_QUERY: PublicLineageQuery = {
  datastore: null,
  file: null,
  page: { datastore: 0, file: 0 },
  q: { datastore: '', file: '', event: '' },
};

/** A public datastore id, its hex parts lower-case. */
export const DATASTORE_ID = /^(ep-[0-9a-f]{24}|dr-[0-9a-f]{24}-[A-Za-z0-9_-]{1,64})$/;

const ENDPOINT_ID = /^ep-([0-9a-fA-F]{24})$/;
const DRIVE_ID = /^dr-([0-9a-fA-F]{24})-([A-Za-z0-9_-]{1,64})$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;
const PAGE = /^\d{1,4}$/;

/** The datastore's kind, by its prefix. */
export function datastoreKind(id: string): 'ENDPOINT' | 'DRIVE' {
  return id.startsWith('ep-') ? 'ENDPOINT' : 'DRIVE';
}

/** The id with its hex parts lower-cased, or null when it is no public datastore id. */
function datastoreId(raw: string | null): string | null {
  if (raw === null) return null;
  const endpoint = ENDPOINT_ID.exec(raw);
  if (endpoint) return `ep-${endpoint[1].toLowerCase()}`;
  const drive = DRIVE_ID.exec(raw);
  if (drive) return `dr-${drive[1].toLowerCase()}-${drive[2]}`;
  return null;
}

/**
 * A file id as the datastore's kind accepts it: an endpoint file's UUID, lower-cased; a drive document
 * id (letters, digits, "_" and "-", up to 128, not UUID-shaped, as the backend's rule) as sent; else null.
 */
export function fileIdOk(datastoreId: string, raw: string): string | null {
  if (datastoreKind(datastoreId) === 'ENDPOINT') return UUID.test(raw) ? raw.toLowerCase() : null;
  return DOC_ID.test(raw) && !UUID.test(raw) ? raw : null;
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
 * The query from the URL. A value that fails its rule is absent: `file`, `filePage` and `fileQ` need
 * `datastore`, `eventQ` needs `file`; `datastorePage` and `datastoreQ` are always read. Any other key
 * (build 4's `category`, `policy`, …) is dropped.
 */
export function parsePublicQuery(params: URLSearchParams): PublicLineageQuery {
  const datastore = datastoreId(params.get('datastore'));
  const rawFile = params.get('file');
  const file = datastore !== null && rawFile !== null ? fileIdOk(datastore, rawFile) : null;
  return {
    datastore,
    file,
    page: {
      datastore: pageNumber(params.get('datastorePage')),
      file: datastore === null ? 0 : pageNumber(params.get('filePage')),
    },
    q: {
      datastore: searchText(params.get('datastoreQ')),
      file: datastore === null ? '' : searchText(params.get('fileQ')),
      event: file === null ? '' : searchText(params.get('eventQ')),
    },
  };
}

/**
 * The canonical URL query: `datastore, file, datastorePage, datastoreQ, filePage, fileQ, eventQ`, without
 * pages of 0 and empty searches. A parsed-and-written URL is therefore canonical.
 */
export function toPublicSearchParams(query: PublicLineageQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.datastore !== null) params.set('datastore', query.datastore);
  if (query.file !== null) params.set('file', query.file);
  for (const level of ['datastore', 'file'] as const) {
    if (query.page[level] > 0) params.set(`${level}Page`, String(query.page[level]));
    if (query.q[level] !== '') params.set(`${level}Q`, query.q[level]);
  }
  if (query.q.event !== '') params.set('eventQ', query.q.event);
  return params;
}

/** The deepest open level: the first level without a choice. */
export function publicOpenLevel(query: PublicLineageQuery): PublicOpenLevel {
  if (query.datastore === null) return 'datastore';
  if (query.file === null) return 'file';
  return 'event';
}

/** The query without the file choice, the file row's page and search, and the event search. */
const clearBelowDatastore = (query: PublicLineageQuery): PublicLineageQuery => ({
  ...query,
  file: null,
  page: { ...query.page, file: 0 },
  q: { ...query.q, file: '', event: '' },
});

/** The query without the event search. */
const clearBelowFile = (query: PublicLineageQuery): PublicLineageQuery => ({ ...query, q: { ...query.q, event: '' } });

/**
 * A click on a node (build 4's rules, with the root in the category's place):
 * - the root clears every choice, page and search when a datastore is chosen, and does nothing otherwise;
 * - an unchosen node is chosen, and everything below its row is cleared;
 * - the chosen node with a choice below it stays chosen, and everything below it is cleared;
 * - the deepest chosen node is unchosen; its row keeps its page and search.
 */
export function clickPublicNode(query: PublicLineageQuery, level: 'root' | PublicRowLevel, id: string): PublicLineageQuery {
  if (level === 'root') return query.datastore === null ? query : EMPTY_PUBLIC_QUERY;
  if (level === 'datastore') {
    const below = clearBelowDatastore(query);
    if (query.datastore !== id) return { ...below, datastore: id };
    if (query.file !== null) return below;
    return { ...below, datastore: null };
  }
  const below = clearBelowFile(query);
  if (query.file !== id) return { ...below, file: id };
  return { ...below, file: null };
}

/** The row at `page`, clamped to 0..MAX_PAGE. */
export function setPublicPage(query: PublicLineageQuery, level: PublicRowLevel, page: number): PublicLineageQuery {
  const clamped = Math.min(MAX_PAGE, Math.max(0, Math.trunc(page)));
  return { ...query, page: { ...query.page, [level]: clamped } };
}

/** A level's search text, normalised and cut to 200; a row's page goes back to 0. */
export function setPublicSearch(query: PublicLineageQuery, level: PublicOpenLevel, text: string): PublicLineageQuery {
  const next: PublicLineageQuery = { ...query, q: { ...query.q, [level]: searchText(text) } };
  if (level !== 'event') next.page = { ...query.page, [level]: 0 };
  return next;
}
