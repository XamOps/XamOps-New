import { useState, useEffect, useCallback, useRef } from 'react';

// Re-export types from useDbOpsData for convenience
export type {
  DbInstance, DbMetrics, DbIdleInstance, DbRightsizing, DbCost, DbSnapshot,
  DbSecurityCheck, DbSavingsSummary, SlowQuery, SlowQueriesResponse,
  TimeseriesPoint, DbInstanceTimeseries, AgentInstanceMetrics, AgentTimeseriesSnapshot,
  AgentTableSize, AgentTimeseries, AgentQueryStat, AgentIndexUsage, AgentTableActivity,
  AgentVacuumStat, AgentUserActivity,
} from './useDbOpsData';

import type {
  DbInstance, DbMetrics, DbIdleInstance, DbRightsizing, DbCost, DbSnapshot,
  DbSecurityCheck, DbSavingsSummary, SlowQueriesResponse, DbInstanceTimeseries,
  AgentInstanceMetrics, AgentTableSize, AgentTimeseries, AgentQueryStat,
  AgentIndexUsage, AgentTableActivity, AgentVacuumStat, AgentUserActivity,
} from './useDbOpsData';

// ── Hook ─────────────────────────────────────────────────────────────────────

const CACHE_TTL = 12 * 60 * 60 * 1000; // 12 hours

interface CachedPayload {
  ts: number;
  summary: DbSavingsSummary | null;
  inventory: DbInstance[];
  metrics: Record<string, DbMetrics>;
  costs: DbCost[];
  idle: DbIdleInstance[];
  rightsizing: DbRightsizing[];
  snapshots: DbSnapshot[];
  security: DbSecurityCheck[];
  slowQueries: SlowQueriesResponse | null;
  agentQueryStats: AgentQueryStat[];
  agentInstanceMetrics: AgentInstanceMetrics[];
}

function readCache(subscriptionId: string): CachedPayload | null {
  try {
    const raw = localStorage.getItem(`azure_dbops_${subscriptionId}`);
    if (!raw) return null;
    const payload: CachedPayload = JSON.parse(raw);
    if (Date.now() - payload.ts > CACHE_TTL) return null;
    return payload;
  } catch {
    return null;
  }
}

function writeCache(subscriptionId: string, payload: Omit<CachedPayload, 'ts'>) {
  try {
    localStorage.setItem(`azure_dbops_${subscriptionId}`, JSON.stringify({ ...payload, ts: Date.now() }));
  } catch { /* storage full — ignore */ }
}

function clearCache(subscriptionId: string) {
  localStorage.removeItem(`azure_dbops_${subscriptionId}`);
}

async function fetchJson<T>(url: string): Promise<T | null> {
  try {
    const res = await fetch(url, { credentials: 'include', headers: { Accept: 'application/json' } });
    if (!res.ok) return null;
    return res.json() as Promise<T>;
  } catch {
    return null;
  }
}

