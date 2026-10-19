/**
 * Adapts the Phase-2 time series (FEAT-004: `UsageSeriesDto`, `SparklinesDto`, `HpaHistoryDto`) into
 * the views the charts read.
 *
 * Rules (CLAUDE.md, glossary rule 4):
 *  - a point nothing measured stays `null`: the chart draws a gap there, never 0;
 *  - a point whose time can't be read is dropped, never placed at a guessed time;
 *  - `unavailable` passes through as the server sent it, so the card can say why in words;
 *  - nothing is resampled, interpolated or invented: the server picks the step.
 */
import type {
  HpaAtMaxView,
  HpaHistoryResponseWire,
  HpaHistorySeriesView,
  HpaHistoryView,
  HpaReplicaChange,
  InsightSource,
  InsightSourceWire,
  InsightUnavailable,
  InsightUnavailableWire,
  SeriesPoint,
  SparklinesResponseWire,
  SparklinesView,
  SparklineView,
  UsageNamespaceView,
  UsageResponseWire,
  UsageSeriesView,
  UsageSeriesWire,
  UsageView,
  WorkloadRefWire,
} from '../types/automationInsights';

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);

/** An instant (nanoseconds and all) as epoch ms, or null when missing or unreadable. */
export function instantOf(v: unknown): number | null {
  const s = text(v);
  if (!s) return null;
  const ms = Date.parse(s.replace(/(\.\d{3})\d+/, '$1'));
  return Number.isFinite(ms) ? ms : null;
}

/**
 * `[[epochSeconds, value | null], …]` as points in epoch ms. A value that isn't a finite number is a
 * gap (`null`); an entry whose time isn't a finite number is dropped.
 */
export function adaptPoints(raw: unknown): SeriesPoint[] {
  if (!Array.isArray(raw)) return [];
  const out: SeriesPoint[] = [];
  for (const p of raw) {
    if (!Array.isArray(p) || p.length < 1) continue;
    const t = num(p[0]);
    if (t === null) continue;
    out.push({ t: t * 1000, v: num(p[1]) });
  }
  return out;
}

export function adaptSource(wire: InsightSourceWire | null | undefined): InsightSource {
  return { type: text(wire?.type), service: text(wire?.service) };
}

export function adaptUnavailable(wire: InsightUnavailableWire | null | undefined): InsightUnavailable | null {
  if (!wire || typeof wire !== 'object') return null;
  const code = text(wire.code);
  const message = text(wire.message);
  if (!code && !message) return null;
  return { code: code ?? 'UNKNOWN', message };
}

function adaptRef(wire: WorkloadRefWire | null | undefined): { namespace: string; kind: string; name: string } | null {
  const namespace = text(wire?.namespace);
  const kind = text(wire?.kind);
  const name = text(wire?.name);
  return namespace && kind && name ? { namespace, kind, name } : null;
}

function adaptSeries(wire: UsageSeriesWire | null | undefined): UsageSeriesView | null {
  if (!wire || typeof wire !== 'object') return null;
  const kind = text(wire.kind);
  const key = text(wire.key) ?? kind;
  if (!key) return null;
  return { key, kind: kind ?? key, label: text(wire.label), points: adaptPoints(wire.points), query: text(wire.query) };
}

const seriesList = (raw: unknown): UsageSeriesView[] =>
  Array.isArray(raw) ? raw.map((s) => adaptSeries(s as UsageSeriesWire)).filter((s): s is UsageSeriesView => s !== null) : [];

export function adaptUsage(wire: UsageResponseWire): UsageView {
  const namespaces: UsageNamespaceView[] | null = Array.isArray(wire.namespaces)
    ? wire.namespaces.flatMap((n) => {
      const namespace = text(n?.namespace);
      if (!n || !namespace) return [];
      return [{ namespace, requestedNow: num(n.requestedNow), usedAvg: num(n.usedAvg), series: seriesList(n.series) }];
    })
    : null;
  const coverage = wire.coverage && typeof wire.coverage === 'object'
    ? { firstSampleAt: instantOf(wire.coverage.firstSampleAt), covered: num(wire.coverage.covered) }
    : null;
  return {
    scope: text(wire.scope),
    resource: text(wire.resource),
    unit: text(wire.unit),
    workloadRef: adaptRef(wire.workloadRef),
    window: text(wire.window),
    stepSeconds: num(wire.stepSeconds),
    start: instantOf(wire.start),
    end: instantOf(wire.end),
    source: adaptSource(wire.source),
    series: seriesList(wire.series),
    namespaces,
    coverage,
    unavailable: adaptUnavailable(wire.unavailable),
  };
}

export function adaptSparklines(wire: SparklinesResponseWire): SparklinesView {
  const workloads: SparklineView[] = Array.isArray(wire.workloads)
    ? wire.workloads.flatMap((w) => {
      const ref = adaptRef(w?.ref);
      if (!w || !ref) return [];
      return [{ id: `${ref.namespace}/${ref.kind}/${ref.name}`, ref, used: adaptPoints(w.used), request: adaptPoints(w.request) }];
    })
    : [];
  const queries = wire.queries && typeof wire.queries === 'object'
    ? { used: text(wire.queries.used), request: text(wire.queries.request) }
    : null;
  return {
    resource: text(wire.resource),
    unit: text(wire.unit),
    window: text(wire.window),
    stepSeconds: num(wire.stepSeconds),
    start: instantOf(wire.start),
    end: instantOf(wire.end),
    source: adaptSource(wire.source),
    queries,
    workloads,
    unavailable: adaptUnavailable(wire.unavailable),
  };
}

export function adaptHpaHistory(wire: HpaHistoryResponseWire): HpaHistoryView {
  const series: HpaHistorySeriesView[] = Array.isArray(wire.series)
    ? wire.series.flatMap((s) => {
      const key = text(s?.key);
      if (!s || !key) return [];
      return [{ key, label: text(s.label), unit: text(s.unit), points: adaptPoints(s.points), query: text(s.query) }];
    })
    : [];
  const a = wire.atMax;
  const atMax: HpaAtMaxView | null = a && typeof a === 'object'
    ? {
      share: num(a.share),
      minutesAtMax: num(a.minutesAtMax),
      minutesMeasured: num(a.minutesMeasured),
      runs: Array.isArray(a.runs)
        ? a.runs.flatMap((r) => {
          const from = instantOf(r?.from);
          return from === null ? [] : [{ from, to: instantOf(r?.to) }];
        })
        : [],
      runsTruncated: a.runsTruncated === true,
      since: instantOf(a.since),
      stepSeconds: num(a.stepSeconds),
      query: text(a.query),
    }
    : null;
  const changes: HpaReplicaChange[] = Array.isArray(wire.changes)
    ? wire.changes.flatMap((c) => {
      const at = instantOf(c?.at);
      const from = num(c?.from);
      const to = num(c?.to);
      return at === null || from === null || to === null ? [] : [{ at, from, to }];
    })
    : [];
  return {
    namespace: text(wire.namespace),
    name: text(wire.name),
    window: text(wire.window),
    stepSeconds: num(wire.stepSeconds),
    start: instantOf(wire.start),
    end: instantOf(wire.end),
    source: adaptSource(wire.source),
    series,
    atMax,
    changes,
    changesTruncated: wire.changesTruncated === true,
    firstSampleAt: instantOf(wire.firstSampleAt),
    unavailable: adaptUnavailable(wire.unavailable),
  };
}

/** The series of a view by its key or kind, or null when the server sent none. */
export function seriesByKey<T extends { key: string }>(series: readonly T[], key: string): T | null {
  return series.find((s) => s.key === key) ?? null;
}
