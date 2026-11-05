/**
 * The Manual mode queue for Automation › Pending actions
 * (`GET /api/eks/autoscaling/{cluster}/proposals`).
 *
 * Same contract as {@link useConsolidationRuns}: a read that answers after the page unmounted, or after
 * the cluster changed, is dropped; `retry` reads it again with the loading state. On top of that the
 * list refreshes every {@link REFRESH_MS} while the tab is visible, without blanking what is shown; a
 * failed background refresh keeps the rows already on screen. `refresh` is the same quiet re-read, for
 * after a decision.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { getProposals } from '../api/k8sAutomationApi';
import type { ProposalListDto } from '../types/automationProposals';
import type { ReadState } from './useNodeAutomationRead';

export const REFRESH_MS = 30_000;
/** The most rows one read asks for (the API's maximum); a longer queue is said so on the page. */
export const PAGE_LIMIT = 200;

export interface ProposalsRead {
  read: ReadState<ProposalListDto>;
  retry: () => void;
  refresh: () => void;
}

const messageOf = (e: unknown): string => (e instanceof Error && e.message ? e.message : "XamOps couldn't read it.");

export function useProposals(cluster: string | null, accountId: string | null, region: string | null): ProposalsRead {
  const [read, setRead] = useState<ReadState<ProposalListDto>>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [quiet, setQuiet] = useState(0);
  const inFlight = useRef<AbortController | null>(null);
  const hasData = useRef(false);

  const load = useCallback((blank: boolean) => {
    if (!cluster || !accountId) {
      setRead({ kind: 'error', message: 'No cluster or account was given.' });
      return () => {};
    }
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    let live = true;
    if (blank) {
      hasData.current = false;
      setRead({ kind: 'loading' });
    }
    getProposals({ cluster, accountId, region: region ?? undefined }, { limit: PAGE_LIMIT }, { signal: controller.signal })
      .then((data) => {
        if (!live) return;
        hasData.current = true;
        setRead({ kind: 'ok', data });
      })
      .catch((e: unknown) => {
        if (!live || (e instanceof DOMException && e.name === 'AbortError')) return;
        // A failed quiet refresh keeps the rows on screen.
        if (!blank && hasData.current) return;
        setRead({ kind: 'error', message: messageOf(e) });
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [cluster, accountId, region]);

  // The loading read: on mount, on a new cluster, and on Retry.
  useEffect(() => load(true), [load, attempt]);

  // Quiet re-reads after a decision.
  useEffect(() => {
    if (quiet === 0) return undefined;
    return load(false);
  }, [load, quiet]);

  // The 30 s refresh, only while the page is visible.
  useEffect(() => {
    const id = setInterval(() => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
      setQuiet((n) => n + 1);
    }, REFRESH_MS);
    return () => clearInterval(id);
  }, []);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  const refresh = useCallback(() => setQuiet((n) => n + 1), []);
  return { read, retry, refresh };
}

export default useProposals;
