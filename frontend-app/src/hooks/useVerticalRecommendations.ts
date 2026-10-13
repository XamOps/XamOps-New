/**
 * The vertical rightsizing engine's status for one cluster, read only
 * (docs/cloudk8s/ui-revamp-design.md §8.1, §11.2, D8; design-review NIT 3; plan item 24).
 *
 * No request at all unless capabilities have loaded, report `verticalEngine.enabled` and list the
 * cluster as registered: a capabilities failure is V5 (Retry reloads capabilities first, NIT 3), an
 * engine switched off is V7, an unlisted cluster V8. Otherwise endpoint 1 is read once per 60 s
 * (identity-keyed module cache, §15) and classified by `code`, never by status alone: a codeless 404
 * means the engine isn't deployed here (V6), `K8S_CLUSTER_NOT_REGISTERED` V8, a codeless 403 or
 * `K8S_NO_ACCOUNT_ACCESS` the page-level no-access message, anything else V5; a `200` gives V0, V2,
 * V3, V4 or V7 from the body. Writes and the actions feed (endpoint 4) come with FEAT-005.
 */
import { useCallback, useEffect, useState } from 'react';
import { classifyVerticalFailure, getVerticalStatus } from '../api/verticalRecommendationsApi';
import { toVerticalEngineView } from '../api/verticalRecommendationsAdapter';
import type { VerticalEngineView } from '../types/verticalRecommendations';
import { createK8sCache } from '../utils/k8sCache';
import { useK8sCapabilities } from './useK8sCapabilities';

export type VerticalReadState =
  | 'loading'
  | VerticalEngineView['state']
  /** The request failed, or capabilities did. */
  | 'V5'
  /** The engine's endpoints don't exist in this environment (codeless 404). */
  | 'V6'
  | 'V8'
  | 'no-access';

export interface VerticalRead {
  state: VerticalReadState;
  /** The engine's view after a `200`; null otherwise. */
  view: VerticalEngineView | null;
  /** Retries: capabilities first when they failed, else the engine read. */
  retry: () => void;
}

const statusCache = createK8sCache<VerticalEngineView>('vertical-status');

/** For tests: forget cached engine reads. */
export function clearVerticalCacheForTests(): void {
  statusCache.clear();
}

export function useVerticalRecommendations(clusterName: string, accountId: string, region: string): VerticalRead {
  const caps = useK8sCapabilities();
  const registered = caps.forCluster(clusterName)?.registered === true;
  const eligible = caps.status === 'ready' && caps.verticalEngineEnabled && registered;

  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ key: string; state: VerticalReadState; view: VerticalEngineView | null } | null>(null);
  const key = `${accountId}|${region}|${clusterName}|${attempt}`;

  useEffect(() => {
    if (!eligible) return undefined;
    const scope = { accountId, region, clusterName };
    const cached = statusCache.get(scope);
    if (cached) {
      setResult({ key, state: cached.state, view: cached });
      return undefined;
    }
    const controller = new AbortController();
    getVerticalStatus({ cluster: clusterName, accountId, region }, { signal: controller.signal })
      .then((wire) => {
        const view = toVerticalEngineView(wire);
        statusCache.set(scope, view);
        setResult({ key, state: view.state, view });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const failure = classifyVerticalFailure(error);
        const state: VerticalReadState = failure === 'not-deployed' ? 'V6'
          : failure === 'not-registered' ? 'V8'
            : failure === 'no-account-access' ? 'no-access' : 'V5';
        if (state === 'V5') console.warn('Vertical engine status failed', error);
        setResult({ key, state, view: null });
      });
    return () => controller.abort();
  }, [eligible, key, accountId, region, clusterName]);

  const { refetch } = caps;
  const retry = useCallback(() => {
    if (caps.status === 'error') {
      refetch();
      return;
    }
    statusCache.delete({ accountId, region, clusterName });
    setAttempt((n) => n + 1);
  }, [caps.status, refetch, accountId, region, clusterName]);

  if (caps.status === 'loading') return { state: 'loading', view: null, retry };
  if (caps.status === 'error') return { state: 'V5', view: null, retry };
  if (!caps.verticalEngineEnabled) return { state: 'V7', view: null, retry };
  if (!registered) return { state: 'V8', view: null, retry };
  if (!result || result.key !== key) return { state: 'loading', view: null, retry };
  return { state: result.state, view: result.view, retry };
}

export default useVerticalRecommendations;
