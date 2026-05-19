import { useState, useCallback, useEffect } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface KeyGroup {
  id: number;
  name: string;
  description: string;
  groupKey: string;
  providerIds: number[];
  providerNames: string[];
  loadBalanceStrategy: string;
  keyCount: number;
  createdAt: string;
}

export function useKeyGroups() {
  const [groups, setGroups] = useState<KeyGroup[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchGroups = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetchJson<KeyGroup[]>('/api/xamops/aiops/gateway/key-groups');
      setGroups(data);
    } catch {
      setGroups([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const createGroup = useCallback(async (payload: { name: string; description: string; providerIds: number[] }) => {
    const data = await apiFetchJson<KeyGroup>('/api/xamops/aiops/gateway/key-groups', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    setGroups(prev => [...prev, data]);
    return data;
  }, []);

  const updateGroup = useCallback(async (id: number, payload: { name: string; description: string; providerIds: number[] }) => {
    const data = await apiFetchJson<KeyGroup>(`/api/xamops/aiops/gateway/key-groups/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    setGroups(prev => prev.map(g => g.id === id ? data : g));
    return data;
  }, []);

  const deleteGroup = useCallback(async (id: number) => {
    await apiFetchJson(`/api/xamops/aiops/gateway/key-groups/${id}`, { method: 'DELETE' });
    setGroups(prev => prev.filter(g => g.id !== id));
  }, []);

  useEffect(() => { fetchGroups(); }, [fetchGroups]);

  return { groups, loading, fetchGroups, createGroup, updateGroup, deleteGroup };
}
