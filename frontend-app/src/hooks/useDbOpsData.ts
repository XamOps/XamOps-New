import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';

// ── Types matching backend DTOs ──────────────────────────────────────────────

export interface DbInstance {
  instanceId: string;
  instanceClass: string;
  engine: string;
  engineVersion: string;
  status: string;
  region: string;
  multiAz: string;
  storageType: string;
  allocatedStorage: number;
  publiclyAccessible: boolean;
  storageEncrypted: boolean;
  backupEnabled: boolean;
  backupRetentionDays: string;
  endpoint: string;
  estimatedMonthlyCost: number;
  maxConnections: number;
}

export interface DbMetrics {
  instanceId: string;
  region: string;
  avgCpuPercent: number;
  avgConnections: number;
  avgFreeableMemoryMb: number;
  avgReadIops: number;
  avgWriteIops: number;
  // Datadog-equivalent metrics
  avgReadLatencyMs: number;
  avgWriteLatencyMs: number;
  avgDiskQueueDepth: number;
  freeStorageSpaceGb: number;
  replicaLagSeconds: number;
  avgNetworkReceiveMbps: number;
  avgNetworkTransmitMbps: number;
  avgSwapUsageMb: number;
  burstBalance: number;
}

export interface DbIdleInstance {
  instanceId: string;
  instanceClass: string;
  engine: string;
  region: string;
  avgCpuPercent: number;
  avgConnections: number;
  estimatedMonthlyCost: number;
  potentialSavings: number;
  recommendation: string;
}

export interface DbRightsizing {
  instanceId: string;
  currentInstanceClass: string;
  recommendedInstanceClass: string;
  engine: string;
  region: string;
  avgCpuPercent: number;
  avgFreeableMemoryMb: number;
  currentMonthlyCost: number;
  recommendedMonthlyCost: number;
  estimatedSavings: number;
  reason: string;
}

export interface DbCost {
  service: string;
  region: string;
  cost: number;
  percentOfTotal: number;
}

export interface DbSnapshot {
  snapshotId: string;
  instanceId: string;
  region: string;
  ageDays: number;
  sizeGb: number;
  estimatedMonthlyCost: number;
  recommendation: string;
}

export interface DbSecurityCheck {
  instanceId: string;
  region: string;
  checkName: string;
  severity: 'HIGH' | 'MEDIUM' | 'INFO';
  status: 'PASS' | 'FAIL' | 'WARN';
  details: string;
}

export interface DbSavingsSummary {
  totalDbMonthlyCost: number;
  totalInstances: number;
  idleInstances: number;
  idleSavingsPotential: number;
  rightsizingCandidates: number;
  rightsizingSavingsPotential: number;
  snapshotWasteCost: number;
  totalPotentialSavings: number;
  savingsPercentage: number;
}

export interface SlowQuery {
  instanceId: string;
  region: string;
  queryTimeSeconds: number;
  lockTimeSeconds: number;
  rowsExamined: number;
  rowsSent: number;
  query: string;
  queryTimestamp: string;
}

export interface SlowQueriesResponse {
  logsEnabled: boolean;
  message: string | null;
  queries: SlowQuery[];
}

export interface TimeseriesPoint { ts: number; v: number; }

export interface DbInstanceTimeseries {
  instanceId: string;
  region: string;
  cpu: TimeseriesPoint[];
  connections: TimeseriesPoint[];
  readIops: TimeseriesPoint[];
  writeIops: TimeseriesPoint[];
  readLatencyMs: TimeseriesPoint[];
  writeLatencyMs: TimeseriesPoint[];
  freeStorageGb: TimeseriesPoint[];
  freeableMemoryMb: TimeseriesPoint[];
  networkReceiveMbps: TimeseriesPoint[];
  networkTransmitMbps: TimeseriesPoint[];
  replicaLagSeconds?: TimeseriesPoint[];
}

