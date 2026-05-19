import { useState, useCallback, useEffect } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface GatewayStatus {
  online: boolean;
  url: string;
  proxyEndpoint: string;
}

export function useGatewayData() {
  const [status, setStatus] = useState<GatewayStatus | null>(null);
  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const [statusData, keyData] = await Promise.all([
        apiFetchJson<GatewayStatus>('/api/xamops/aiops/gateway/status'),
        apiFetchJson<{ masterKey: string }>('/api/xamops/aiops/gateway/master-key'),
      ]);
      setStatus(statusData);
      setMasterKey(keyData.masterKey);
    } catch {
      setStatus({ online: false, url: '', proxyEndpoint: '' });
    } finally {
      setLoading(false);
    }
  }, []);

  const syncGateway = useCallback(async () => {
    setSyncing(true);
    try {
      await apiFetchJson('/api/xamops/aiops/gateway/sync', { method: 'POST' });
      await fetchStatus();
    } catch (err) {
      console.error('Gateway sync failed', err);
    } finally {
      setSyncing(false);
    }
  }, [fetchStatus]);

  useEffect(() => { fetchStatus(); }, [fetchStatus]);

  return { status, masterKey, loading, syncing, fetchStatus, syncGateway };
}
