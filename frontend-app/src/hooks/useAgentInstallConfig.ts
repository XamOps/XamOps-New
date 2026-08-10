import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';

/**
 * Environment-specific values used to render the agent install manifests in the
 * AKS / EKS / GKE setup guides.
 *
 * These used to be hardcoded to production in each modal, which meant a user on local
 * or UAT would copy a manifest pointing their cluster at prod.
 */
export interface AgentInstallConfig {
  /** Backend base URL for the running profile, e.g. https://live.xamops.com */
  serverUrl: string;
  /** Global agent API key for this environment */
  apiKey: string;
  /** False when serverUrl is loopback/private and unreachable from inside a cluster */
  reachableFromCluster: boolean;
}

/**
 * Falls back to production rather than leaving the guide blank if the request fails.
 * A guide showing a slightly wrong URL is recoverable; one showing `undefined` is not.
 */
const FALLBACK: AgentInstallConfig = {
  serverUrl: 'https://live.xamops.com',
  apiKey: 'xamops_SecureAgentKey2024XamOps',
  reachableFromCluster: true,
};

export function useAgentInstallConfig(enabled = true) {
  const [config, setConfig] = useState<AgentInstallConfig>(FALLBACK);
  const [loading, setLoading] = useState(false);
  const [usedFallback, setUsedFallback] = useState(false);
  const fetchedRef = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiClient.get<AgentInstallConfig>('/api/xamops/agent-install/config');
      if (data?.serverUrl) {
        setConfig({
          serverUrl: data.serverUrl,
          apiKey: data.apiKey || FALLBACK.apiKey,
          reachableFromCluster: data.reachableFromCluster !== false,
        });
        setUsedFallback(false);
      } else {
        setUsedFallback(true);
      }
    } catch (err) {
      console.warn('Failed to load agent install config; using production defaults', err);
      setUsedFallback(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Only fetch once, and only when the guide is actually opened.
    if (!enabled || fetchedRef.current) return;
    fetchedRef.current = true;
    load();
  }, [enabled, load]);

  return { config, loading, usedFallback };
}