export interface AgentInstanceMetrics {
  agentName: string;
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  dbName?: string;
  region?: string;
  dbSizeBytes?: number;
  ec2InstanceType?: string;
  ec2InstanceId?: string;
  ec2HourlyPrice?: number;
  currentConnections: number;
  maxConnections: number;
  maxUsedConnections: number;
  totalQueries: number;
  totalSlowQueries: number;
  comSelect: number;
  comInsert: number;
  comUpdate: number;
  comDelete: number;
  innodbBufferPoolReads: number;
  innodbBufferPoolHitPct: number;
  innodbRowLockWaits: number;
  innodbDeadlocks: number;
  threadsRunning: number;
  bytesReceived: number;
  bytesSent: number;
  uptime: number;
  version: string;
  collectedAt: string;
}

export interface AgentTimeseriesSnapshot {
  ts: string;
  connections: number;
  maxConnections: number;
  bufferPoolHitPct: number;
  totalQueries: number;
  totalSlowQueries: number;
  comSelect: number;
  comInsert: number;
  comUpdate: number;
  comDelete: number;
  innodbRowLockWaits: number;
  innodbDeadlocks: number;
  threadsRunning: number;
}

export interface AgentTableSize {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  schemaName: string;
  tableName: string;
  dataSizeBytes: number;
  indexSizeBytes: number;
  totalSizeBytes: number;
  rows: number;
  collectedAt: string;
}

export interface AgentTimeseries {
  agentName: string;
  dbHost: string;
  snapshots: AgentTimeseriesSnapshot[];
}

export interface AgentQueryStat {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  queryDigest: string;
  callCount: number;
  totalTimeSeconds: number;
  avgTimeSeconds: number;
  maxTimeSeconds: number;
  rowsExamined: number;
  rowsSent: number;
  sharedBlksHit: number;
  sharedBlksRead: number;
  noIndexUsed: boolean;
  lockTimeSeconds: number;
  collectedAt?: string;
}

export interface AgentIndexUsage {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  schemaName: string;
  tableName: string;
  indexName: string;
  idxScan: number;
  idxTupRead: number;
  idxTupFetch: number;
  isUnused: boolean;
  indexSizeBytes: number;
  collectedAt?: string;
}

export interface AgentTableActivity {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  schemaName: string;
  tableName: string;
  seqScan: number;
  seqTupRead: number;
  idxScan: number;
  idxTupFetch: number;
  nTupIns: number;
  nTupUpd: number;
  nTupDel: number;
  nTupHotUpd: number;
  collectedAt?: string;
}

export interface AgentVacuumStat {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  schemaName: string;
  tableName: string;
  nLiveTup: number;
  nDeadTup: number;
  deadTupRatio: number;       // fraction 0–1
  lastVacuum?: string;
  lastAutoVacuum?: string;
  lastAnalyze?: string;
  lastAutoAnalyze?: string;
  collectedAt?: string;
}

export interface AgentUserActivity {
  dsnAlias: string;
  dbEngine: string;
  dbHost: string;
  username: string;
  activeConnections: number;
  idleConnections: number;
  totalConnections: number;
  callCount: number;
  totalTimeSeconds: number;
  collectedAt?: string;
}

export interface DbHealthSnapshotDto {
  instanceId: string;
  score: number;
  status: 'healthy' | 'warning' | 'critical';
  issues: string[];
  computedAt: string;
}

export interface DbHealthAlertDto {
  id: number;
  instanceId: string;
  alertType: string;
  severity: 'WARNING' | 'CRITICAL';
  message: string;
  triggeredAt: string;
  resolvedAt: string | null;
  active: boolean;
}

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
  healthSnapshots: DbHealthSnapshotDto[];
  dbAlerts: DbHealthAlertDto[];
}

