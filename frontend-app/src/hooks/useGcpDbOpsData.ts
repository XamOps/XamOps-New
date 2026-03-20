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

function readCache(projectId: string): CachedPayload | null {
  try {
    const raw = localStorage.getItem(`gcp_dbops_${projectId}`);
    if (!raw) return null;
    const payload: CachedPayload = JSON.parse(raw);
    if (Date.now() - payload.ts > CACHE_TTL) return null;
    return payload;
  } catch {
    return null;
  }
}

function writeCache(projectId: string, payload: Omit<CachedPayload, 'ts'>) {
  try {
    localStorage.setItem(`gcp_dbops_${projectId}`, JSON.stringify({ ...payload, ts: Date.now() }));
  } catch { /* storage full — ignore */ }
}

function clearCache(projectId: string) {
  localStorage.removeItem(`gcp_dbops_${projectId}`);
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

export function useGcpDbOpsData(projectId: string | null) {
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
  const prevProjectRef = useRef<string | null>(null);

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
    if (!projectId || fetchingRef.current) return;
    fetchingRef.current = true;
    setError(null);

    if (forceRefresh) {
      clearCache(projectId);
    } else {
      const cached = readCache(projectId);
      if (cached) {
        applyPayload(cached);
        setLoading(false);
        fetchingRef.current = false;
        return;
      }
    }

    setLoading(true);
    try {
      const base = `/api/dbops/gcp/${projectId}`;
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
        writeCache(projectId, payload);
      }
    } catch (e) {
      console.error('[GcpDbOps] Fetch error:', e);
      setError('Failed to load GCP DbOps data. Check your GCP project connection.');
    } finally {
      setLoading(false);
      fetchingRef.current = false;
    }
  }, [projectId]);

  useEffect(() => {
    if (!projectId) {
      setSummary(null); setInventory([]); setMetrics({}); setCosts([]); setIdle([]);
      setRightsizing([]); setSnapshots([]); setSecurity([]); setSlowQueries(null);
      setAgentQueryStats([]); setAgentInstanceMetrics([]);
      setLoading(false);
      return;
    }
    if (prevProjectRef.current !== projectId) {
      prevProjectRef.current = projectId;
      const cached = readCache(projectId);
      if (!cached) setLoading(true);
      fetchAll(false);
    }
  }, [projectId, fetchAll]);

  const refresh = useCallback(() => fetchAll(true), [fetchAll]);

  const fetchAgentTimeseries = useCallback(async (
    hours = 24
  ): Promise<AgentTimeseries[]> => {
    if (!projectId) return [];
    return fetchJson<AgentTimeseries[]>(
      `/api/dbops/gcp/${projectId}/agent-timeseries?hours=${hours}`
    ).then(r => r ?? []);
  }, [projectId]);

  const fetchAgentTableSizes = useCallback(async (): Promise<AgentTableSize[]> => {
    if (!projectId) return [];
    return fetchJson<AgentTableSize[]>(
      `/api/dbops/gcp/${projectId}/agent-table-sizes`
    ).then(r => r ?? []);
  }, [projectId]);

  const fetchAgentIndexUsage = useCallback(async (): Promise<AgentIndexUsage[]> => {
    if (!projectId) return [];
    return fetchJson<AgentIndexUsage[]>(
      `/api/dbops/gcp/${projectId}/agent-index-usage`
    ).then(r => r ?? []);
  }, [projectId]);

  const fetchAgentTableActivity = useCallback(async (): Promise<AgentTableActivity[]> => {
    if (!projectId) return [];
    return fetchJson<AgentTableActivity[]>(
      `/api/dbops/gcp/${projectId}/agent-table-activity`
    ).then(r => r ?? []);
  }, [projectId]);

  const fetchAgentVacuumStats = useCallback(async (): Promise<AgentVacuumStat[]> => {
    if (!projectId) return [];
    return fetchJson<AgentVacuumStat[]>(
      `/api/dbops/gcp/${projectId}/agent-vacuum-stats`
    ).then(r => r ?? []);
  }, [projectId]);

  const fetchAgentUserActivity = useCallback(async (): Promise<AgentUserActivity[]> => {
    if (!projectId) return [];
    return fetchJson<AgentUserActivity[]>(
      `/api/dbops/gcp/${projectId}/agent-user-activity`
    ).then(r => r ?? []);
  }, [projectId]);

  // GCP Cloud SQL does not expose per-instance CloudWatch-style timeseries yet;
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
