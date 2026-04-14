import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { ApiKeyStats, ApiKeyRotateRequest } from '../types/aiops';

export function useApiKeysData() {
  const [keys, setKeys] = useState<ApiKeyStats[]>([]);
  const [loading, setLoading] = useState(false);

  const loadKeys = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetchJson<ApiKeyStats[]>('/api/xamops/aiops/api-keys');
      setKeys(data);
    } catch (err) {
      console.error('Failed to load API key stats', err);
    } finally {
      setLoading(false);
    }
  }, []);

  const rotateKey = useCallback(async (id: number, req: ApiKeyRotateRequest) => {
    try {
      await apiFetchJson<void>(`/api/xamops/aiops/api-keys/${id}/rotate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      });
      await loadKeys();
    } catch (err) {
      console.error('Failed to rotate API key', err);
    }
  }, [loadKeys]);

  const updateUsageLimit = useCallback(async (id: number, limit: number | null) => {
    try {
      const url = limit != null
        ? `/api/xamops/aiops/api-keys/${id}/usage-limit?limit=${limit}`
        : `/api/xamops/aiops/api-keys/${id}/usage-limit`;
      await apiFetchJson<void>(url, { method: 'PUT' });
      setKeys(prev => prev.map(k => k.id === id ? { ...k, usageLimit: limit } : k));
    } catch (err) {
      console.error('Failed to update usage limit', err);
    }
  }, []);

  const cleanupOrphanedLogs = useCallback(async (): Promise<{ deleted: number; message: string }> => {
    const result = await apiFetchJson<{ deleted: number; message: string }>(
      '/api/xamops/aiops/cleanup-orphaned-logs',
      { method: 'POST' }
    );
    await loadKeys();
    return result;
  }, [loadKeys]);

  return { keys, loading, loadKeys, rotateKey, updateUsageLimit, cleanupOrphanedLogs };
}
