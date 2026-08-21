import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import ReactDOM from 'react-dom';
import { useNavigate } from 'react-router-dom';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  ScatterChart, Scatter, Cell, ZAxis,
} from 'recharts';
import { Icon } from '@iconify/react';
import {
  useObservabilityData,
  TraceSummary,
  TraceSpan,
  TraceStats,
} from '../../hooks/useObservabilityData';
import ErrorNotice from './ErrorNotice';

// ── Constants ─────────────────────────────────────────────────────────────────

const TIME_RANGES = [
  { label: 'Last 15 min', ms: 15 * 60 * 1000 },
  { label: 'Last 1 hour', ms: 60 * 60 * 1000 },
  { label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { label: 'Last 24 hours', ms: 24 * 60 * 60 * 1000 },
  { label: 'Last 7 days', ms: 7 * 24 * 60 * 60 * 1000 },
];

const MIN_DURATIONS = [
  { label: 'Any duration', ms: 0 },
  { label: '≥ 100 ms', ms: 100 },
  { label: '≥ 500 ms', ms: 500 },
  { label: '≥ 1 s', ms: 1000 },
  { label: '≥ 3 s', ms: 3000 },
];

// Span kind — OTLP enum arrives stringified. Status red always wins over kind.
const KIND_META: Record<string, { label: string; color: string }> = {
  '1': { label: 'internal', color: '#8b5cf6' },
  '2': { label: 'server',   color: '#0b8f5a' },
  '3': { label: 'client',   color: '#3b82f6' },
  '4': { label: 'producer', color: '#f59e0b' },
  '5': { label: 'consumer', color: '#f59e0b' },
};

const ERROR_COLOR = '#ef4444';
const OK_DOT = 'bg-[#0b8f5a]';
const ERR_DOT = 'bg-[#ef4444]';

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtCount(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'K';
  return n.toLocaleString();
}

/** OpenObserve stores span durations in microseconds. */
function fmtDur(us?: number): string {
  const ms = (Number(us) || 0) / 1000;
  if (ms >= 60_000) return (ms / 60_000).toFixed(1) + ' min';
  if (ms >= 1000)   return (ms / 1000).toFixed(2) + ' s';
  if (ms >= 100)    return Math.round(ms) + ' ms';
  return ms.toFixed(1) + ' ms';
}

function startMsOf(t: { start_time?: number }): number {
  return Math.round((Number(t.start_time) || 0) / 1e6); // ns → ms
}

function isError(t: { span_status?: string; error_count?: number }): boolean {
  return t.span_status === 'ERROR' || Number(t.error_count) > 0;
}

// Histogram bucket width scaled to the queried window (mirrors LogsExplorer).
function histIntervalFor(spanMs: number): string {
  const HOUR = 3600_000, DAY = 24 * HOUR;
  if (spanMs <= HOUR)      return '1 minute';
  if (spanMs <= 3 * HOUR)  return '2 minute';
  if (spanMs <= 12 * HOUR) return '10 minute';
  if (spanMs <= 2 * DAY)   return '30 minute';
  return '2 hour';
}

// OpenObserve histogram bucket keys arrive either as epoch numbers or as UTC
// datetime strings without a timezone suffix — parse both to epoch ms.
function parseBucketTs(key: string): number {
  if (/^\d+$/.test(key)) {
    const n = Number(key);
    if (n > 1e15) return Math.round(n / 1000);
    if (n > 1e12) return n;
    return n * 1000;
  }
  let s = String(key).replace(' ', 'T');
  if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)) s += 'Z';
  const t = Date.parse(s);
  return Number.isNaN(t) ? 0 : t;
}

// ── Summary stat tile ─────────────────────────────────────────────────────────

interface StatTile {
  key: string;
  label: string;
  value: string;
  icon: string;
  accent: string;
  bg: string;
  sub?: string;
  clickable?: boolean;
  active?: boolean;
  onClick?: () => void;
}

function SummaryTile({ stat, index }: { stat: StatTile; index: number }) {
  return (
    <button
      type="button"
      onClick={stat.clickable ? stat.onClick : undefined}
      disabled={!stat.clickable}
      style={{ animationDelay: `${0.04 + index * 0.06}s` }}
      className={`group relative text-left obs-card obs-fade-up p-4 flex items-center gap-3 overflow-hidden ${
        stat.clickable ? 'obs-card-click' : 'cursor-default'
      }`}
    >
      {stat.active && (
        <span className="absolute inset-0 pointer-events-none rounded-[14px]"
          style={{ boxShadow: `inset 0 0 0 2px ${stat.accent}` }} />
      )}
      <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0"
        style={{ backgroundColor: stat.bg }}>
        <Icon icon={stat.icon} width={18} style={{ color: stat.accent }} />
      </div>
      <div className="min-w-0">
        <p className="obs-eyebrow truncate">{stat.label}</p>
        <p className="text-[20px] font-bold leading-7 text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {stat.value}
        </p>
        {stat.sub && <p className="text-[10px] text-[#94a3b8] leading-tight truncate">{stat.sub}</p>}
      </div>
      {stat.clickable && (
        <Icon icon="mdi:filter-variant" width={13}
          className="absolute top-2.5 right-2.5 text-[rgba(15,23,42,0.15)] group-hover:text-[rgba(15,23,42,0.4)] transition-colors" />
      )}
    </button>
  );
}

