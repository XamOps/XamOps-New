/**
 * The Phase-2 time series (FEAT-004), read-only, from Prometheus in the cluster through the API
 * server's service proxy:
 *  - usage over a window: `GET /api/eks/automation/usage/{cluster}` (`UsageSeriesDto`), for the
 *    cluster, every namespace or one workload;
 *  - the Workloads table's sparklines: `GET /api/eks/automation/usage/{cluster}/sparklines`
 *    (`SparklinesDto`);
 *  - one HPA's replicas, bounds and CPU against its target:
 *    `GET /api/eks/automation/hpa/{cluster}/{namespace}/{name}/history` (`HpaHistoryDto`).
 *
 * Two layers, as in `automationActivity.ts`:
 *  - the wire types, field for field with the DTOs, every field optional because the reader must
 *    survive an older or newer backend;
 *  - the view types the charts read: times in epoch ms, and a point whose value nothing measured is
 *    `null` (a gap in the chart), never 0. Values keep Prometheus' units (cores, bytes, replicas,
 *    percent); the page converts them for display.
 */
import type { CodeView } from './verticalRightsizer';

// ─── Wire ────────────────────────────────────────────────────────────────────

/** One point as the server sends it: `[epochSeconds, value]`, value `null` where nothing was measured. */
export type SeriesPointWire = [number, number | null];

export interface InsightSourceWire {
  /** `PROMETHEUS_PROXY`: the cluster's own Prometheus, through the Kubernetes API service proxy. */
  type?: string | null;
  /** `namespace/service:port`. */
  service?: string | null;
}

/** Why there is no data (a `200` answer): the code and the reason in words. */
export interface InsightUnavailableWire {
  code?: string | null;
  message?: string | null;
}

export interface WorkloadRefWire {
  namespace?: string | null;
  kind?: string | null;
  name?: string | null;
}

export interface UsageSeriesWire {
  key?: string | null;
  /** `used | request | limit | allocatable | capacity`. */
  kind?: string | null;
  label?: string | null;
  points?: unknown;
  query?: string | null;
}

export interface UsageNamespaceWire {
  namespace?: string | null;
  requestedNow?: number | null;
  usedAvg?: number | null;
  series?: UsageSeriesWire[] | null;
}

export interface UsageResponseWire {
  schemaVersion?: number | null;
  scope?: string | null;
  resource?: string | null;
  /** `cores | bytes`. */
  unit?: string | null;
  workloadRef?: WorkloadRefWire | null;
  window?: string | null;
  stepSeconds?: number | null;
  start?: string | null;
  end?: string | null;
  source?: InsightSourceWire | null;
  series?: UsageSeriesWire[] | null;
  namespaces?: UsageNamespaceWire[] | null;
  coverage?: { firstSampleAt?: string | null; covered?: number | null } | null;
  unavailable?: InsightUnavailableWire | null;
}

export interface SparklineWire {
  ref?: WorkloadRefWire | null;
  used?: unknown;
  request?: unknown;
}

export interface SparklinesResponseWire {
  schemaVersion?: number | null;
  resource?: string | null;
  unit?: string | null;
  window?: string | null;
  stepSeconds?: number | null;
  start?: string | null;
  end?: string | null;
  source?: InsightSourceWire | null;
  queries?: { used?: string | null; request?: string | null } | null;
  workloads?: SparklineWire[] | null;
  unavailable?: InsightUnavailableWire | null;
}

export interface HpaHistorySeriesWire {
  /** `currentReplicas | minReplicas | maxReplicas | cpuUtilization | cpuTarget`. */
  key?: string | null;
  label?: string | null;
  /** `replicas | percent`. */
  unit?: string | null;
  points?: unknown;
  query?: string | null;
}

export interface HpaHistoryResponseWire {
  schemaVersion?: number | null;
  namespace?: string | null;
  name?: string | null;
  window?: string | null;
  stepSeconds?: number | null;
  start?: string | null;
  end?: string | null;
  source?: InsightSourceWire | null;
  series?: HpaHistorySeriesWire[] | null;
  atMax?: {
    share?: number | null;
    minutesAtMax?: number | null;
    minutesMeasured?: number | null;
    runs?: Array<{ from?: string | null; to?: string | null }> | null;
    runsTruncated?: boolean | null;
    since?: string | null;
    stepSeconds?: number | null;
    query?: string | null;
  } | null;
  changes?: Array<{ at?: string | null; from?: number | null; to?: number | null }> | null;
  changesTruncated?: boolean | null;
  firstSampleAt?: string | null;
  unavailable?: InsightUnavailableWire | null;
}

// ─── Query ───────────────────────────────────────────────────────────────────

