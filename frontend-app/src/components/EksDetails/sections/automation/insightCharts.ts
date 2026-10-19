/**
 * Pure helpers the Automation pages share to turn the Phase-2 reads (FEAT-004 usage, sparklines and
 * HPA history; FEAT-005 summary) into chart rows, card states and source lines (glossary §10).
 *
 * Rules: a point nothing measured stays null, so the chart shows a gap and the data table a dash;
 * nothing is resampled or invented; every state the card can be in is named in words, with the
 * source it came from.
 */
import type { InsightReadState } from '../../../../hooks/useInsightRead';
import type { ChartEmptyState } from '../../../CloudK8s/shared/charts/ChartCard';
import type { TimeSeriesBand } from '../../../CloudK8s/shared/charts/TimeSeriesChart';
import type { InsightUnavailable, SeriesPoint, UsageView } from '../../../../types/automationInsights';
import { DASH, formatMemoryMi } from '../../../../api/verticalRightsizerAdapter';
import { DAY_MS } from './automationDerivations';

const HOUR_MS = 3_600_000;

// ─── Time labels (IST) ───────────────────────────────────────────────────────

const TIME = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', hour12: false });
const DAY = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Kolkata', month: 'short', day: 'numeric' });

/**
 * A point's axis label in IST: "18:05" for windows up to 12 hours, "Oct 14 18:00" for longer ones.
 * Labels are unique within a window, so they key the chart's categories.
 */
export const istTick = (ms: number, spanMs: number): string =>
  spanMs > 12 * HOUR_MS ? `${DAY.format(ms)} ${TIME.format(ms)}` : TIME.format(ms);

/** "1 min", "10 min", "30 min", "1 hour". */
export function stepWords(seconds: number | null | undefined): string {
  if (!seconds || !Number.isFinite(seconds)) return DASH;
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
  const hours = seconds / 3600;
  return hours === 1 ? '1 hour' : `${hours} hours`;
}

/** "24 hours", "3 days", "7 days"; a custom window by its span. */
export function windowText(window: string | null | undefined, start?: number | null, end?: number | null): string {
  if (window === '24h') return '24 hours';
  if (window === '72h') return '3 days';
  if (window === '168h') return '7 days';
  if (start !== null && start !== undefined && end !== null && end !== undefined && end > start) {
    const hours = Math.round((end - start) / HOUR_MS);
    return hours === 1 ? '1 hour' : `${hours} hours`;
  }
  return window ?? DASH;
}

// ─── Units ───────────────────────────────────────────────────────────────────

const GIB = 1024 ** 3;
const MIB = 1024 ** 2;

/** Cores as vCPU, two decimals ("1.25 vCPU"); a dash when not measured. */
export const vcpuText = (cores: number | null): string => (cores === null ? DASH : `${cores.toFixed(2)} vCPU`);
/** Bytes as GiB, two decimals. */
export const gibText = (bytes: number | null): string => (bytes === null ? DASH : `${(bytes / GIB).toFixed(2)} GiB`);
/** Cores as millicores ("63m"). */
export const millisText = (cores: number | null): string => (cores === null ? DASH : `${Math.round(cores * 1000)}m`);
/** Bytes as the product's memory form (Mi below 1 GiB, GiB from there). */
export const mibText = (bytes: number | null): string => (bytes === null ? DASH : formatMemoryMi(Math.round(bytes / MIB)));

/** A value in the unit a chart axis shows: vCPU or GiB on cluster charts, m or Mi on workload charts. */
export type AxisUnit = 'vcpu' | 'gib' | 'millis' | 'mib';

export function toAxis(value: number | null, unit: AxisUnit): number | null {
  if (value === null) return null;
  switch (unit) {
    case 'vcpu':
      return Number(value.toFixed(3));
    case 'gib':
      return Number((value / GIB).toFixed(3));
    case 'millis':
      return Math.round(value * 1000);
    case 'mib':
      return Number((value / MIB).toFixed(1));
    default:
      return value;
  }
}

export const axisText = (value: number | null, unit: AxisUnit): string => {
  switch (unit) {
    case 'vcpu':
      return vcpuText(value);
    case 'gib':
      return gibText(value);
    case 'millis':
      return millisText(value);
    default:
      return mibText(value);
  }
};

export const AXIS_NAME: Record<AxisUnit, string> = { vcpu: 'vCPU', gib: 'GiB', millis: 'millicores', mib: 'MiB' };

// ─── Card states ─────────────────────────────────────────────────────────────

/** What a card does with a read: not rendered at all (no endpoint), or shown in a state. */
export type InsightCardState<T> =
  | { render: false }
  | { render: true; state: ChartEmptyState; view: T | null };

