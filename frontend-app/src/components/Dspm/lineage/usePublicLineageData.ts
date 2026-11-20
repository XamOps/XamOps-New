import { useEffect, useMemo, useRef } from 'react';
import type {
  PublicFileLineage, PublicLineageDatastorePage, PublicLineageFile, PublicLineageFilePage,
} from '../../../types/dspmLineage';
import { lineageApi, useKeyedResource, type KeyedResource } from '../../../hooks/useDspmLineage';
import { FILE_SEARCH_LIMIT, PAGE_SIZE, SEARCH_LIMIT } from './lineageQuery';
import { setPublicPage, type PublicLineageQuery, type PublicRowLevel } from './publicLineageQuery';

export interface PublicLineageData {
  datastores: KeyedResource<PublicLineageDatastorePage>;
  files: KeyedResource<PublicLineageFilePage>;
  /** The file nodes to draw: the loaded page, or a search's matches among the first 100; null until loaded. */
  fileRows: PublicLineageFile[] | null;
  lineage: KeyedResource<PublicFileLineage>;
}

/** A row's request window: a search asks for its first `searchLimit` rows, else the page of 7. */
const rowWindow = (q: string, page: number, searchLimit: number) =>
  q !== '' ? { skip: 0, limit: searchLimit } : { skip: page * PAGE_SIZE, limit: PAGE_SIZE };

/**
 * Every request of the public path (build 7 design 6.5), as build 4's `useLineageData`: each resource's
 * key is the JSON of exactly its request's inputs. The datastores are always requested (a search sends
 * `q` with the first 50); the files once a datastore is chosen (a search fetches the first 100 and filters
 * them by name in the browser); the lineage once a file is chosen. A row that comes back empty on a page
 * above 0 without a search (a stale URL) goes back to page 0 through `onQuery`.
 */
export function usePublicLineageData(
  query: PublicLineageQuery,
  onQuery: (query: PublicLineageQuery) => void,
): PublicLineageData {
  const { datastore, file, page, q } = query;

  const datastoreWindow = rowWindow(q.datastore, page.datastore, SEARCH_LIMIT);
  const datastoreQ = q.datastore || null;
  const datastores = useKeyedResource<PublicLineageDatastorePage>(
    JSON.stringify(['public-datastores', datastoreQ, datastoreWindow]),
    () => lineageApi.publicDatastores({ q: datastoreQ, ...datastoreWindow }),
  );

  const fileWindow = rowWindow(q.file, page.file, FILE_SEARCH_LIMIT);
  const files = useKeyedResource<PublicLineageFilePage>(
    datastore === null ? null : JSON.stringify(['public-files', datastore, fileWindow]),
    () => lineageApi.publicFiles(datastore!, fileWindow.skip, fileWindow.limit),
  );

  const lineage = useKeyedResource<PublicFileLineage>(
    datastore === null || file === null ? null : JSON.stringify(['public-lineage', datastore, file]),
    () => lineageApi.publicFileLineage(datastore!, file!),
  );

  const fileRows = useMemo(() => {
    if (!files.data) return null;
    const needle = q.file.toLowerCase();
    if (needle === '') return files.data.rows;
    return files.data.rows.filter(row => row.name?.toLowerCase().includes(needle));
  }, [files.data, q.file]);

  // The callback and the query as they are when an answer arrives.
  const latest = useRef({ query, onQuery });
  useEffect(() => { latest.current = { query, onQuery }; });

  const stale = (level: PublicRowLevel, data: { rows: unknown[] } | null) =>
    data !== null && data.rows.length === 0 && page[level] > 0 && q[level] === '';
  const staleDatastore = stale('datastore', datastores.data);
  const staleFile = stale('file', files.data);
  useEffect(() => {
    let next = latest.current.query;
    if (staleDatastore) next = setPublicPage(next, 'datastore', 0);
    if (staleFile) next = setPublicPage(next, 'file', 0);
    if (next !== latest.current.query) latest.current.onQuery(next);
  }, [staleDatastore, staleFile]);

  return { datastores, files, fileRows, lineage };
}
