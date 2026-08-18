import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { observabilityQueryLimit } from '../utils/concurrencyLimit';
import { AgentStatus } from '../utils/agentHealth';
import { useAccounts } from '../contexts/AccountContext';

// ── Types ─────────────────────────────────────────────────────────────────────

export interface ObservabilityConfig {
  orgId: string;
  accountName?: string;
  retentionDays: number;
  provisionedAt: string;
  /**
   * Whether this config describes the selected cloud account or the whole organisation.
   * A 'client' scope while an account is selected means we fell back — the data shown is
   * organisation-wide, not the account's.
   */
  scope?: 'account' | 'client';
  accountProvisioned?: boolean;
  requestedAccountId?: string;
  fallbackReason?: string;
}

export interface LogStream {
  name: string;
  stream_type?: 'logs' | 'metrics' | 'traces' | string;
  schema?: { name: string; field_type: string }[];
  stats?: { doc_count: number; storage_size: number };
}

export interface LogEntry {
  _timestamp: number;
  level?: string;
  message?: string;
  service?: string;
  namespace?: string;
  pod_name?: string;
  container_name?: string;
  [key: string]: unknown;
}

export interface LogSearchParams {
  stream: string;
  keyword?: string;
  level?: string;
  rawSql?: string;
  sourceFilter?: string;
  startTime: number;
  endTime: number;
  size?: number;
  from?: number;
}

export interface EksCluster {
  clusterName: string;
  region: string;
}

export interface HistogramBucket {
  key: string;
  num: number;
}

export interface MetricResult {
  metric: Record<string, string>;
  values: [number, string][];
}

export interface AlertDestination {
  name: string;
  url: string;
  method?: string;
  type?: string;
  skip_tls_verify?: boolean;
  headers?: Record<string, string>;
}

export interface ObservabilityAlert {
  name: string;
  stream_type: string;
  stream_name: string;
  is_real_time: boolean;
  enabled: boolean;
  severity?: string;
  description?: string;
  destinations?: string[];
  query_condition?: {
    type: string;
    promql?: string;
    sql?: string;
  };
  trigger_condition?: {
    period: number;
    frequency: number;
    silence: number;
    threshold: number;
    operator: string;
  };
}

export interface ConnectedInstance {
  hostName: string;
  lastSeenMs: number;
  /** Health state as classified by the backend — see utils/agentHealth for presentation. */
  status: AgentStatus;
  /**
   * Collector self-telemetry is arriving even though host metrics aren't: the agent is
   * running and its pipeline is broken, which needs a config fix rather than a restart.
   */
  collectorAlive?: boolean;
}

/** Wire format of /api/observability/agents — status arrives as an uppercase enum name. */
interface RawAgentHealth {
  hostName: string;
  lastSeenMs: number;
  status: string;
  ageMs: number;
  collectorAlive?: boolean;
}

// A trace's root span plus per-trace rollups computed by the backend.
export interface TraceSummary {
  trace_id: string;
  operation_name?: string;
  service_name?: string;
  duration?: number;        // µs
  start_time?: number;      // ns
  span_status?: string;     // UNSET | OK | ERROR
  status_message?: string;
  http_status_code?: string;
  http_method?: string;
  span_count?: number;
  error_count?: number;
  service_count?: number;
  [key: string]: unknown;
}

export interface TraceSpan {
  trace_id: string;
  span_id: string;
  reference_parent_span_id?: string;
  operation_name?: string;
  service_name?: string;
  duration?: number;        // µs
  start_time?: number;      // ns
  end_time?: number;        // ns
  span_status?: string;
  span_kind?: string;       // "1" internal · "2" server · "3" client
  status_message?: string;
  _timestamp?: number;
  [key: string]: unknown;
}

export interface TraceSearchParams {
  stream?: string;
  keyword?: string;
  service?: string;
  host?: string;
  minDurationMs?: number;
  errorsOnly?: boolean;
  startTime: number;        // ms
  endTime: number;          // ms
  size?: number;
}

export interface TraceStats {
  summary: { n?: number; errors?: number; avg_us?: number; p50?: number; p95?: number; p99?: number };
  histogram: { key: string; total: number; errors: number }[];
  operations: { operation_name: string; service_name: string; n: number; avg_us: number; p95: number; errors: number }[];
  services: { service_name: string; n: number }[];
  hosts?: { service_host_name: string; n: number }[];
}