export function useAzureDbOpsData(subscriptionId: string | null) {
  const [summary, setSummary]               = useState<DbSavingsSummary | null>(null);
  const [inventory, setInventory]           = useState<DbInstance[]>([]);
  const [metrics, setMetrics]               = useState<Record<string, DbMetrics>>({});
  const [costs, setCosts]                   = useState<DbCost[]>([]);
  const [idle, setIdle]                     = useState<DbIdleInstance[]>([]);
  const [rightsizing, setRightsizing]       = useState<DbRightsizing[]>([]);
  const [snapshots, setSnapshots]           = useState<DbSnapshot[]>([]);
  const [security, setSecurity]             = useState<DbSecurityCheck[]>([]);
  const [slowQueries, setSlowQueries]       = useState<SlowQueriesResponse | null>(null);
  const [agentQueryStats, setAgentQueryStats] = useState<AgentQueryStat[]>([]);
  const [agentInstanceMetrics, setAgentInstanceMetrics] = useState<AgentInstanceMetrics[]>([]);
  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState<string | null>(null);

  const fetchingRef = useRef(false);
  const prevSubscriptionRef = useRef<string | null>(null);

  const applyPayload = (p: Omit<CachedPayload, 'ts'>) => {
    setSummary(p.summary);
    setInventory(p.inventory);
    setMetrics(p.metrics);
    setCosts(p.costs);
    setIdle(p.idle);
    setRightsizing(p.rightsizing);
    setSnapshots(p.snapshots);
    setSecurity(p.security);
    setSlowQueries(p.slowQueries ?? null);
    setAgentQueryStats(p.agentQueryStats ?? []);
    setAgentInstanceMetrics(p.agentInstanceMetrics ?? []);
  };

  const fetchAll = useCallback(async (forceRefresh = false) => {
    if (!subscriptionId || fetchingRef.current) return;
    fetchingRef.current = true;
    setError(null);

    if (forceRefresh) {
      clearCache(subscriptionId);
    } else {
      const cached = readCache(subscriptionId);
      if (cached) {
        applyPayload(cached);
        setLoading(false);
        fetchingRef.current = false;
        return;
      }
    }

    setLoading(true);
    try {
      const base = `/api/dbops/azure/${subscriptionId}`;
      const qp   = `?forceRefresh=${forceRefresh}`;

      // Summary first — warms all backend caches
      const summaryData = await fetchJson<DbSavingsSummary>(`${base}/summary${qp}`);
      setSummary(summaryData);

      // All subsequent calls run in parallel
      const [inv, metricsData, cost, idleData, rsData, snapData, secData, slowData, agentStats, agentMetrics] = await Promise.all([
        fetchJson<DbInstance[]>(`${base}/inventory`),
        fetchJson<Record<string, DbMetrics>>(`${base}/metrics`),
        fetchJson<DbCost[]>(`${base}/costs`),
        fetchJson<DbIdleInstance[]>(`${base}/idle`),
        fetchJson<DbRightsizing[]>(`${base}/rightsizing`),
        fetchJson<DbSnapshot[]>(`${base}/snapshots`),
        fetchJson<DbSecurityCheck[]>(`${base}/security`),
        fetchJson<SlowQueriesResponse>(`${base}/slow-queries`),
        fetchJson<AgentQueryStat[]>(`${base}/agent-query-stats`),
        fetchJson<AgentInstanceMetrics[]>(`${base}/agent-instance-metrics`),
      ]);

      const payload = {
        summary:              summaryData,
        inventory:            inv          ?? [],
        metrics:              metricsData  ?? {},
        costs:                cost         ?? [],
        idle:                 idleData     ?? [],
        rightsizing:          rsData       ?? [],
        snapshots:            snapData     ?? [],
        security:             secData      ?? [],
        slowQueries:          slowData,
        agentQueryStats:      agentStats   ?? [],
        agentInstanceMetrics: agentMetrics ?? [],
      };

      applyPayload(payload);
      if (payload.summary !== null) {
        writeCache(subscriptionId, payload);
      }
    } catch (e) {
      console.error('[AzureDbOps] Fetch error:', e);
      setError('Failed to load Azure DbOps data. Check your Azure subscription connection.');
    } finally {
      setLoading(false);
      fetchingRef.current = false;
    }
  }, [subscriptionId]);

  useEffect(() => {
    if (!subscriptionId) {
      setSummary(null); setInventory([]); setMetrics({}); setCosts([]); setIdle([]);
      setRightsizing([]); setSnapshots([]); setSecurity([]); setSlowQueries(null);
      setAgentQueryStats([]); setAgentInstanceMetrics([]);
      setLoading(false);
      return;
    }
    if (prevSubscriptionRef.current !== subscriptionId) {
      prevSubscriptionRef.current = subscriptionId;
      const cached = readCache(subscriptionId);
      if (!cached) setLoading(true);
      fetchAll(false);
    }
  }, [subscriptionId, fetchAll]);

  const refresh = useCallback(() => fetchAll(true), [fetchAll]);

  const fetchAgentTimeseries = useCallback(async (
    hours = 24
  ): Promise<AgentTimeseries[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentTimeseries[]>(
      `/api/dbops/azure/${subscriptionId}/agent-timeseries?hours=${hours}`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  const fetchAgentTableSizes = useCallback(async (): Promise<AgentTableSize[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentTableSize[]>(
      `/api/dbops/azure/${subscriptionId}/agent-table-sizes`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  const fetchAgentIndexUsage = useCallback(async (): Promise<AgentIndexUsage[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentIndexUsage[]>(
      `/api/dbops/azure/${subscriptionId}/agent-index-usage`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  const fetchAgentTableActivity = useCallback(async (): Promise<AgentTableActivity[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentTableActivity[]>(
      `/api/dbops/azure/${subscriptionId}/agent-table-activity`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  const fetchAgentVacuumStats = useCallback(async (): Promise<AgentVacuumStat[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentVacuumStat[]>(
      `/api/dbops/azure/${subscriptionId}/agent-vacuum-stats`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  const fetchAgentUserActivity = useCallback(async (): Promise<AgentUserActivity[]> => {
    if (!subscriptionId) return [];
    return fetchJson<AgentUserActivity[]>(
      `/api/dbops/azure/${subscriptionId}/agent-user-activity`
    ).then(r => r ?? []);
  }, [subscriptionId]);

  // Azure SQL / PostgreSQL / MySQL does not expose per-instance CloudWatch-style timeseries yet;
  // returns null to indicate unavailable (detail drawer will hide timeseries tab)
  const fetchInstanceTimeseries = useCallback(async (
    _instanceId: string, _hours = 24
  ): Promise<DbInstanceTimeseries | null> => {
    return null;
  }, []);

  return {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    loading, error,
    refresh,
    fetchInstanceTimeseries,
    fetchAgentTimeseries,
    fetchAgentTableSizes,
    fetchAgentVacuumStats,
    fetchAgentUserActivity,
    fetchAgentIndexUsage,
    fetchAgentTableActivity,
  };
}