export type UsageScope = 'cluster' | 'namespace' | 'workload';
export type UsageResource = 'cpu' | 'memory';
/** The fixed windows; the server picks the step (24 h → 10 min, 72 h → 30 min, 168 h → 1 h). */
export type UsageWindow = '24h' | '72h' | '168h';
/** The custom window's steps (scope=workload only), in seconds. */
export type UsageStep = 60 | 300 | 1800 | 3600;

export interface UsageQuery {
  scope: UsageScope;
  resource: UsageResource;
  window: UsageWindow | 'custom';
  /** Required with scope=workload, refused by the server otherwise. */
  workload?: { namespace: string; kind: string; name: string };
  /** window=custom only: epoch ms, sent as ISO-8601 in UTC. */
  from?: number;
  to?: number;
  step?: UsageStep;
}

export type HpaHistoryWindow = '24h' | '72h' | '168h';

// ─── View ────────────────────────────────────────────────────────────────────

/** One point: epoch ms and the value, `null` where nothing was measured. */
export interface SeriesPoint {
  t: number;
  v: number | null;
}

export interface InsightSource {
  type: string | null;
  service: string | null;
}

/** Why a source has no data. `code` is the server's (ENGINE_DISABLED, METRICS_TIMEOUT, …). */
export interface InsightUnavailable {
  code: string;
  message: string | null;
}

export interface UsageSeriesView {
  key: string;
  kind: string;
  label: string | null;
  points: SeriesPoint[];
  query: string | null;
}

export interface UsageNamespaceView {
  namespace: string;
  /** The request series' last point (Running pods), in the view's unit; null when not measured. */
  requestedNow: number | null;
  /** The mean of the used series' measured points over the window; null when none was measured. */
  usedAvg: number | null;
  series: UsageSeriesView[];
}

export interface UsageView {
  scope: string | null;
  resource: string | null;
  unit: string | null;
  workloadRef: { namespace: string; kind: string; name: string } | null;
  window: string | null;
  stepSeconds: number | null;
  /** Epoch ms of the first and last point. */
  start: number | null;
  end: number | null;
  source: InsightSource;
  series: UsageSeriesView[];
  /** scope=namespace only; null otherwise. */
  namespaces: UsageNamespaceView[] | null;
  /** How much of the window the used series has samples for; null when unavailable. */
  coverage: { firstSampleAt: number | null; covered: number | null } | null;
  unavailable: InsightUnavailable | null;
}

export interface SparklineView {
  /** `namespace/kind/name`, the same id endpoint 1's workloads carry. */
  id: string;
  ref: { namespace: string; kind: string; name: string };
  /** The busiest pod's use per step; every value null when no pod ran. */
  used: SeriesPoint[];
  /** The busiest pod's request per step. */
  request: SeriesPoint[];
}

export interface SparklinesView {
  resource: string | null;
  unit: string | null;
  window: string | null;
  stepSeconds: number | null;
  start: number | null;
  end: number | null;
  source: InsightSource;
  queries: { used: string | null; request: string | null } | null;
  workloads: SparklineView[];
  unavailable: InsightUnavailable | null;
}

export interface HpaHistorySeriesView {
  key: string;
  label: string | null;
  unit: string | null;
  points: SeriesPoint[];
  query: string | null;
}

export interface HpaAtMaxView {
  /** minutesAtMax ÷ minutesMeasured; null when nothing was measured. */
  share: number | null;
  minutesAtMax: number | null;
  minutesMeasured: number | null;
  /** Unbroken spells at the maximum, oldest first; `to` null while the spell is still running. */
  runs: Array<{ from: number; to: number | null }>;
  runsTruncated: boolean;
  /** When the current spell began, if the HPA is at its maximum now. */
  since: number | null;
  stepSeconds: number | null;
  query: string | null;
}

export interface HpaReplicaChange {
  at: number;
  from: number;
  to: number;
}

export interface HpaHistoryView {
  namespace: string | null;
  name: string | null;
  window: string | null;
  stepSeconds: number | null;
  start: number | null;
  end: number | null;
  source: InsightSource;
  series: HpaHistorySeriesView[];
  atMax: HpaAtMaxView | null;
  /** Every change of the running replica count, oldest first (the newest 200). */
  changes: HpaReplicaChange[];
  changesTruncated: boolean;
  /** The first time any series has a sample; null when none has. */
  firstSampleAt: number | null;
  unavailable: InsightUnavailable | null;
}

/**
 * What one of these reads answered. A source failure inside the cluster is still `ok`, with the
 * view's `unavailable` set; these kinds are the HTTP answers.
 */
export type InsightReadResult<T> =
  | { kind: 'ok'; view: T }
  /** An untyped 404: this backend doesn't serve the endpoint, so the panel isn't rendered. */
  | { kind: 'not-deployed' }
  | { kind: 'cluster-not-registered'; message: string }
  /** A typed refusal (403, 400, or a typed 404 such as a workload the cluster doesn't have). */
  | { kind: 'refused'; code: CodeView; message: string; field: string | null }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };
