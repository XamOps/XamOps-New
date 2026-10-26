/**
 * The recorded Spot interruption history for Automation › Spot interruptions
 * (`GET /api/eks/autoscaling/{cluster}/spot-interruptions?days=&limit=`). Read-only: the page that
 * uses this has no controls but the window and Retry. No polling; a read that answers after the
 * page unmounted, or after the cluster or window changed, is dropped. `retry` reads it again.
 */
import { useCallback, useEffect, useState } from 'react';
import { getSpotInterruptions } from '../api/k8sAutomationApi';
import type { SpotInterruptionsDto } from '../types/k8sAutomation';
import type { ReadState } from './useNodeAutomationRead';

/** The backend's default and the page's table cap; the response says `truncated` when there are more. */
export const SPOT_INTERRUPTIONS_LIMIT = 200;

export interface SpotInterruptionsRead {
  read: ReadState<SpotInterruptionsDto>;
  retry: () => void;
}

const messageOf = (e: unknown): string => (e instanceof Error && e.message ? e.message : "XamOps couldn't read it.");

export function useSpotInterruptions(
  cluster: string | null,
  accountId: string | null,
  region: string | null,
  days: number,
): SpotInterruptionsRead {
  const [read, setRead] = useState<ReadState<SpotInterruptionsDto>>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!cluster || !accountId) {
      setRead({ kind: 'error', message: 'No cluster or account was given.' });
      return;
    }
    const controller = new AbortController();
    let live = true;
    setRead({ kind: 'loading' });
    getSpotInterruptions(
      { cluster, accountId, region: region ?? undefined },
      { days, limit: SPOT_INTERRUPTIONS_LIMIT },
      { signal: controller.signal },
    )
      .then((data) => {
        if (live) setRead({ kind: 'ok', data });
      })
      .catch((e: unknown) => {
        if (!live || (e instanceof DOMException && e.name === 'AbortError')) return;
        setRead({ kind: 'error', message: messageOf(e) });
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [cluster, accountId, region, days, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { read, retry };
}

export default useSpotInterruptions;
