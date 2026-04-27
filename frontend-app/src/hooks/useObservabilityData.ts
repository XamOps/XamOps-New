import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { useAccounts } from '../contexts/AccountContext';

// ── Types ─────────────────────────────────────────────────────────────────────

export interface ObservabilityConfig {
  orgId: string;
  accountName?: string;
  retentionDays: number;
  provisionedAt: string;
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
}

export interface OverviewStats {
  orgId: string;
  accountName?: string;
  retentionDays: number;
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

  const fetchHistogram = useCallback(async (stream: string, startTime: number, endTime: number) => {
    try {
      const data = await apiClient.post('/api/observability/logs/histogram', {
        stream, startTime, endTime,
        providerAccountId: activeAccountId ?? undefined,
      });
      const buckets: HistogramBucket[] = (data?.hits || []).map((h: Record<string, unknown>) => ({
        key: String(h.key),
        num: Number(h.num),
      }));
      setHistogram(buckets);
      return buckets;
    } catch {
      setHistogram([]);
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
      const data = await apiClient.get(
        `/api/observability/metrics/query?${params}${accountParamAmpersand}`
      );
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

  const fetchConnectedInstances = useCallback(async (): Promise<ConnectedInstance[]> => {
    try {
      const now = Date.now();
      const start = now - 6 * 3600_000;
      const params = new URLSearchParams({
        query: 'group by (host_name) (system_cpu_load_average_1m)',
        startTime: String(start),
        endTime: String(now),
        step: '10m',
      });
      const data = await apiClient.get(
        `/api/observability/metrics/query?${params}${accountParamAmpersand}`
      );
      const results: MetricResult[] = data?.data?.result || [];
      return results
        .filter(r => r.metric?.host_name)
        .map(r => {
          const lastVal = r.values[r.values.length - 1];
          return {
            hostName: r.metric.host_name,
            lastSeenMs: lastVal ? Number(lastVal[0]) * 1000 : 0,
          };
        })
        .sort((a, b) => b.lastSeenMs - a.lastSeenMs);
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
    fetchHistogram,
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
