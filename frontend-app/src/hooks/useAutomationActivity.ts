/**
 * Loads one cluster's activity feed (FEAT-007) for Automation › Activity and a workload's recent
 * changes: the first page for the current filters, then older pages on request (`loadOlder`, keyset
 * cursor). No polling; the next read is a user action. A read that answers after the page unmounted,
 * or after the cluster or the filters changed, is dropped.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { activitySummaryUrl, fetchActivity, fetchActivitySummary, rangeFrom } from '../api/automationActivityApi';
import type {
  ActivityEntryView,
  ActivityFeatureWire,
  ActivityReadResult,
  ActivityResultWire,
  ActivitySummaryView,
} from '../types/automationActivity';
import { useInsightRead, type InsightReadState } from './useInsightRead';

export interface ActivityFilters {
  features: readonly ActivityFeatureWire[];
  results: readonly ActivityResultWire[];
  /** Part of a workload or HPA name. */
  q: string;
  /** Exactly one workload or HPA: its own entries, never those of a neighbour whose name contains its own. */
  target?: { namespace: string; kind: string; name: string } | null;
  /** Entries at or after this instant (ISO-8601, UTC); the caller fixes it, so it doesn't move every render. */
  from?: string | null;
  /**
   * Entries in the last `since` ms instead: turned into the server's `from` when each request is sent
   * (the first page, Load older and Retry alike), so a page left open never asks for more than the
   * server's 90 days. Ignored when `from` is given.
   */
  since?: number | null;
}

export const NO_ACTIVITY_FILTERS: ActivityFilters = { features: [], results: [], q: '' };

export interface UseAutomationActivityArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
  filters?: ActivityFilters;
  limit?: number;
}

export interface UseAutomationActivityState {
  /** True while the first page for the current filters is being read. */
  loading: boolean;
  /** The first page's answer; null until it arrives, or while the cluster is missing. */
  result: ActivityReadResult | null;
  /** Every entry loaded so far for the current filters, newest first. */
  entries: ActivityEntryView[];
  /** True when the server has an older page. */
  hasOlder: boolean;
  loadingOlder: boolean;
  /** An older page that failed to load, in words; the loaded entries stay. */
  olderError: string | null;
  loadOlder: () => void;
  reload: () => void;
}

const failureText = (result: ActivityReadResult): string => {
  switch (result.kind) {
    case 'unauthenticated':
      return 'Your session has expired. Sign in again to load older entries.';
    case 'not-deployed':
      return 'This backend has no activity feed.';
    case 'ok':
      return '';
    default:
      return result.message;
  }
};

export const useAutomationActivity = ({
  clusterName,
  accountId,
  region,
  filters = NO_ACTIVITY_FILTERS,
  limit,
}: UseAutomationActivityArgs): UseAutomationActivityState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ActivityReadResult | null>(null);
  const [entries, setEntries] = useState<ActivityEntryView[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  // Bumped whenever the first page is re-read, so an older page from the previous filters is dropped.
  const generation = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const featureKey = filters.features.join(',');
  const resultKey = filters.results.join(',');
  const q = filters.q.trim();
  const from = filters.from ?? null;
  const since = filters.since ?? null;
  // The server's `from` for a request sent now.
  const fromNow = (): string | null => from ?? rangeFrom(since, Date.now());
  const targetNamespace = filters.target?.namespace ?? null;
  const targetKind = filters.target?.kind ?? null;
  const targetName = filters.target?.name ?? null;
  const target = targetNamespace !== null && targetKind !== null && targetName !== null
    ? { namespace: targetNamespace, kind: targetKind, name: targetName }
    : null;

  useEffect(() => {
    generation.current += 1;
    const mine = generation.current;
    setEntries([]);
    setCursor(null);
    setOlderError(null);
    setLoadingOlder(false);
    if (!clusterName || !accountId || !region) {
      setResult(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    fetchActivity(clusterName, accountId, region, {
      features: featureKey ? (featureKey.split(',') as ActivityFeatureWire[]) : [],
      results: resultKey ? (resultKey.split(',') as ActivityResultWire[]) : [],
      q,
      target,
      from: fromNow(),
      limit,
    })
      .then((next) => {
        if (!mounted.current || generation.current !== mine) return;
        setResult(next);
        if (next.kind === 'ok') {
          setEntries(next.page.entries);
          setCursor(next.page.nextCursor);
        }
      })
      .finally(() => {
        if (mounted.current && generation.current === mine) setLoading(false);
      });
    // target is rebuilt from its three strings, and fromNow from `from` and `since`, which are the dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clusterName, accountId, region, featureKey, resultKey, q, targetNamespace, targetKind, targetName, from, since, limit, reloadToken]);

  const loadOlder = useCallback(() => {
    if (!clusterName || !accountId || !region || !cursor || loadingOlder) return;
    const mine = generation.current;
    setLoadingOlder(true);
    setOlderError(null);
    fetchActivity(clusterName, accountId, region, {
      features: featureKey ? (featureKey.split(',') as ActivityFeatureWire[]) : [],
      results: resultKey ? (resultKey.split(',') as ActivityResultWire[]) : [],
      q,
      target,
      from: fromNow(),
      limit,
      cursor,
    })
      .then((next) => {
        if (!mounted.current || generation.current !== mine) return;
        if (next.kind === 'ok') {
          // Keyset pages never overlap, but a row id seen twice would be a server bug: keep the first.
          setEntries((prev) => {
            const seen = new Set(prev.map((e) => e.id));
            return [...prev, ...next.page.entries.filter((e) => !seen.has(e.id))];
          });
          setCursor(next.page.nextCursor);
        } else {
          setOlderError(failureText(next));
        }
      })
      .finally(() => {
        if (mounted.current && generation.current === mine) setLoadingOlder(false);
      });
    // target is rebuilt from its three strings, and fromNow from `from` and `since`, which are the dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clusterName, accountId, region, cursor, loadingOlder, featureKey, resultKey, q, targetNamespace, targetKind, targetName, from, since, limit]);

  const reload = useCallback(() => setReloadToken((t) => t + 1), []);

  return { loading, result, entries, hasOlder: cursor !== null, loadingOlder, olderError, loadOlder, reload };
};

/**
 * The activity summary (FEAT-005): changes per IST day by result, over the last `days` days. One
 * GET; no polling; aborted when the cluster changes or the page unmounts.
 */
export function useActivitySummary(
  { clusterName, accountId, region }: Pick<UseAutomationActivityArgs, 'clusterName' | 'accountId' | 'region'>,
  days = 7,
  features: readonly ActivityFeatureWire[] = [],
): InsightReadState<ActivitySummaryView> {
  const key = clusterName && accountId && region ? activitySummaryUrl(clusterName, accountId, region, days, features) : null;
  return useInsightRead(key, (signal) => fetchActivitySummary(clusterName!, accountId!, region!, days, features, signal));
}

export default useAutomationActivity;