// ── Waterfall drawer ──────────────────────────────────────────────────────────

interface SpanRow {
  span: TraceSpan;
  depth: number;
}

/** Parent/child tree flattened depth-first, children ordered by start time. */
function flattenSpans(spans: TraceSpan[]): SpanRow[] {
  const byParent = new Map<string, TraceSpan[]>();
  const ids = new Set(spans.map(s => s.span_id));
  const roots: TraceSpan[] = [];
  for (const s of spans) {
    const parent = s.reference_parent_span_id;
    // Orphaned spans (parent outside the fetched window) render at root level.
    if (parent && ids.has(parent)) {
      if (!byParent.has(parent)) byParent.set(parent, []);
      byParent.get(parent)!.push(s);
    } else {
      roots.push(s);
    }
  }
  const byStart = (a: TraceSpan, b: TraceSpan) => (Number(a.start_time) || 0) - (Number(b.start_time) || 0);
  const out: SpanRow[] = [];
  const walk = (span: TraceSpan, depth: number) => {
    out.push({ span, depth });
    (byParent.get(span.span_id) || []).sort(byStart).forEach(c => walk(c, depth + 1));
  };
  roots.sort(byStart).forEach(r => walk(r, 0));
  return out;
}

function TraceDrawer({ trace, spans, loading, error, onClose }: {
  trace: TraceSummary | null;
  spans: TraceSpan[];
  loading: boolean;
  /** Set when the span fetch failed, so the waterfall doesn't read as "trace has no spans". */
  error?: string | null;
  onClose: () => void;
}) {
  const [expandedSpan, setExpandedSpan] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const navigate = useNavigate();

  useEffect(() => { setExpandedSpan(null); setCopied(false); }, [trace?.trace_id]);

  const rows = useMemo(() => flattenSpans(spans), [spans]);
  const t0 = useMemo(() => Math.min(...spans.map(s => Number(s.start_time) || Infinity)), [spans]);
  const t1 = useMemo(() => Math.max(...spans.map(s => Number(s.end_time) || 0)), [spans]);
  const total = Math.max(t1 - t0, 1);

  if (!trace) return null;

  const startMs = startMsOf(trace);
  const err = isError(trace);

  const copyTraceId = () => {
    navigator.clipboard?.writeText(trace.trace_id).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return ReactDOM.createPortal(
    <div className="obs-wrap obs-drawer-backdrop fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div
        className="obs-drawer w-full max-w-3xl h-full overflow-y-auto border-l border-[rgba(15,23,42,0.08)] bg-white p-6"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between mb-5">
          <h3 className="obs-h2 text-[16px]">Trace Details</h3>
          <button onClick={onClose}
            className="p-1.5 rounded-md text-[#94a3b8] hover:text-[#0b1220] hover:bg-[rgba(15,23,42,0.05)] transition-colors">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        {/* Identity */}
        <div className="mb-4">
          <div className="flex items-center gap-2.5 flex-wrap">
            <span className={`obs-lvl ${err ? 'obs-lvl-error' : 'obs-lvl-debug'}`}>{err ? 'ERROR' : 'OK'}</span>
            <p className="font-mono text-[14px] font-semibold text-[#0b1220] break-all">
              {String(trace.operation_name || 'trace')}
            </p>
          </div>
          <div className="flex items-center gap-4 mt-2 text-[12px] text-[#64748b]" style={{ fontVariantNumeric: 'tabular-nums' }}>
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:clock-outline" width={13} />
              {startMs ? new Date(startMs).toLocaleString() : '—'}
            </span>
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:timer-outline" width={13} />
              {fmtDur(trace.duration)}
            </span>
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:file-tree-outline" width={13} />
              {spans.length || Number(trace.span_count) || '—'} spans
            </span>
            <button
              type="button"
              onClick={copyTraceId}
              title="Copy trace ID"
              className="flex items-center gap-1.5 font-mono text-[11px] px-2 py-0.5 rounded bg-[rgba(15,23,42,0.04)] border border-[rgba(15,23,42,0.08)] text-[#64748b] hover:text-[#0b1220] hover:border-[rgba(15,23,42,0.25)] transition-colors">
              <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={11}
                className={copied ? 'text-[#0b8f5a]' : undefined} />
              {trace.trace_id.slice(0, 16)}…
            </button>
            {(() => {
              // Pivot to Logs for the same host, in a ±2-min window around this trace —
              // the practical way to see the exact log lines behind a request.
              const host = String((spans.find(s => (s as Record<string, unknown>).service_host_name) as Record<string, unknown> | undefined)?.service_host_name || '');
              if (!host || !startMs) return null;
              const w = 120_000;
              return (
                <button
                  type="button"
                  onClick={() => { navigate(`/observability/logs?host=${encodeURIComponent(host)}&from=${startMs - w}&to=${startMs + w}`); onClose(); }}
                  title={`View logs for ${host} around this trace`}
                  className="flex items-center gap-1.5 text-[11px] px-2 py-0.5 rounded bg-[rgba(37,99,235,0.06)] border border-[rgba(37,99,235,0.2)] text-[#2563EB] hover:bg-[rgba(37,99,235,0.1)] transition-colors">
                  <Icon icon="mdi:text-box-search-outline" width={12} /> View logs
                </button>
              );
            })()}
          </div>
          {err && String(trace.status_message || '') && (
            <div className="mt-3 rounded-lg px-3 py-2 text-[12px] font-mono bg-[rgba(239,68,68,0.06)] border border-[rgba(239,68,68,0.2)] text-[#b91c1c]">
              {String(trace.status_message)}
            </div>
          )}
        </div>

        {/* Kind legend */}
        <div className="flex items-center gap-4 mb-2 text-[11px] text-[#64748b]">
          <span className="obs-eyebrow mr-auto">Waterfall</span>
          {['2', '3', '1'].map(k => (
            <span key={k} className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-[3px] flex-shrink-0" style={{ backgroundColor: KIND_META[k].color }} />
              {KIND_META[k].label}
            </span>
          ))}
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-[3px] flex-shrink-0" style={{ backgroundColor: ERROR_COLOR }} />
            error
          </span>
        </div>

        {/* Waterfall */}
        {loading ? (
          <div className="flex items-center justify-center py-16"><div className="obs-spinner" /></div>
        ) : error ? (
          <ErrorNotice title="Could not load the waterfall" message={error} />
        ) : rows.length === 0 ? (
          <p className="text-[13px] text-[#94a3b8] py-8 text-center">No spans found for this trace.</p>
        ) : (
          <div className="rounded-lg border border-[rgba(15,23,42,0.08)] overflow-hidden">
            {rows.map(({ span, depth }) => {
              const sStart = Number(span.start_time) || t0;
              const sEnd = Number(span.end_time) || sStart;
              const left = ((sStart - t0) / total) * 100;
              const width = Math.max(((sEnd - sStart) / total) * 100, 0.4);
              const spanErr = span.span_status === 'ERROR';
              const kind = KIND_META[String(span.span_kind)] || KIND_META['1'];
              const color = spanErr ? ERROR_COLOR : kind.color;
              const expanded = expandedSpan === span.span_id;
              return (
                <div key={span.span_id} className="border-b border-[rgba(15,23,42,0.05)] last:border-b-0">
                  <div
                    onClick={() => setExpandedSpan(expanded ? null : span.span_id)}
                    className={`grid grid-cols-[minmax(0,17rem)_1fr_4.5rem] items-center gap-2 px-3 py-1.5 cursor-pointer transition-colors text-[12px] ${
                      expanded ? 'bg-[#f7f8fa]' : 'hover:bg-[rgba(15,23,42,0.025)]'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 min-w-0" style={{ paddingLeft: depth * 14 }}>
                      <Icon icon="mdi:chevron-right" width={12}
                        className={`flex-shrink-0 text-[#94a3b8] transition-transform ${expanded ? 'rotate-90' : ''}`} />
                      <span className="w-2 h-2 rounded-[3px] flex-shrink-0" style={{ backgroundColor: color }} />
                      <span className="font-mono truncate text-[#334155]" title={String(span.operation_name || span.span_id)}>
                        {String(span.operation_name || span.span_id)}
                      </span>
                    </div>
                    <div className="relative h-4 rounded bg-[rgba(15,23,42,0.03)]">
                      <div
                        className="absolute top-0.5 bottom-0.5 rounded-[3px]"
                        style={{ left: `${left}%`, width: `${width}%`, backgroundColor: color, opacity: 0.85 }}
                      />
                    </div>
                    <span className="text-right font-mono text-[11px] text-[#64748b]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {fmtDur(span.duration)}
                    </span>
                  </div>
                  {expanded && (
                    <div className="px-4 pb-3 pt-1 bg-[#f7f8fa]">
                      <div className="rounded-lg border border-[rgba(15,23,42,0.08)] overflow-hidden bg-white">
                        {Object.entries(span)
                          .filter(([, v]) => String(v ?? '') !== '' && String(v) !== '[]')
                          .map(([key, val]) => (
                            <div key={key} className="flex border-b border-[rgba(15,23,42,0.06)] last:border-b-0 text-[12px]">
                              <div className="w-52 flex-shrink-0 px-3 py-1.5 font-medium break-words bg-[#f7f8fa] text-[#64748b]">
                                {key}
                              </div>
                              <div className="flex-1 min-w-0 px-3 py-1.5 font-mono break-all text-[#334155]">
                                {String(val)}
                              </div>
                            </div>
                          ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}

// ── Empty / onboarding state ──────────────────────────────────────────────────

function TracesOnboarding() {
  const navigate = useNavigate();
  return (
    <div className="obs-card obs-fade-up p-12 flex flex-col items-center gap-3 text-center">
      <div className="w-12 h-12 rounded-xl flex items-center justify-center bg-[rgba(27,230,139,0.12)]">
        <Icon icon="mdi:graph-outline" width={24} className="text-[#0b8f5a]" />
      </div>
      <p className="obs-h2 text-[15px]">No trace data yet</p>
      <p className="text-[13px] max-w-md text-[#64748b] leading-relaxed">
        Traces are emitted by your applications, not by the host agent. Instrument a service
        with the OpenTelemetry SDK — the collector XamOps deploys on your instances already
        listens for OTLP on <span className="font-mono text-[12px] bg-[rgba(15,23,42,0.05)] px-1.5 py-0.5 rounded">localhost:4317</span> and
        forwards spans here automatically.
      </p>
      <div className="flex items-center gap-2 mt-2">
        <button type="button" onClick={() => navigate('/observability/setup')} className="obs-btn obs-btn-primary">
          <Icon icon="mdi:rocket-launch-outline" width={15} /> Open Setup
        </button>
        <a
          href="https://opentelemetry.io/docs/languages/"
          target="_blank" rel="noreferrer"
          className="obs-btn obs-btn-ghost">
          OTel SDK docs <Icon icon="mdi:open-in-new" width={13} />
        </a>
      </div>
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

const TracesExplorer: React.FC = () => {
  const { activeAccountId, tracesError, searchTraces, fetchTraceStats, fetchTraceSpans } = useObservabilityData();

  // Filters
  const [keyword, setKeyword] = useState('');
  const [service, setService] = useState('');
  const [host, setHost] = useState('');
  const [minDur, setMinDur] = useState(0);
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [timeRange, setTimeRange] = useState(TIME_RANGES[2]); // 6 hours
  const [searchNonce, setSearchNonce] = useState(0);

  // Live auto-refresh — keeps the end-time current so new traces appear without
  // a manual Search (the Traces query is otherwise a fixed snapshot at page load).
  const [live, setLive] = useState(true);
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());

  // Data
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadedOnce, setLoadedOnce] = useState(false);

  // Chart view
  const [chartView, setChartView] = useState<'volume' | 'latency'>('volume');

  // Drawer
  const [selected, setSelected] = useState<TraceSummary | null>(null);
  const [spans, setSpans] = useState<TraceSpan[]>([]);
  const [spansLoading, setSpansLoading] = useState(false);
  /** Why the waterfall is empty, when it's empty because the request failed. */
  const [spansError, setSpansError] = useState<string | null>(null);

  const keywordRef = useRef(keyword);
  keywordRef.current = keyword;

  // `silent` skips the full-page spinner so live auto-refreshes don't flicker.
  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    const end = Date.now();
    const start = end - timeRange.ms;
    const common = {
      keyword: keywordRef.current || undefined,
      service: service || undefined,
      host: host || undefined,
      minDurationMs: minDur || undefined,
      errorsOnly: errorsOnly || undefined,
      startTime: start,
      endTime: end,
    };
    const [t, s] = await Promise.all([
      searchTraces({ ...common, size: 100 }),
      fetchTraceStats({ ...common, interval: histIntervalFor(timeRange.ms) }),
    ]);
    setTraces(t);
    setStats(s);
    setLoading(false);
    setLoadedOnce(true);
    setLastRefresh(new Date());
  }, [service, host, minDur, errorsOnly, timeRange, searchTraces, fetchTraceStats]);

  useEffect(() => { load(); }, [load, searchNonce, activeAccountId]);

  // Live mode: silently re-query every 15s so newly-ingested traces show up.
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => load(true), 15_000);
    return () => clearInterval(id);
  }, [live, load]);

  const openTrace = useCallback(async (trace: TraceSummary) => {
    setSelected(trace);
    setSpans([]);
    setSpansError(null);
    setSpansLoading(true);
    const startMs = startMsOf(trace);
    const durMs = (Number(trace.duration) || 0) / 1000;
    const found = await fetchTraceSpans(
      trace.trace_id,
      startMs - 5 * 60_000,
      startMs + Math.max(durMs, 1000) + 5 * 60_000,
    );
    setSpans(found.data);
    setSpansError(found.error ?? null);
    setSpansLoading(false);
  }, [fetchTraceSpans]);

  // ── Derived data ────────────────────────────────────────────────────────────

  const summary = stats?.summary || {};
  const totalReq = Number(summary.n) || 0;
  const totalErr = Number(summary.errors) || 0;
  const errRate = totalReq > 0 ? (totalErr / totalReq) * 100 : 0;

  const tiles: StatTile[] = [
    {
      key: 'requests', label: 'Requests', value: fmtCount(totalReq),
      icon: 'mdi:swap-horizontal', accent: '#0b8f5a', bg: 'rgba(27,230,139,0.12)',
      sub: `over ${timeRange.label.toLowerCase().replace('last ', '')}`,
    },
    {
      key: 'errors', label: 'Errors', value: fmtCount(totalErr),
      icon: 'mdi:alert-circle-outline', accent: '#ef4444', bg: 'rgba(239,68,68,0.08)',
      sub: totalReq > 0 ? `${errRate.toFixed(1)}% of requests` : undefined,
      clickable: true, active: errorsOnly,
      onClick: () => setErrorsOnly(v => !v),
    },
    {
      key: 'p50', label: 'P50 Latency', value: fmtDur(summary.p50),
      icon: 'mdi:speedometer-slow', accent: '#3b82f6', bg: 'rgba(59,130,246,0.08)',
    },
    {
      key: 'p95', label: 'P95 Latency', value: fmtDur(summary.p95),
      icon: 'mdi:speedometer-medium', accent: '#b45309', bg: 'rgba(245,158,11,0.1)',
    },
    {
      key: 'p99', label: 'P99 Latency', value: fmtDur(summary.p99),
      icon: 'mdi:speedometer', accent: '#6d28d9', bg: 'rgba(139,92,246,0.08)',
    },
  ];

  const histData = useMemo(() =>
    (stats?.histogram || []).map(h => ({
      ts: parseBucketTs(String(h.key)),
      total: Number(h.total) || 0,
      errors: Number(h.errors) || 0,
    })).filter(h => h.ts > 0),
  [stats]);

  const histHasErrors = histData.some(h => h.errors > 0);
  const spansDays = timeRange.ms > 24 * 3600_000;

  const scatterData = useMemo(() =>
    traces.map(t => ({
      x: startMsOf(t),
      y: (Number(t.duration) || 0) / 1000, // ms
      err: isError(t),
      trace: t,
    })).filter(d => d.x > 0),
  [traces]);

  const maxDurUs = useMemo(() =>
    Math.max(...traces.map(t => Number(t.duration) || 0), 1),
  [traces]);

  const operations = stats?.operations || [];
  const services = stats?.services || [];
  const hosts = stats?.hosts || [];
  const hasAnyData = totalReq > 0 || traces.length > 0 || services.length > 0;
  const noFilters = !keyword && !service && !host && !minDur && !errorsOnly;

  const fmtAxisTime = (ts: number) => spansDays
    ? new Date(ts).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  // First load
  if (!loadedOnce && loading) {
    return (
      <div className="obs-wrap flex items-center justify-center py-24">
        <div className="obs-spinner" />
      </div>
    );
  }

  // No data at all in range with no filters → onboarding.
  // Guarded on `tracesError`: a failed query also produces zero traces, and telling someone
  // whose collector is working fine to go and install a collector is worse than telling them
  // nothing. When the request failed we fall through and report the failure instead.
  if (loadedOnce && !hasAnyData && noFilters && !tracesError) {
    return <TracesOnboarding />;
  }

  return (
    <div className="obs-wrap space-y-4">

      {tracesError && (
        <ErrorNotice
          title="Could not load traces"
          message={tracesError}
          onRetry={() => setSearchNonce(n => n + 1)}
        />
      )}

      {/* Summary tiles */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        {tiles.map((stat, i) => <SummaryTile key={stat.key} stat={stat} index={i} />)}
      </div>

      {/* Filter bar */}
      <div className="obs-card p-4">
        <form
          onSubmit={e => { e.preventDefault(); setSearchNonce(n => n + 1); }}
          className="flex flex-wrap gap-3 items-end"
        >
          <div className="flex flex-col gap-1 flex-1 min-w-[220px]">
            <label className="obs-label">Operation</label>
            <div className="relative">
              <Icon icon="mdi:magnify" width={15}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[#94a3b8] pointer-events-none" />
              <input
                type="text"
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                placeholder="Filter by operation name, e.g. /api/finops"
                className="obs-input w-full !pl-8"
              />
            </div>
          </div>
          <div className="flex flex-col gap-1">
            <label className="obs-label">Service</label>
            <select value={service} onChange={e => setService(e.target.value)} className="obs-input !pr-8">
              <option value="">All services</option>
              {service && !services.some(s => s.service_name === service) && (
                <option value={service}>{service}</option>
              )}
              {services.map(s => (
                <option key={s.service_name} value={s.service_name}>{s.service_name}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="obs-label">Host</label>
            <select value={host} onChange={e => setHost(e.target.value)} className="obs-input !pr-8" title="Filter traces to a single EC2 / host">
              <option value="">All hosts</option>
              {host && !hosts.some(h => h.service_host_name === host) && (
                <option value={host}>{host}</option>
              )}
              {hosts.map(h => (
                <option key={h.service_host_name} value={h.service_host_name}>
                  {h.service_host_name.includes('.') ? h.service_host_name.slice(0, h.service_host_name.indexOf('.')) : h.service_host_name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="obs-label">Duration</label>
            <select value={minDur} onChange={e => setMinDur(Number(e.target.value))} className="obs-input !pr-8">
              {MIN_DURATIONS.map(d => <option key={d.ms} value={d.ms}>{d.label}</option>)}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="obs-label">Time Range</label>
            <select
              value={timeRange.label}
              onChange={e => setTimeRange(TIME_RANGES.find(t => t.label === e.target.value) || TIME_RANGES[2])}
              className="obs-input !pr-8"
            >
              {TIME_RANGES.map(t => <option key={t.label} value={t.label}>{t.label}</option>)}
            </select>
          </div>
          <button
            type="button"
            onClick={() => setErrorsOnly(v => !v)}
            className={`obs-toggle ${errorsOnly ? 'on' : ''}`}
            style={{ padding: '0.5rem 0.75rem' }}
          >
            <Icon icon="mdi:alert-circle-outline" width={14} /> Errors only
          </button>
          <button
            type="button"
            onClick={() => setLive(v => !v)}
            title={live ? 'Auto-refreshing every 15s — click to pause' : 'Paused — click to auto-refresh'}
            className={`obs-toggle ${live ? 'on' : ''}`}
            style={{ padding: '0.5rem 0.75rem' }}
          >
            <span className={`inline-block w-2 h-2 rounded-full ${live ? 'bg-emerald-500 animate-pulse' : 'bg-[#94a3b8]'}`} />
            {live ? 'Live' : 'Paused'}
          </button>
          <button type="submit" className="obs-btn obs-btn-primary" disabled={loading}>
            {loading
              ? <span className="obs-spinner obs-spinner-sm !border-t-[#04231a]" />
              : <Icon icon="mdi:magnify" width={15} />}
            Search
          </button>
        </form>
        <p className="mt-2 text-[11px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
          Updated {lastRefresh.toLocaleTimeString()}{live ? ' · auto-refresh 15s' : ''}
        </p>
      </div>

      {/* Chart + top operations rail */}
      <div className="obs-card obs-fade-up p-4">
        <div className="flex flex-col lg:flex-row gap-4">

          {/* Chart area */}
          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
              <div>
                <p className="obs-h2 text-[14px] leading-tight">
                  {chartView === 'volume' ? 'Request Volume' : 'Latency Distribution'}
                </p>
                <p className="text-[11px] text-[#94a3b8]">
                  {chartView === 'volume'
                    ? `Traces over ${timeRange.label.toLowerCase()}`
                    : 'Each dot is a trace — click one to open its waterfall'}
                </p>
              </div>
              <div className="flex items-center gap-4">
                <div className="flex items-center gap-3 text-[11px] text-[#64748b]">
                  <span className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: '#0b8f5a' }} /> OK
                  </span>
                  <span className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: ERROR_COLOR }} /> Errors
                  </span>
                </div>
                <div className="obs-seg">
                  <button type="button" onClick={() => setChartView('volume')}
                    className={`obs-seg-btn ${chartView === 'volume' ? 'active' : ''}`}>
                    <Icon icon="mdi:chart-areaspline" width={13} /> Volume
                  </button>
                  <button type="button" onClick={() => setChartView('latency')}
                    className={`obs-seg-btn ${chartView === 'latency' ? 'active' : ''}`}>
                    <Icon icon="mdi:chart-scatter-plot" width={13} /> Latency
                  </button>
                </div>
              </div>
            </div>

            {chartView === 'volume' ? (
              <ResponsiveContainer width="100%" height={180}>
                <AreaChart data={histData} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
                  <defs>
                    <linearGradient id="traceVolGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#1be68b" stopOpacity={0.32} />
                      <stop offset="60%" stopColor="#1be68b" stopOpacity={0.07} />
                      <stop offset="100%" stopColor="#1be68b" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="traceErrGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#ef4444" stopOpacity={0.35} />
                      <stop offset="100%" stopColor="#ef4444" stopOpacity={0.04} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(15,23,42,0.05)" vertical={false} />
                  <XAxis dataKey="ts" tickFormatter={fmtAxisTime}
                    tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false}
                    minTickGap={spansDays ? 72 : 40} />
                  <YAxis tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={34}
                    tickFormatter={(v: number) => fmtCount(v)} allowDecimals={false} />
                  <Tooltip
                    contentStyle={{ background: '#fff', border: '1px solid rgba(15,23,42,0.14)', borderRadius: 8, fontSize: 12, color: '#0b1220', boxShadow: '0 12px 28px -12px rgba(15,23,42,0.25)' }}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    formatter={(v: any, name: any) => [Number(v).toLocaleString(), name]}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    labelFormatter={(ts: any) => new Date(Number(ts)).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                  />
                  <Area type="monotone" dataKey="total" name="Traces" stroke="#0b8f5a" strokeWidth={2.25}
                    strokeLinecap="round" strokeLinejoin="round" fill="url(#traceVolGrad)"
                    dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
                  {histHasErrors && (
                    <Area type="monotone" dataKey="errors" name="Errors" stroke="#ef4444" strokeWidth={2}
                      strokeLinecap="round" strokeLinejoin="round" fill="url(#traceErrGrad)"
                      dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
                  )}
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <ResponsiveContainer width="100%" height={180}>
                <ScatterChart margin={{ top: 8, right: 4, bottom: 0, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(15,23,42,0.05)" vertical={false} />
                  <XAxis type="number" dataKey="x" domain={['dataMin', 'dataMax']}
                    tickFormatter={fmtAxisTime}
                    tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false}
                    minTickGap={spansDays ? 72 : 40} />
                  <YAxis type="number" dataKey="y"
                    tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={44}
                    tickFormatter={(v: number) => v >= 1000 ? (v / 1000).toFixed(1) + 's' : Math.round(v) + 'ms'} />
                  <ZAxis range={[36, 36]} />
                  <Tooltip
                    cursor={{ strokeDasharray: '3 3', stroke: 'rgba(15,23,42,0.2)' }}
                    contentStyle={{ background: '#fff', border: '1px solid rgba(15,23,42,0.14)', borderRadius: 8, fontSize: 12, color: '#0b1220', boxShadow: '0 12px 28px -12px rgba(15,23,42,0.25)' }}
                    content={({ active, payload }) => {
                      if (!active || !payload?.length) return null;
                      const d = payload[0].payload as { trace: TraceSummary; y: number; err: boolean };
                      return (
                        <div className="bg-white border border-[rgba(15,23,42,0.14)] rounded-lg px-3 py-2 text-[12px] shadow-lg">
                          <p className="font-mono font-semibold text-[#0b1220] max-w-[260px] truncate">
                            {String(d.trace.operation_name || d.trace.trace_id)}
                          </p>
                          <p className="text-[#64748b] mt-0.5" style={{ fontVariantNumeric: 'tabular-nums' }}>
                            {fmtDur(d.trace.duration)} · {new Date(startMsOf(d.trace)).toLocaleTimeString()}
                            {d.err && <span className="text-[#b91c1c] font-semibold"> · ERROR</span>}
                          </p>
                          <p className="text-[10px] text-[#94a3b8] mt-0.5">Click to open waterfall</p>
                        </div>
                      );
                    }}
                  />
                  <Scatter
                    data={scatterData}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    onClick={(d: any) => { const t = d?.payload?.trace ?? d?.trace; if (t) openTrace(t); }}
                    style={{ cursor: 'pointer' }}
                  >
                    {scatterData.map((d, i) => (
                      <Cell key={i}
                        fill={d.err ? 'rgba(239,68,68,0.75)' : 'rgba(11,143,90,0.45)'}
                        stroke={d.err ? '#b91c1c' : '#0b8f5a'} strokeWidth={1} />
                    ))}
                  </Scatter>
                </ScatterChart>
              </ResponsiveContainer>
            )}
          </div>

          {/* Top operations rail */}
          <div className="lg:w-80 flex-shrink-0 lg:border-l lg:border-[rgba(15,23,42,0.08)] lg:pl-4">
            <p className="obs-eyebrow mb-2">Top Operations</p>
            {operations.length === 0 ? (
              <p className="text-[12px] text-[#94a3b8] py-4">No operations in this window.</p>
            ) : (
              <div className="space-y-1">
                {operations.slice(0, 6).map(op => (
                  <button
                    key={`${op.service_name}|${op.operation_name}`}
                    type="button"
                    onClick={() => { setKeyword(op.operation_name); setSearchNonce(n => n + 1); }}
                    title={`Filter traces to ${op.operation_name}`}
                    className="w-full text-left px-2.5 py-2 rounded-lg hover:bg-[rgba(15,23,42,0.03)] transition-colors group"
                  >
                    <div className="flex items-center gap-2">
                      <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${Number(op.errors) > 0 ? ERR_DOT : OK_DOT}`} />
                      <span className="font-mono text-[12px] text-[#334155] truncate flex-1 group-hover:text-[#0b1220]">
                        {op.operation_name}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-0.5 pl-3.5 text-[10.5px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      <span>{fmtCount(Number(op.n))} req</span>
                      <span>p95 {fmtDur(op.p95)}</span>
                      {Number(op.errors) > 0 && (
                        <span className="text-[#b91c1c] font-semibold">{fmtCount(Number(op.errors))} err</span>
                      )}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Trace list */}
      <div className="obs-card obs-fade-up overflow-hidden">
        <div className="flex items-center gap-3 px-4 py-3 border-b border-[rgba(15,23,42,0.06)]">
          <p className="obs-h2 text-[14px]">Traces</p>
          <span className="text-[11px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {traces.length === 100 ? 'newest 100' : `${traces.length} result${traces.length === 1 ? '' : 's'}`}
          </span>
          {loading && <span className="obs-spinner obs-spinner-sm ml-auto" />}
        </div>

        {/* Column headers */}
        <div className="hidden md:grid grid-cols-[5.5rem_4rem_minmax(0,1fr)_10rem_4rem_4rem] items-center gap-3 px-4 py-2 border-b border-[rgba(15,23,42,0.06)] bg-[#fafbfc]">
          {['Time', 'Status', 'Operation', 'Duration', 'Spans', 'HTTP'].map(h => (
            <span key={h} className={`obs-eyebrow text-[10px] ${h === 'Duration' ? 'text-right' : ''} ${h === 'Spans' || h === 'HTTP' ? 'text-center' : ''}`}>{h}</span>
          ))}
        </div>

        {traces.length === 0 && !loading && tracesError ? (
          <div className="px-4 py-12 text-center">
            <p className="text-[13px] text-[#64748b]">
              Trace results unavailable — see the message above.
            </p>
          </div>
        ) : traces.length === 0 && !loading ? (
          <div className="px-4 py-12 text-center">
            <p className="text-[13px] text-[#64748b]">No traces match the current filters.</p>
            <button
              type="button"
              onClick={() => { setKeyword(''); setService(''); setMinDur(0); setErrorsOnly(false); setSearchNonce(n => n + 1); }}
              className="obs-btn obs-btn-soft obs-btn-sm mt-3">
              Clear filters
            </button>
          </div>
        ) : (
          <div>
            {traces.map(t => {
              const err = isError(t);
              const startMs = startMsOf(t);
              const durPct = Math.max((Number(t.duration) || 0) / maxDurUs * 100, 2);
              return (
                <div
                  key={t.trace_id}
                  onClick={() => openTrace(t)}
                  className="grid grid-cols-[5.5rem_4rem_minmax(0,1fr)_10rem_4rem_4rem] items-center gap-3 px-4 py-2.5 border-b border-[rgba(15,23,42,0.04)] last:border-b-0 cursor-pointer text-[13px] hover:bg-[rgba(15,23,42,0.025)] transition-colors"
                >
                  <span className="font-mono text-[11.5px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {startMs
                      ? spansDays
                        ? new Date(startMs).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
                        : new Date(startMs).toLocaleTimeString()
                      : '—'}
                  </span>
                  <span className={`obs-lvl ${err ? 'obs-lvl-error' : 'obs-lvl-debug'} justify-self-start`}>
                    {err ? 'ERROR' : 'OK'}
                  </span>
                  <div className="min-w-0 flex items-center gap-2">
                    <span className="font-mono truncate text-[#334155]" title={String(t.operation_name || t.trace_id)}>
                      {String(t.operation_name || t.trace_id)}
                    </span>
                    {t.service_name && (
                      <span className="flex-shrink-0 text-[10.5px] px-1.5 py-0.5 rounded bg-[rgba(15,23,42,0.04)] text-[#64748b] hidden lg:inline">
                        {String(t.service_name)}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center justify-end gap-2">
                    <div className="w-16 h-1 rounded-full bg-[rgba(15,23,42,0.05)] overflow-hidden hidden md:block">
                      <div className="h-full rounded-full"
                        style={{ width: `${durPct}%`, backgroundColor: err ? ERROR_COLOR : '#0b8f5a', opacity: 0.75 }} />
                    </div>
                    <span className="font-mono text-[12px] text-[#334155] w-16 text-right" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {fmtDur(t.duration)}
                    </span>
                  </div>
                  <span className="text-center font-mono text-[12px] text-[#64748b]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {t.span_count != null ? `×${t.span_count}` : '—'}
                  </span>
                  <span className="text-center font-mono text-[12px] text-[#64748b]">
                    {t.http_status_code ? String(t.http_status_code) : '—'}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Drawer */}
      <TraceDrawer
        trace={selected}
        spans={spans}
        loading={spansLoading}
        error={spansError}
        onClose={() => setSelected(null)}
      />
    </div>
  );
};

export default TracesExplorer;
