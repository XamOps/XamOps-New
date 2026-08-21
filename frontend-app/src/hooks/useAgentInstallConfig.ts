import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import { useAccounts } from '../contexts/AccountContext';

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
  /**
   * Agent API key for the install command. Scoped to the selected cloud account when the
   * backend could issue one — see `keyScope`.
   */
  apiKey: string;
  /**
   * 'account' when apiKey is bound to the selected cloud account, 'legacy-global' when it is
   * the shared key every customer used to share. Callers can warn on the latter.
   */
  keyScope?: 'account' | 'legacy-global';
  /** False when serverUrl is loopback/private and unreachable from inside a cluster */
  reachableFromCluster: boolean;
}

/**
 * Falls back to production rather than leaving the guide blank if the request fails.
 * A guide showing a slightly wrong URL is recoverable; one showing `undefined` is not.
 *
 * The key is deliberately empty. It used to be the literal shared key, which is one of the
 * reasons that key stopped being a secret — it shipped in the frontend bundle. Commands render
 * a visible placeholder instead, so a failed fetch looks like a failed fetch rather than
 * silently handing out a credential that is being retired.
 */
const FALLBACK: AgentInstallConfig = {
  serverUrl: 'https://live.xamops.com',
  apiKey: '',
  reachableFromCluster: true,
};

/**
 * Resolves the install command's server URL and API key.
 *
 * The account is read from account context rather than passed in by each caller, so every guide
 * gets an account-scoped key without knowing that scoped keys exist. That is the point: the
 * customer copies one command that already contains the right key and never types one.
 */
export function useAgentInstallConfig(enabled = true) {
  const { selectedAccountIds } = useAccounts();
  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [config, setConfig] = useState<AgentInstallConfig>(FALLBACK);
  const [loading, setLoading] = useState(false);
  const [usedFallback, setUsedFallback] = useState(false);
  // Keyed by account so switching accounts re-fetches; a key from the previous account would
  // now be rejected by the backend rather than silently working.
  const fetchedForRef = useRef<string | null>(null);

  const load = useCallback(async (account: string | null) => {
    setLoading(true);
    try {
      const query = account ? `?accountId=${encodeURIComponent(account)}` : '';
      const data = await apiClient.get<AgentInstallConfig>(
        `/api/xamops/agent-install/config${query}`);
      if (data?.serverUrl) {
        setConfig({
          serverUrl: data.serverUrl,
          apiKey: data.apiKey || '',
          keyScope: data.keyScope,
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
    // Only when the guide is actually opened, and once per account.
    if (!enabled) return;
    const key = accountId ?? '__none__';
    if (fetchedForRef.current === key) return;
    fetchedForRef.current = key;
    void load(accountId);
  }, [enabled, accountId, load]);

  return { config, loading, usedFallback };
}
