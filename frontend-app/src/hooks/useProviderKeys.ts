import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface ProviderKey {
  id: number;
  provider: string;
  keyHint: string;
  status: string; // ACTIVE, RATE_LIMITED, DISABLED
  currentRequests: number;
  totalRequests: number;
  totalCost: number;
  totalTokens: number;
  createdAt: string | null;
}

export function useGroupKeys(groupId: number | undefined) {
  const [keys, setKeys] = useState<ProviderKey[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchKeys = useCallback(async () => {
    if (!groupId) return;
    setLoading(true);
    try {
      const data = await apiFetchJson<ProviderKey[]>(
        `/api/xamops/aiops/gateway/key-groups/${groupId}/keys`
      );
      setKeys(data);
    } catch {
      setKeys([]);
    } finally {
      setLoading(false);
    }
  }, [groupId]);

  const addKey = useCallback(async (provider: string, apiKey: string) => {
    if (!groupId) return;
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${groupId}/keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, apiKey }),
    });
    await fetchKeys();
  }, [groupId, fetchKeys]);

  const rotateKey = useCallback(async (keyId: number, apiKey: string) => {
    if (!groupId) return;
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${groupId}/keys/${keyId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey }),
    });
    await fetchKeys();
  }, [groupId, fetchKeys]);

  const deleteKey = useCallback(async (keyId: number) => {
    if (!groupId) return;
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${groupId}/keys/${keyId}`, {
      method: 'DELETE',
    });
    await fetchKeys();
  }, [groupId, fetchKeys]);

  const updateKeyStatus = useCallback(async (keyId: number, status: string) => {
    if (!groupId) return;
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${groupId}/keys/${keyId}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    });
    await fetchKeys();
  }, [groupId, fetchKeys]);

  const updateStrategy = useCallback(async (strategy: string) => {
    if (!groupId) return;
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${groupId}/strategy`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ strategy }),
    });
  }, [groupId]);

  return { keys, loading, fetchKeys, addKey, rotateKey, deleteKey, updateKeyStatus, updateStrategy };
}
