import { useState, useCallback, useEffect } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface GatewayStatus {
  online: boolean;
  url: string;
  proxyEndpoint: string;
}

export function useGatewayData() {
  const [status, setStatus] = useState<GatewayStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetchJson<GatewayStatus>('/api/xamops/aiops/gateway/status');
      setStatus(data);
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

  return { status, loading, syncing, fetchStatus, syncGateway };
}