const REFUSAL_WORDS: Record<string, string> = {
  CLUSTER_ACCESS_NOT_CONFIGURED: "XamOps can't sign in to this cluster, so it can't read Prometheus there.",
  CLUSTER_UNREACHABLE: "XamOps can't reach this cluster's API server just now.",
  NO_ACCOUNT_ACCESS: "You don't have access to this AWS account.",
  DEMO: "Demo workspace: this isn't read here.",
};

/**
 * Why a source had no data, in words (the server's code, glossary §7.4), with the feature whose
 * switch it is when it's off.
 */
export function unavailableWords(u: InsightUnavailable, feature: 'usage' | 'hpa' = 'usage'): string {
  switch (u.code) {
    case 'ENGINE_DISABLED':
      return feature === 'hpa'
        ? "HPA automation is off in this environment, so XamOps doesn't read HPA history here."
        : "The Workload autoscaler is off in this environment, so XamOps doesn't read usage here.";
    case 'CLUSTER_ACCESS_NOT_CONFIGURED':
      return "XamOps can't sign in to this cluster, so it can't read Prometheus there.";
    case 'CLUSTER_UNREACHABLE':
      return "XamOps can't reach this cluster's API server just now.";
    case 'NO_METRICS_SOURCE':
      return 'No Prometheus answered in this cluster, so there is no history to draw.';
    case 'METRICS_TIMEOUT':
      return 'Prometheus in the cluster took too long to answer. Try again in a minute.';
    case 'METRICS_UNREADABLE':
      return "Prometheus answered with something XamOps couldn't read.";
    case 'INSUFFICIENT_HISTORY':
      return "No pod of this workload is running now, so XamOps can't tell which pods to read.";
    default:
      return u.message ?? `Not available (${u.code}).`;
  }
}

/**
 * A read's state for its chart card, before the chart's own checks (coverage, empty series). A view
 * that carries `unavailable` (a source failure inside the cluster) is unavailable in words.
 */
export function insightCardState<T extends object>(
  read: InsightReadState<T>,
  feature: 'usage' | 'hpa' = 'usage',
): InsightCardState<T> {
  const r = read.result;
  if (!r) return read.loading ? { render: true, state: { kind: 'loading' }, view: null } : { render: false };
  switch (r.kind) {
    case 'not-deployed':
      return { render: false };
    case 'ok': {
      const unavailable = (r.view as { unavailable?: InsightUnavailable | null }).unavailable ?? null;
      return unavailable
        ? { render: true, state: { kind: 'unavailable', reason: unavailableWords(unavailable, feature) }, view: r.view }
        : { render: true, state: { kind: 'ok' }, view: r.view };
    }
    case 'cluster-not-registered':
      return { render: true, state: { kind: 'unavailable', reason: "This cluster isn't connected to XamOps yet, so there is no history to read." }, view: null };
    case 'refused': {
      const code = r.code.kind === 'known' ? r.code.code : null;
      return { render: true, state: { kind: 'unavailable', reason: (code && REFUSAL_WORDS[code]) ?? r.message }, view: null };
    }
    case 'unauthenticated':
      return { render: true, state: { kind: 'error', message: 'Your session has expired. Sign in again to see this chart.' }, view: null };
    default:
      return { render: true, state: { kind: 'error', message: `Couldn't read this chart's data: ${r.message}`, onRetry: read.reload }, view: null };
  }
}

// ─── Coverage: "Prometheus keeps {n} days here" (glossary §10) ───────────────

export interface CoverageInfo {
  /** The card's state from coverage alone. */
  state: ChartEmptyState;
  /** The span with no data at the start of the window, to hatch; null when there is none. */
  noDataBefore: number | null;
}

/**
 * From the view's coverage: drawn in full, partial with the glossary's words, or nothing to draw.
 * A late first sample means the history is shorter than the window ("No data before {time}");
 * samples missing only in the middle are gaps, and the note says so.
 */
