/**
 * The automation agent's three reads for Automation › Node autoscaler: its dashboard
 * (`GET /api/eks/autoscaling/{cluster}`: agent status and mode), its node fleet report
 * (`…/node-fleet`: idle nodes) and the last rebalance (`…/rebalance-status`). Read-only: the page
 * that uses this has no controls. Each read answers on its own, so one failure shows as "Not
 * checked" with its reason while the others still render. No polling; reads that answer after the
 * page unmounted, or after the cluster changed, are dropped.
 */
import { useEffect, useState } from 'react';
import { getAutomationDashboard, getNodeFleet, getRebalanceStatus } from '../api/k8sAutomationApi';
import type { AutomationDashboardDto, AutomationNodeFleet, AutomationRebalanceStatus } from '../types/k8sAutomation';

export type ReadState<T> = { kind: 'loading' } | { kind: 'ok'; data: T } | { kind: 'error'; message: string };

export interface NodeAutomationRead {
  dashboard: ReadState<AutomationDashboardDto>;
  fleet: ReadState<AutomationNodeFleet>;
  rebalance: ReadState<AutomationRebalanceStatus>;
}

const LOADING: NodeAutomationRead = { dashboard: { kind: 'loading' }, fleet: { kind: 'loading' }, rebalance: { kind: 'loading' } };

const messageOf = (e: unknown): string => (e instanceof Error && e.message ? e.message : "XamOps couldn't read it.");

export function useNodeAutomationRead(cluster: string | null, accountId: string | null, region: string | null): NodeAutomationRead {
  const [state, setState] = useState<NodeAutomationRead>(LOADING);

  useEffect(() => {
    if (!cluster || !accountId) {
      setState({
        dashboard: { kind: 'error', message: 'No cluster or account was given.' },
        fleet: { kind: 'error', message: 'No cluster or account was given.' },
        rebalance: { kind: 'error', message: 'No cluster or account was given.' },
      });
      return;
    }
    const controller = new AbortController();
    let live = true;
    setState(LOADING);
    const target = { cluster, accountId, region: region ?? undefined };
    const settle = <K extends keyof NodeAutomationRead>(key: K, promise: Promise<unknown>) => {
      promise
        .then((data) => {
          if (live) setState((prev) => ({ ...prev, [key]: { kind: 'ok', data } }));
        })
        .catch((e: unknown) => {
          if (!live || (e instanceof DOMException && e.name === 'AbortError')) return;
          setState((prev) => ({ ...prev, [key]: { kind: 'error', message: messageOf(e) } }));
        });
    };
    settle('dashboard', getAutomationDashboard(target, { signal: controller.signal }));
    settle('fleet', getNodeFleet(target, { signal: controller.signal }));
    settle('rebalance', getRebalanceStatus(target, { signal: controller.signal }));
    return () => {
      live = false;
      controller.abort();
    };
  }, [cluster, accountId, region]);

  return state;
}

export default useNodeAutomationRead;
