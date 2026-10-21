/**
 * The HPAs' history (FEAT-004: replicas, bounds, CPU against its target, time at its maximum and
 * replica changes, from kube-state-metrics in the cluster), for Automation › HPA and its detail.
 * The list reads every HPA's with one GET (one cluster connection on the server); a detail, or a
 * list of one, reads its own. No polling; reads in flight are aborted when the HPAs change or the
 * page unmounts, and their answers dropped.
 */
import { useCallback, useEffect, useState } from 'react';
import { fetchHpaHistories, fetchHpaHistory } from '../api/hpaApi';
import type { HpaHistoryView, HpaHistoryWindow, InsightReadResult } from '../types/automationInsights';
import type { ClusterArgs } from './useAutomationUsage';

export interface HpaRef {
  namespace: string;
  name: string;
}

/** The page reads at most this many HPAs' histories at once; the rest say so. */
export const HPA_HISTORY_READ_LIMIT = 12;

export const hpaRefId = (hpa: HpaRef): string => `${hpa.namespace}/${hpa.name}`;

const ready = (args: ClusterArgs): args is { clusterName: string; accountId: string; region: string } =>
  !!args.clusterName && !!args.accountId && !!args.region;

export interface HpaHistoriesState {
  /** Each HPA's answer by `namespace/name`; absent while it loads or when it wasn't read. */
  results: Record<string, InsightReadResult<HpaHistoryView>>;
  /** The HPAs being read (the first {@link HPA_HISTORY_READ_LIMIT}). */
  read: ReadonlySet<string>;
  /** True when they are read with the list's one GET (more than one HPA). */
  batched: boolean;
  reload: () => void;
}

/** The HPAs' histories: one GET for several (the list), the one-HPA GET for one (a detail). */
export function useHpaHistories(args: ClusterArgs, hpas: readonly HpaRef[], window: HpaHistoryWindow = '72h'): HpaHistoriesState {
  const wanted = hpas.slice(0, HPA_HISTORY_READ_LIMIT);
  // Sorted: the order the page lists the HPAs in picks which are read, never whether to read again.
  const idsKey = ready(args) ? wanted.map(hpaRefId).sort().join('|') : '';
  const [token, setToken] = useState(0);
  const [state, setState] = useState<{ key: string; results: Record<string, InsightReadResult<HpaHistoryView>> }>({ key: '', results: {} });
  const stateKey = `${args.clusterName}|${args.accountId}|${args.region}|${window}|${idsKey}|${token}`;

  useEffect(() => {
    if (!idsKey || !ready(args)) return undefined;
    const controller = new AbortController();
    let live = true;
    setState({ key: stateKey, results: {} });
    const refs = idsKey.split('|').map((id) => {
      const slash = id.indexOf('/');
      return { namespace: id.slice(0, slash), name: id.slice(slash + 1) };
    });
    if (refs.length > 1) {
      fetchHpaHistories(args.clusterName, refs, args.accountId, args.region, window, controller.signal).then((results) => {
        if (live) setState((prev) => (prev.key === stateKey ? { key: prev.key, results: { ...prev.results, ...results } } : prev));
      });
    } else {
      const [hpa] = refs;
      fetchHpaHistory(args.clusterName, hpa, args.accountId, args.region, window, controller.signal).then((result) => {
        if (live) setState((prev) => (prev.key === stateKey ? { key: prev.key, results: { ...prev.results, [hpaRefId(hpa)]: result } } : prev));
      });
    }
    return () => {
      live = false;
      controller.abort();
    };
    // stateKey carries every input the reads depend on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stateKey]);

  const reload = useCallback(() => setToken((t) => t + 1), []);
  const read = new Set(idsKey ? idsKey.split('|') : []);
  return {
    results: state.key === stateKey ? state.results : {},
    read,
    batched: read.size > 1,
    reload,
  };
}

export default useHpaHistories;
