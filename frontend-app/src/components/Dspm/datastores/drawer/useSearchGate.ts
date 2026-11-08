import { useCallback, useRef } from 'react';

/*
 * One Data Flow table search at a time (design 6.11). A table request with `q` can make the backend read
 * up to 100 vendor pages, and nothing stops that read when the browser drops the response, so a search
 * waits for the pending one and only the newest waiting search is kept. Requests without `q` bypass it.
 */

export interface TableRequest { parentKey: string /* 's:<schemaId>' or 'd:<databaseId>' */; q: string; page: number; pageSize: number }
export const sameRequest = (a: TableRequest, b: TableRequest) =>
  a.parentKey === b.parentKey && a.q === b.q && a.page === b.page && a.pageSize === b.pageSize;

/** At most one search request in flight; while one is pending only the newest is remembered. */
export function useSearchGate(send: (r: TableRequest) => Promise<void>, isWanted: (r: TableRequest) => boolean) {
  const inFlight = useRef(false);
  const queued = useRef<TableRequest | null>(null);
  const run: (r: TableRequest) => void = useCallback((r: TableRequest) => {
    if (inFlight.current) { queued.current = r; return; }
    inFlight.current = true;
    void send(r).finally(() => {
      inFlight.current = false;
      const next = queued.current;
      queued.current = null;
      if (next !== null && !sameRequest(next, r) && isWanted(next)) run(next);
    });
  }, [send, isWanted]);
  return run;
}