function readCache(accountId: string): CachedPayload | null {
  try {
    const raw = localStorage.getItem(`dbops_${accountId}`);
    if (!raw) return null;
    const payload: CachedPayload = JSON.parse(raw);
    if (Date.now() - payload.ts > CACHE_TTL) return null;
    return payload;
  } catch {
    return null;
  }
}

function writeCache(accountId: string, payload: Omit<CachedPayload, 'ts'>) {
  try {
    localStorage.setItem(`dbops_${accountId}`, JSON.stringify({ ...payload, ts: Date.now() }));
  } catch { /* storage full — ignore */ }
}

function clearCache(accountId: string) {
  localStorage.removeItem(`dbops_${accountId}`);
}

// Throws on a non-ok response or a network error so a genuine failure is distinguishable from a
// successful-but-empty payload. The caller's try/catch sets the `error` state; a succeeded scan
// with zero rows resolves normally to an empty list (COR-13 / UX-2).
async function fetchJson<T>(url: string): Promise<T | null> {
  const res = await fetch(url, { credentials: 'include', headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`DbOps request failed (${res.status}): ${url}`);
  return res.json() as Promise<T>;
}

export function useDbOpsData(accountId: string | null) {
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
  const [healthSnapshots, setHealthSnapshots] = useState<DbHealthSnapshotDto[]>([]);
  const [dbAlerts, setDbAlerts]               = useState<DbHealthAlertDto[]>([]);
  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState<string | null>(null);

  const fetchingRef = useRef(false);
  const prevAccountRef = useRef<string | null>(null);

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
    setHealthSnapshots(p.healthSnapshots ?? []);
    setDbAlerts(p.dbAlerts ?? []);
  };

  const fetchAll = useCallback(async (forceRefresh = false) => {
    if (!accountId || fetchingRef.current) return;
    fetchingRef.current = true;
    setError(null);

    if (forceRefresh) {
      clearCache(accountId);
    } else {
      const cached = readCache(accountId);
      if (cached) {
        applyPayload(cached);
        setLoading(false);
        fetchingRef.current = false;
        return;
      }
    }

    setLoading(true);
    try {
      const base = `/api/dbops/aws/${accountId}`;
      const qp   = `?forceRefresh=${forceRefresh}`;

      // Summary first — this warms all backend caches
      const summaryData = await fetchJson<DbSavingsSummary>(`${base}/summary${qp}`);
      setSummary(summaryData);

      // All subsequent calls run in parallel
      const [inv, metricsData, cost, idleData, rsData, snapData, secData, slowData, agentStats, agentMetrics, healthSnaps, alertsData] = await Promise.all([
        fetchJson<DbInstance[]>(`${base}/inventory${qp}`),
        fetchJson<Record<string, DbMetrics>>(`${base}/metrics${qp}`),
        fetchJson<DbCost[]>(`${base}/costs${qp}`),
        fetchJson<DbIdleInstance[]>(`${base}/idle${qp}`),
        fetchJson<DbRightsizing[]>(`${base}/rightsizing${qp}`),
        fetchJson<DbSnapshot[]>(`${base}/snapshots${qp}`),
        fetchJson<DbSecurityCheck[]>(`${base}/security${qp}`),
        fetchJson<SlowQueriesResponse>(`${base}/slow-queries${qp}`),
        fetchJson<AgentQueryStat[]>(`${base}/agent-query-stats`),
        fetchJson<AgentInstanceMetrics[]>(`${base}/agent-instance-metrics`),
        fetchJson<DbHealthSnapshotDto[]>(`${base}/health-snapshots`),
        fetchJson<DbHealthAlertDto[]>(`${base}/db-alerts`),
      ]);

      const payload = {
        summary:         summaryData,
        inventory:       inv          ?? [],
        metrics:         metricsData  ?? {},
        costs:           cost         ?? [],
        idle:            idleData     ?? [],
        rightsizing:     rsData       ?? [],
        snapshots:       snapData     ?? [],
        security:        secData      ?? [],
        slowQueries:          slowData,
        agentQueryStats:      agentStats   ?? [],
        agentInstanceMetrics: agentMetrics ?? [],
        healthSnapshots:      healthSnaps  ?? [],
        dbAlerts:             alertsData   ?? [],
      };

      applyPayload(payload);
      // Only cache if we got real data — don't persist empty/failed responses
      if (payload.summary !== null) {
        writeCache(accountId, payload);
      }
    } catch (e) {
      console.error('[DbOps] Fetch error:', e);
      setError('Failed to load DbOps data. Check your AWS account connection.');
    } finally {
      setLoading(false);
      fetchingRef.current = false;
    }
  }, [accountId]);

  useEffect(() => {
    if (!accountId) {
      setSummary(null); setInventory([]); setMetrics({}); setCosts([]); setIdle([]);
      setRightsizing([]); setSnapshots([]); setSecurity([]); setSlowQueries(null);
      setAgentQueryStats([]); setAgentInstanceMetrics([]);
      setHealthSnapshots([]); setDbAlerts([]);
      setLoading(false);
      return;
    }
    if (prevAccountRef.current !== accountId) {
      prevAccountRef.current = accountId;
      const cached = readCache(accountId);
      if (!cached) setLoading(true);
      fetchAll(false);
    }
  }, [accountId, fetchAll]);

  const refresh = useCallback(() => fetchAll(true), [fetchAll]);

  const fetchInstanceTimeseries = useCallback(async (
    instanceId: string, hours = 24
  ): Promise<DbInstanceTimeseries | null> => {
    if (!accountId) return null;
    return fetchJson<DbInstanceTimeseries>(
      `/api/dbops/aws/${accountId}/instance/${encodeURIComponent(instanceId)}/timeseries?hours=${hours}`
    ).catch(() => null);
  }, [accountId]);

  const fetchAgentTimeseries = useCallback(async (
    hours = 24
  ): Promise<AgentTimeseries[]> => {
    if (!accountId) return [];
    return fetchJson<AgentTimeseries[]>(
      `/api/dbops/aws/${accountId}/agent-timeseries?hours=${hours}`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  const fetchAgentTableSizes = useCallback(async (): Promise<AgentTableSize[]> => {
    if (!accountId) return [];
    return fetchJson<AgentTableSize[]>(
      `/api/dbops/aws/${accountId}/agent-table-sizes`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  const fetchAgentIndexUsage = useCallback(async (): Promise<AgentIndexUsage[]> => {
    if (!accountId) return [];
    return fetchJson<AgentIndexUsage[]>(
      `/api/dbops/aws/${accountId}/agent-index-usage`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  const fetchAgentTableActivity = useCallback(async (): Promise<AgentTableActivity[]> => {
    if (!accountId) return [];
    return fetchJson<AgentTableActivity[]>(
      `/api/dbops/aws/${accountId}/agent-table-activity`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  const fetchAgentVacuumStats = useCallback(async (): Promise<AgentVacuumStat[]> => {
    if (!accountId) return [];
    return fetchJson<AgentVacuumStat[]>(
      `/api/dbops/aws/${accountId}/agent-vacuum-stats`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  const fetchAgentUserActivity = useCallback(async (): Promise<AgentUserActivity[]> => {
    if (!accountId) return [];
    return fetchJson<AgentUserActivity[]>(
      `/api/dbops/aws/${accountId}/agent-user-activity`
    ).then(r => r ?? []).catch(() => []);
  }, [accountId]);

  return {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    healthSnapshots, dbAlerts,
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

export async function fetchHealthHistory(accountId: string, instanceId: string, hours = 24): Promise<DbHealthSnapshotDto[]> {
  try {
    return await apiClient.get<DbHealthSnapshotDto[]>(
      `/api/dbops/aws/${accountId}/health-history?instanceId=${encodeURIComponent(instanceId)}&hours=${hours}`
    );
  } catch { return []; }
}