export function coverageInfo(view: Pick<UsageView, 'start' | 'end' | 'stepSeconds' | 'coverage'>, formatTime: (ms: number) => string): CoverageInfo {
  const c = view.coverage;
  if (!c) return { state: { kind: 'ok' }, noDataBefore: null };
  if (c.firstSampleAt === null || c.covered === 0) {
    return { state: { kind: 'unavailable', reason: 'Prometheus in your cluster has no samples for this window yet.' }, noDataBefore: null };
  }
  if (c.covered === null || c.covered >= 0.999) return { state: { kind: 'ok' }, noDataBefore: null };
  const step = (view.stepSeconds ?? 0) * 1000;
  if (view.start !== null && view.end !== null && c.firstSampleAt > view.start + step / 2) {
    const kept = view.end - c.firstSampleAt;
    const days = Math.round(kept / DAY_MS);
    const words = days >= 1
      ? `Prometheus keeps ${days} ${days === 1 ? 'day' : 'days'} here.`
      : `Prometheus has ${Math.max(1, Math.round(kept / HOUR_MS))} hours here.`;
    return { state: { kind: 'partial', note: `${words} No data before ${formatTime(c.firstSampleAt)}.` }, noDataBefore: c.firstSampleAt };
  }
  return { state: { kind: 'partial', note: "Some points have no samples. They're left as gaps, never as zero." }, noDataBefore: null };
}

/** The hatched "No data before {time}" band over the categories before the first sample. */
export function noDataBand(rows: ReadonlyArray<{ t: number; x: string }>, firstSampleAt: number | null, label: string): TimeSeriesBand[] {
  if (firstSampleAt === null || rows.length === 0) return [];
  const before = rows.filter((r) => r.t < firstSampleAt);
  if (before.length === 0) return [];
  const last = rows.find((r) => r.t >= firstSampleAt) ?? before[before.length - 1];
  return [{ kind: 'noHistory', x1: before[0].x, x2: last.x, label }];
}

// ─── Rows ────────────────────────────────────────────────────────────────────

/** Every time any of the series has a point, oldest first. */
export function timesOf(series: ReadonlyArray<ReadonlyArray<SeriesPoint>>): number[] {
  const set = new Set<number>();
  for (const s of series) for (const p of s) set.add(p.t);
  return [...set].sort((a, b) => a - b);
}

/** A series as a lookup by time; a time it has no point for reads null. */
export const byTime = (points: ReadonlyArray<SeriesPoint> | null | undefined): Map<number, number | null> =>
  new Map((points ?? []).map((p) => [p.t, p.v]));

/** The point nearest `ms`, by time; null with no points. */
export function nearest<T extends { t: number }>(rows: readonly T[], ms: number): T | null {
  let best: T | null = null;
  for (const r of rows) if (!best || Math.abs(r.t - ms) < Math.abs(best.t - ms)) best = r;
  return best;
}

/** One cluster-scope point: the raw values (Prometheus units) and the stacked layers in the axis unit. */
export interface ClusterStackRow {
  t: number;
  x: string;
  used: number | null;
  requested: number | null;
  allocatable: number | null;
  capacity: number | null;
  /** Layers: used, requested − used, allocatable − requested; null at a point any of the three is missing. */
  layerUsed: number | null;
  layerReserved: number | null;
  layerFree: number | null;
  /** The total line, in the axis unit. */
  line: number | null;
}

/**
 * The cluster's used / reserved-unused / not-requested stack (design-delta §3.1): the layers add up
 * to allocatable. A point where use, requests or allocatable wasn't measured is a gap in every layer,
 * never a partial stack sitting on 0. Use above requests (CPU can burst) shows as used, with no
 * reserved-unused layer at that point.
 */
export function clusterStackRows(view: UsageView, unit: AxisUnit, totalKey: 'capacity' | 'allocatable'): ClusterStackRow[] {
  const get = (kind: string) => byTime(view.series.find((s) => s.kind === kind || s.key === kind)?.points);
  const used = get('used');
  const requested = get('request');
  const allocatable = get('allocatable');
  const capacity = get('capacity');
  const span = view.start !== null && view.end !== null ? view.end - view.start : 3 * DAY_MS;
  const times = timesOf(view.series.map((s) => s.points));
  return times.map((t) => {
    const u = used.get(t) ?? null;
    const r = requested.get(t) ?? null;
    const a = allocatable.get(t) ?? null;
    const c = capacity.get(t) ?? null;
    const complete = u !== null && r !== null && a !== null;
    const top = complete ? Math.max(u!, r!) : null;
    return {
      t,
      x: istTick(t, span),
      used: u,
      requested: r,
      allocatable: a,
      capacity: c,
      layerUsed: complete ? toAxis(u, unit) : null,
      layerReserved: complete ? toAxis(Math.max(0, r! - u!), unit) : null,
      layerFree: complete ? toAxis(Math.max(0, a! - top!), unit) : null,
      line: toAxis(totalKey === 'capacity' ? c : a, unit),
    };
  });
}

/** "monitoring/prometheus-server:80", or the type when the service isn't named. */
export const sourceService = (source: { type: string | null; service: string | null }): string =>
  source.service ? `Prometheus ${source.service}` : 'Prometheus in your cluster';