export interface OverviewStats {
  orgId: string;
  accountName?: string;
  /** Retention OpenObserve is actually enforcing on this org. */
  retentionDays: number;
  /** Retention configured in XamOps — may differ from the above if it was never applied. */
  configuredRetentionDays?: number;
  retentionMismatch?: boolean;
  /** 'client' while an account is selected means these totals are organisation-wide. */
  scope?: 'account' | 'client';
  accountProvisioned?: boolean;
  streamCount: number;
  logStreamCount: number;
  metricStreamCount: number;
  traceStreamCount: number;
  totalEvents: number;
  ingestedSizeMb: number;
  compressedSizeMb: number;
  indexSizeMb: number;
  streams: LogStream[];
  provisionedAt: string;
}

// ── Hook ──────────────────────────────────────────────────────────────────────

export const useObservabilityData = () => {
  const { selectedAccountIds } = useAccounts();
  // The currently selected account's provider ID (AWS account ID / GCP project ID / Azure sub ID)
  const activeAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [config, setConfig] = useState<ObservabilityConfig | null>(null);
  const [overview, setOverview] = useState<OverviewStats | null>(null);
  const [streams, setStreams] = useState<LogStream[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [logTotal, setLogTotal] = useState(0);
  const [histogram, setHistogram] = useState<HistogramBucket[]>([]);
  const [metrics, setMetrics] = useState<MetricResult[]>([]);
  const [setupConfig, setSetupConfig] = useState('');

  const [configLoading, setConfigLoading] = useState(false);
  const [logsLoading, setLogsLoading] = useState(false);
  const [metricsLoading, setMetricsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Build query string suffix for the active account
  const accountParam = activeAccountId
    ? `?providerAccountId=${encodeURIComponent(activeAccountId)}`
    : '';
  const accountParamAmpersand = activeAccountId
    ? `&providerAccountId=${encodeURIComponent(activeAccountId)}`
    : '';

  // ── Config ──────────────────────────────────────────────────────────────────

  const fetchConfig = useCallback(async () => {
    setConfigLoading(true);
    setError(null);
    try {
      const data = await apiClient.get(`/api/observability/config${accountParam}`);
      setConfig(data);
      return data as ObservabilityConfig;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to load config';
      setError(msg);
      return null;
    } finally {
      setConfigLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const saveConfig = useCallback(async (updates: Pick<ObservabilityConfig, 'retentionDays'>) => {
    setError(null);
    try {
      const data = await apiClient.put('/api/observability/config', updates);
      setConfig(data);
      return data as ObservabilityConfig;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to save config';
      setError(msg);
      return null;
    }
  }, []);

  // ── Overview ────────────────────────────────────────────────────────────────

  const fetchOverview = useCallback(async () => {
    try {
      const data = await apiClient.get(`/api/observability/overview${accountParam}`);
      setOverview(data);
      return data as OverviewStats;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to load overview';
      setError(msg);
      return null;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Streams ─────────────────────────────────────────────────────────────────

  const fetchStreams = useCallback(async () => {
    try {
      const data = await apiClient.get(`/api/observability/streams${accountParam}`);
      setStreams(data || []);
      return data as LogStream[];
    } catch {
      setStreams([]);
      return [];
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Logs ─────────────────────────────────────────────────────────────────────

  const searchLogs = useCallback(async (params: LogSearchParams) => {
    setLogsLoading(true);
    setError(null);
    try {
      const data = await apiClient.post('/api/observability/logs/search', {
        ...params,
        size: params.size ?? 50,
        from: params.from ?? 0,
        providerAccountId: activeAccountId ?? undefined,
      });
      const hits: LogEntry[] = data?.hits || [];
      setLogs(hits);
      setLogTotal(data?.total || hits.length);
      return hits;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Log search failed';
      setError(msg);
      setLogs([]);
      return [];
    } finally {
      setLogsLoading(false);
    }
  }, [activeAccountId]);

  // Distinct hosts sending logs to a stream — powers the Host/Source dropdown.
  const fetchLogHosts = useCallback(async (stream: string, startTime: number, endTime: number): Promise<string[]> => {
    try {
      const data = await apiClient.post('/api/observability/logs/hosts', {
        stream, startTime, endTime,
        providerAccountId: activeAccountId ?? undefined,
      });
      return Array.isArray(data?.hosts) ? data.hosts.map((h: unknown) => String(h)) : [];
    } catch {
      return [];
    }
  }, [activeAccountId]);

  // `match` adds a full-text condition (e.g. "error") — those calls return buckets
  // without touching the shared histogram state, so overlays don't clobber the total.
  const fetchHistogram = useCallback(async (stream: string, startTime: number, endTime: number, interval?: string, match?: string) => {
    try {
      const data = await apiClient.post('/api/observability/logs/histogram', {
        stream, startTime, endTime,
        interval: interval ?? undefined,
        match: match ?? undefined,
        providerAccountId: activeAccountId ?? undefined,
      });
      const buckets: HistogramBucket[] = (data?.hits || []).map((h: Record<string, unknown>) => ({
        key: String(h.key),
        num: Number(h.num),
      }));
      if (!match) setHistogram(buckets);
      return buckets;
    } catch {
      if (!match) setHistogram([]);
      return [];
    }
  }, [activeAccountId]);

  // ── Metrics ──────────────────────────────────────────────────────────────────

  const queryMetrics = useCallback(async (
    query: string,
    startTime: number,
    endTime: number,
    step = '60s'
  ) => {
    setMetricsLoading(true);
    try {
      const params = new URLSearchParams({
        query,
        startTime: String(startTime),
        endTime: String(endTime),
        step,
      });
      // Queued: the dashboard mounts 18 panels that each call this at once.
      // See utils/concurrencyLimit for why the burst has to be capped.
      const data = await observabilityQueryLimit(() => apiClient.get(
        `/api/observability/metrics/query?${params}${accountParamAmpersand}`
      ));
      const results: MetricResult[] = data?.data?.result || [];
      setMetrics(results);
      return results;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Metrics query failed';
      setError(msg);
      return [];
    } finally {
      setMetricsLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Traces ───────────────────────────────────────────────────────────────────

  const searchTraces = useCallback(async (params: TraceSearchParams): Promise<TraceSummary[]> => {
    try {
      const data = await apiClient.post('/api/observability/traces/search', {
        ...params,
        size: params.size ?? 100,
        providerAccountId: activeAccountId ?? undefined,
      });
      return (data?.traces as TraceSummary[]) || [];
    } catch {
      return [];
    }
  }, [activeAccountId]);

  const fetchTraceStats = useCallback(async (
    params: Omit<TraceSearchParams, 'size'> & { interval?: string }
  ): Promise<TraceStats | null> => {
    try {
      const data = await apiClient.post('/api/observability/traces/stats', {
        ...params,
        providerAccountId: activeAccountId ?? undefined,
      });
      return data as TraceStats;
    } catch {
      return null;
    }
  }, [activeAccountId]);

  const fetchTraceSpans = useCallback(async (
    traceId: string, startTime: number, endTime: number, stream = 'default'
  ): Promise<TraceSpan[]> => {
    try {
      const data = await apiClient.post('/api/observability/traces/spans', {
        traceId, startTime, endTime, stream,
        providerAccountId: activeAccountId ?? undefined,
      });
      return (data?.spans as TraceSpan[]) || [];
    } catch {
      return [];
    }
  }, [activeAccountId]);

  // ── Cluster discovery ─────────────────────────────────────────────────────────

  const fetchClusters = useCallback(async (): Promise<EksCluster[]> => {
    try {
      const data = await apiClient.get(`/api/observability/clusters${accountParam}`);
      return (data as EksCluster[]) || [];
    } catch {
      return [];
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Alerts ───────────────────────────────────────────────────────────────────

  const fetchAlerts = useCallback(async (): Promise<ObservabilityAlert[]> => {
    try {
      const data = await apiClient.get(`/api/observability/alerts${accountParam}`);
      return (data as ObservabilityAlert[]) || [];
    } catch { return []; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const createAlert = useCallback(async (alert: ObservabilityAlert): Promise<boolean> => {
    try {
      await apiClient.post(`/api/observability/alerts${accountParam}`, alert);
      return true;
    } catch { return false; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const deleteAlert = useCallback(async (streamType: string, streamName: string, alertName: string): Promise<boolean> => {
    try {
      await apiClient.delete(`/api/observability/alerts/${streamType}/${streamName}/${alertName}${accountParam}`);
      return true;
    } catch { return false; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const toggleAlert = useCallback(async (streamType: string, streamName: string, alertName: string, enable: boolean): Promise<boolean> => {
    try {
      await apiClient.put(`/api/observability/alerts/${streamType}/${streamName}/${alertName}/toggle${accountParam ? accountParam + '&' : '?'}enable=${enable}`, {});
      return true;
    } catch { return false; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Alert Destinations ────────────────────────────────────────────────────────

  const fetchDestinations = useCallback(async (): Promise<AlertDestination[]> => {
    try {
      const data = await apiClient.get(`/api/observability/alert-destinations${accountParam}`);
      return (data as AlertDestination[]) || [];
    } catch { return []; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const saveDestination = useCallback(async (dest: AlertDestination): Promise<boolean> => {
    try {
      await apiClient.post(`/api/observability/alert-destinations${accountParam}`, dest);
      return true;
    } catch { return false; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const deleteDestination = useCallback(async (name: string): Promise<boolean> => {
    try {
      await apiClient.delete(`/api/observability/alert-destinations/${name}${accountParam}`);
      return true;
    } catch { return false; }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Connected Instances ───────────────────────────────────────────────────────

  /**
   * Builds the roster of known hosts with a health state for each.
   *
   * Uses two windows deliberately:
   *
   *  - A wide 7-day window at coarse resolution establishes *which* hosts exist. The old
   *    single 6-hour query meant a host that died 7 hours ago silently vanished from the
   *    UI, so an agent going away looked identical to an agent that was never installed.
   *  - A narrow 1-hour window at 60s resolution pins down *when* each host last reported.
   *    The wide window's step is far too coarse to tell "reporting now" from "stopped 45
   *    minutes ago", which is exactly the distinction that matters for health.
   *
   * Hosts present in the wide roster but absent from the recent window keep their coarse
   * lastSeen and land in the offline/removed states.
   */
  /**
   * Agent roster with a health state for each host.
   *
   * Classification is done server-side (`/api/observability/agents`) rather than here. It
   * used to be computed in the browser from two raw PromQL queries, which meant the
   * thresholds existed in two places — so the console could disagree with the agent-silence
   * alert email a user had just been sent. The backend is now the only place that decides
   * what "offline" means, and this is one request instead of two.
   */
  const fetchConnectedInstances = useCallback(async (): Promise<ConnectedInstance[]> => {
    try {
      const data = await observabilityQueryLimit(() =>
        apiClient.get(`/api/observability/agents${accountParam}`));

      const agents: RawAgentHealth[] = data?.agents || [];
      return agents.map(a => ({
        hostName: a.hostName,
        lastSeenMs: a.lastSeenMs,
        status: (a.status?.toLowerCase() as AgentStatus) ?? 'gone',
        collectorAlive: a.collectorAlive ?? false,
      }));
    } catch {
      return [];
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Setup configs ─────────────────────────────────────────────────────────────

  const fetchSetupConfig = useCallback(async (type: 'fluent-bit' | 'helm' | 'otel' | 'agent-secret' | 'agents/rbac' | 'agents/xamops-agent' | 'agents/fluent-bit' | 'agents/otel-collector' | 'helm-eks' | 'ec2-otel' | 'gce-otel') => {
    try {
      const data = await apiClient.get(`/api/observability/setup/${type}${accountParam}`);
      setSetupConfig(data);
      return data as string;
    } catch {
      return '';
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  return {
    // State
    config,
    overview,
    streams,
    logs,
    logTotal,
    histogram,
    metrics,
    setupConfig,
    // Loading
    configLoading,
    logsLoading,
    metricsLoading,
    error,
    // Active account
    activeAccountId,
    // Actions
    fetchConfig,
    saveConfig,
    fetchOverview,
    fetchStreams,
    searchLogs,
    fetchLogHosts,
    fetchHistogram,
    searchTraces,
    fetchTraceStats,
    fetchTraceSpans,
    queryMetrics,
    fetchSetupConfig,
    fetchClusters,
    fetchConnectedInstances,
    fetchAlerts,
    createAlert,
    deleteAlert,
    toggleAlert,
    fetchDestinations,
    saveDestination,
    deleteDestination,
  };
};
