import { useState, useEffect, useCallback } from 'react';
import {
  WorkspaceStatus,
  WorkspaceConnectRequest,
  WorkspaceUserEntry,
  WorkspaceLicenseSummary,
  WorkspaceOptimizationItem,
  WorkspaceSyncResult,
  UserStatusFilter,
} from '../types/workspace';
import { apiFetchJson, apiFetch } from '../utils/apiClient';

// ─── Status / Connection ──────────────────────────────────────────────────────

export function useWorkspaceStatus() {
  const [status, setStatus] = useState<WorkspaceStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetchJson<WorkspaceStatus>('/api/workspace/status');
      setStatus(data);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load workspace status');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  return { status, loading, error, refetch: fetch };
}

// ─── Users ────────────────────────────────────────────────────────────────────

export function useWorkspaceUsers(connected: boolean, statusFilter: UserStatusFilter = 'ALL') {
  const [users, setUsers] = useState<WorkspaceUserEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!connected) { setUsers([]); return; }
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetchJson<WorkspaceUserEntry[]>(
        `/api/workspace/users?status=${encodeURIComponent(statusFilter)}`
      );
      setUsers(data);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, [connected, statusFilter]);

  useEffect(() => { fetch(); }, [fetch]);

  return { users, loading, error, refetch: fetch };
}

// ─── License Summary ─────────────────────────────────────────────────────────

export function useWorkspaceLicenseSummary(connected: boolean) {
  const [summary, setSummary] = useState<WorkspaceLicenseSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!connected) { setSummary(null); return; }
    setLoading(true);
    apiFetchJson<WorkspaceLicenseSummary>('/api/workspace/licenses/summary')
      .then(setSummary)
      .catch((e: any) => setError(e.message ?? 'Failed to load license summary'))
      .finally(() => setLoading(false));
  }, [connected]);

  return { summary, loading, error };
}

// ─── Optimization ─────────────────────────────────────────────────────────────

export function useWorkspaceOptimization(connected: boolean, inactiveDays: number = 30) {
  const [items, setItems] = useState<WorkspaceOptimizationItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!connected) { setItems([]); return; }
    setLoading(true);
    apiFetchJson<WorkspaceOptimizationItem[]>(
      `/api/workspace/optimization?inactiveDays=${inactiveDays}`
    )
      .then(setItems)
      .catch((e: any) => setError(e.message ?? 'Failed to load optimization'))
      .finally(() => setLoading(false));
  }, [connected, inactiveDays]);

  return { items, loading, error };
}

// ─── Actions ─────────────────────────────────────────────────────────────────

export async function connectWorkspace(req: WorkspaceConnectRequest): Promise<WorkspaceStatus> {
  return apiFetchJson<WorkspaceStatus>('/api/workspace/connect', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export async function syncWorkspace(): Promise<WorkspaceSyncResult> {
  return apiFetchJson<WorkspaceSyncResult>('/api/workspace/sync', { method: 'POST' });
}

export async function disconnectWorkspace(): Promise<void> {
  const res = await apiFetch('/api/workspace/disconnect', { method: 'DELETE' });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Disconnect failed: ${text}`);
  }
}
