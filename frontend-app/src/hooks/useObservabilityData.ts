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

// ── Error handling ────────────────────────────────────────────────────────────

/** Outcome of a write. Callers must not assume success — see extractApiError. */
export interface MutationResult {
  ok: boolean;
  error?: string;
  /** Infrastructure failure the backend flagged as worth retrying. */
  retryable?: boolean;
}

/** Outcome of a read that returns a collection, carrying the failure reason when it fails. */
export interface QueryResult<T> {
  data: T;
  error?: string;
  retryable?: boolean;
}

/**
 * Turns a thrown apiClient error into something worth showing a user.
 *
 * apiClient throws `Error("HTTP 500: <raw body>")`, and the observability endpoints answer
 * with `{ error, retryable }`. Rendering the raw message leaked the HTTP envelope and a JSON
 * blob into the UI, so the envelope is unwrapped here and the backend's own message — which
 * is already written for a user, including the 503 "temporarily unavailable" text — is used.
 */
export function extractApiError(e: unknown, fallback = 'Request failed'): { message: string; retryable: boolean } {
  if (!(e instanceof Error)) return { message: fallback, retryable: false };

  const match = /^HTTP (\d+): ([\s\S]*)$/.exec(e.message);
  if (!match) {
    // Client-side failure (timeout, network, abort) — the message is already readable.
    return { message: e.message || fallback, retryable: /timed out|network|fetch/i.test(e.message) };
  }

  const status = Number(match[1]);
  const body = match[2].trim();
  let message = '';
  let retryable = status === 503 || status === 504;

  if (body.startsWith('{')) {
    try {
      const parsed = JSON.parse(body) as { error?: string; message?: string; retryable?: boolean };
      message = parsed.error || parsed.message || '';
      if (typeof parsed.retryable === 'boolean') retryable = parsed.retryable;
    } catch {
      // fall through to the raw body
    }
  }

  if (!message) {
    // The length cap exists to stop an HTML error page being dumped into the UI, but at 300
    // characters it also discarded the messages worth showing: the plain-text config generators
    // answer with a multi-sentence explanation of why a credential was refused and what to do,
    // which runs to roughly 330. Reject HTML explicitly and allow a server-authored message to
    // be as long as it needs to be.
    const looksLikeHtml = /^\s*<(!doctype|html|head|body)\b/i.test(body);
    message = (!looksLikeHtml && body.length > 0 && body.length <= 2000)
      ? body
      : `${fallback} (HTTP ${status})`;
  }
  return { message, retryable };
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

  // Per-dataset failure reasons. These exist so a failed request renders as a failure
  // rather than as an empty result — previously a 503 from the backend and a genuinely
  // empty stream produced the identical "No logs found" screen.
  const [logsError, setLogsError] = useState<string | null>(null);
  const [streamsError, setStreamsError] = useState<string | null>(null);
  const [tracesError, setTracesError] = useState<string | null>(null);

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
    setStreamsError(null);
    try {
      const data = await apiClient.get(`/api/observability/streams${accountParam}`);
      setStreams(data || []);
      return data as LogStream[];
    } catch (e: unknown) {
      const { message } = extractApiError(e, 'Failed to load streams');
      setStreamsError(message);
      setStreams([]);
      return [];
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Logs ─────────────────────────────────────────────────────────────────────

  const searchLogs = useCallback(async (params: LogSearchParams) => {
    setLogsLoading(true);
    setError(null);
    setLogsError(null);
    try {
      const data = await apiClient.post('/api/observability/logs/search', {
        ...params,
        size: params.size ?? 50,
        from: params.from ?? 0,
        providerAccountId: activeAccountId ?? undefined,
      });

      // The backend answers 200 with `{ hits: [], total: 0, error }` when OpenObserve
      // rejects the query — a malformed SQL statement from the query editor being the
      // common case. That `error` was dropped here, so a syntax error was indistinguishable
      // from a stream with no matching records and the editor was undebuggable.
      if (data?.error) {
        setLogsError(String(data.error));
        setLogs([]);
        setLogTotal(0);
        return [];
      }

      const hits: LogEntry[] = data?.hits || [];
      setLogs(hits);
      setLogTotal(data?.total || hits.length);
      return hits;
    } catch (e: unknown) {
      const { message } = extractApiError(e, 'Log search failed');
      setError(message);
      setLogsError(message);
      setLogs([]);
      setLogTotal(0);
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

  // Trace reads clear `tracesError` on the search call only: search and stats run in
  // parallel from the explorer, so clearing in both would let whichever resolved second
  // wipe the other's error.
  const searchTraces = useCallback(async (params: TraceSearchParams): Promise<TraceSummary[]> => {
    setTracesError(null);
    try {
      const data = await apiClient.post('/api/observability/traces/search', {
        ...params,
        size: params.size ?? 100,
        providerAccountId: activeAccountId ?? undefined,
      });
      if (data?.error) {
        setTracesError(String(data.error));
        return [];
      }
      return (data?.traces as TraceSummary[]) || [];
    } catch (e: unknown) {
      setTracesError(extractApiError(e, 'Trace search failed').message);
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
      if (data?.error) {
        setTracesError(String(data.error));
        return null;
      }
      return data as TraceStats;
    } catch (e: unknown) {
      setTracesError(extractApiError(e, 'Failed to load trace statistics').message);
      return null;
    }
  }, [activeAccountId]);

  const fetchTraceSpans = useCallback(async (
    traceId: string, startTime: number, endTime: number, stream = 'default'
  ): Promise<QueryResult<TraceSpan[]>> => {
    try {
      const data = await apiClient.post('/api/observability/traces/spans', {
        traceId, startTime, endTime, stream,
        providerAccountId: activeAccountId ?? undefined,
      });
      if (data?.error) return { data: [], error: String(data.error) };
      return { data: (data?.spans as TraceSpan[]) || [] };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Failed to load spans');
      return { data: [], error: message, retryable };
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

  const fetchAlerts = useCallback(async (): Promise<QueryResult<ObservabilityAlert[]>> => {
    try {
      const data = await apiClient.get(`/api/observability/alerts${accountParam}`);
      return { data: (data as ObservabilityAlert[]) || [] };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Failed to load alerts');
      return { data: [], error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // Writes report the reason they failed. These used to return a bare boolean from a
  // `catch {}`, and every caller discarded it — so a rejected alert looked exactly like a
  // created one. Callers are expected to check `ok` and surface `error`.
  const createAlert = useCallback(async (alert: ObservabilityAlert): Promise<MutationResult> => {
    try {
      await apiClient.post(`/api/observability/alerts${accountParam}`, alert);
      return { ok: true };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Could not create the alert');
      return { ok: false, error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const deleteAlert = useCallback(async (streamType: string, streamName: string, alertName: string): Promise<MutationResult> => {
    try {
      await apiClient.delete(`/api/observability/alerts/${streamType}/${streamName}/${alertName}${accountParam}`);
      return { ok: true };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Could not delete the alert');
      return { ok: false, error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const toggleAlert = useCallback(async (streamType: string, streamName: string, alertName: string, enable: boolean): Promise<MutationResult> => {
    try {
      await apiClient.put(`/api/observability/alerts/${streamType}/${streamName}/${alertName}/toggle${accountParam ? accountParam + '&' : '?'}enable=${enable}`, {});
      return { ok: true };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, `Could not ${enable ? 'enable' : 'disable'} the alert`);
      return { ok: false, error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  // ── Alert Destinations ────────────────────────────────────────────────────────

  const fetchDestinations = useCallback(async (): Promise<QueryResult<AlertDestination[]>> => {
    try {
      const data = await apiClient.get(`/api/observability/alert-destinations${accountParam}`);
      return { data: (data as AlertDestination[]) || [] };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Failed to load destinations');
      return { data: [], error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const saveDestination = useCallback(async (dest: AlertDestination): Promise<MutationResult> => {
    try {
      await apiClient.post(`/api/observability/alert-destinations${accountParam}`, dest);
      return { ok: true };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Could not save the destination');
      return { ok: false, error: message, retryable };
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeAccountId]);

  const deleteDestination = useCallback(async (name: string): Promise<MutationResult> => {
    try {
      await apiClient.delete(`/api/observability/alert-destinations/${encodeURIComponent(name)}${accountParam}`);
      return { ok: true };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Could not delete the destination');
      return { ok: false, error: message, retryable };
    }
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

  /**
   * Fetches a generated collector config.
   *
   * Returns the reason on failure rather than an empty string. The backend refuses to issue a
   * config when the account has no org-scoped ingestion credential, and explains what to do
   * about it — but that was swallowed here, so the Setup tab showed "try restarting the
   * backend", which is advice that cannot possibly help and hides the actual fix.
   */
  const fetchSetupConfig = useCallback(async (type: 'fluent-bit' | 'helm' | 'otel' | 'agent-secret' | 'agents/rbac' | 'agents/xamops-agent' | 'agents/fluent-bit' | 'agents/otel-collector' | 'helm-eks' | 'ec2-otel' | 'gce-otel'): Promise<QueryResult<string>> => {
    try {
      const data = await apiClient.get(`/api/observability/setup/${type}${accountParam}`);
      setSetupConfig(data);
      return { data: data as string };
    } catch (e: unknown) {
      const { message, retryable } = extractApiError(e, 'Could not generate the configuration');
      // The plain-text generators answer with a `#`-commented explanation so it reads correctly
      // inside a shell script. Strip the comment markers for display in the UI.
      const cleaned = message
        .split('\n')
        .map(line => line.replace(/^#\s?/, '').trim())
        .filter(line => line.length > 0 && !/^Configuration was not generated\.?$/i.test(line))
        .join(' ')
        .trim();
      return { data: '', error: cleaned || message, retryable };
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
    logsError,
    streamsError,
    tracesError,
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
