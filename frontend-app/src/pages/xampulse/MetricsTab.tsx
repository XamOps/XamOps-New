import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useMetricCatalog, useMetricQuery } from '../../hooks/xampulse/useXamPulseQueries';
import { Card, EmptyState, ErrorState, SkeletonBlock } from '../../components/XamPulse/ui/ui';
import { ChartLegend, TimeSeriesChart, type ChartSeries } from '../../components/XamPulse/charts/TimeSeriesChart';
import { xampulseApi } from '../../api/xampulseApi';
import { XpApiError } from '../../api/xampulseErrors';
import type { XpAgg, XpMetricFilter, XpMetricQueryRequest, XpMetricSeries, XpScope } from '../../types/xampulse';

export interface SeriesRow { id: number; metric: string; agg: XpAgg; byHost: boolean; filter: string }
const AGGS: XpAgg[] = ['avg', 'min', 'max', 'sum', 'p50', 'p95', 'p99', 'rate', 'last'];
const DEFAULT_ROW: SeriesRow = { id: 1, metric: 'system.cpu.utilization', agg: 'avg', byHost: true, filter: '' };

/** "state=steal, mountpoint!=/boot, device=~nvme.*" -> filters. Entries without an operator are ignored. */
export function parseFilters(text: string): XpMetricFilter[] {
  return text.split(',').map((s) => s.trim()).filter(Boolean).flatMap((s): XpMetricFilter[] => {
    const m = /^([\w.]+)\s*(=~|!=|=)\s*(.+)$/.exec(s);
    return m ? [{ k: m[1], op: m[2] as XpMetricFilter['op'], v: m[3].trim() }] : [];
  });
}

export function buildMetricRequest(scope: XpScope, rows: SeriesRow[], compare: boolean, band: boolean): XpMetricQueryRequest | null {
  const valid = rows.filter((r) => r.metric.trim() !== '');
  if (valid.length === 0) return null;
  const spanSec = Math.round((Date.parse(scope.to) - Date.parse(scope.from)) / 1000);
  return {
    scope,
    series: valid.map((r) => ({ metric: r.metric.trim(), agg: r.agg, ...(r.byHost ? { groupBy: ['instance_id'] } : {}), ...(parseFilters(r.filter).length ? { filters: parseFilters(r.filter) } : {}) })),
    ...(band ? { band: 'ANOMALY' as const } : {}),
    ...(compare ? { compareOffsetSec: spanSec } : {}),
  };
}

const label = (s: XpMetricSeries): string => Object.keys(s.labels).length ? `${s.metric.replace('system.', '')} ${Object.keys(s.labels).map((k) => s.labels[k]).join(' ')}` : s.metric;

const MetricsTab: React.FC = () => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  const [rows, setRows] = useState<SeriesRow[]>([DEFAULT_ROW]);
  const [band, setBand] = useState(false);
  const [saved, setSaved] = useState(false);
  const catalog = useMetricCatalog(ctx.effectiveAccountIds[0], '');
  const req = useMemo(() => buildMetricRequest(ctx.scope, rows, ctx.compare, band), [ctx.scope, rows, ctx.compare, band]);
  const q = useMetricQuery(req);
  const unit = (m: string): string => catalog.data?.find((c) => c.name === m)?.unit ?? '';

  const chartSeries = useMemo<ChartSeries[]>(() => {
    const out: ChartSeries[] = [];
    (q.data?.series ?? []).slice(0, 24).forEach((s, i) => {
      out.push({ key: `s${i}`, name: label(s), points: s.points, band: s.band });
      if (s.compare) out.push({ key: `c${i}`, name: `${label(s)} (previous)`, points: s.compare, dashed: true });
    });
    return out;
  }, [q.data]);

  const update = (id: number, patch: Partial<SeriesRow>) => setRows((rs) => rs.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  const tooBroad = q.error instanceof XpApiError && q.error.code === 'XP_QUERY_TOO_BROAD';

  return (
    <>
      <div className="xp-row">
        <h1 className="xp-h1" style={{ fontSize: 22 }}>Metrics Explorer</h1>
        <span className="xp-spacer" />
        <button type="button" className="xp-btn" aria-pressed={ctx.compare} onClick={() => ctx.update({ compare: !ctx.compare })}>Compare to previous period</button>
        <button type="button" className="xp-btn" aria-pressed={band} onClick={() => setBand(!band)}>Normal range band</button>
      </div>
      <Card title="Series" id="xp-series">
        <datalist id="xp-metric-names">{(catalog.data ?? []).map((c) => <option key={c.name} value={c.name}>{c.unit}</option>)}</datalist>
        {rows.map((r, i) => (
          <div key={r.id} className="xp-row" style={{ marginBottom: 8 }}>
            <label className="xp-sr" htmlFor={`xp-m-${r.id}`}>Metric {i + 1}</label>
            <input id={`xp-m-${r.id}`} className="xp-input xp-input--mono" list="xp-metric-names" value={r.metric} onChange={(e) => update(r.id, { metric: e.target.value })} placeholder="Choose a metric" style={{ minWidth: 280 }} />
            <label className="xp-sr" htmlFor={`xp-a-${r.id}`}>Aggregation {i + 1}</label>
            <select id={`xp-a-${r.id}`} className="xp-select" value={r.agg} onChange={(e) => update(r.id, { agg: e.target.value as XpAgg })}>{AGGS.map((a) => <option key={a} value={a}>{a}</option>)}</select>
            <label style={{ display: 'flex', gap: 6 }}><input type="checkbox" checked={r.byHost} onChange={(e) => update(r.id, { byHost: e.target.checked })} />One line per host</label>
            <label className="xp-sr" htmlFor={`xp-f-${r.id}`}>Filters {i + 1}</label>
            <input id={`xp-f-${r.id}`} className="xp-input xp-input--mono" value={r.filter} onChange={(e) => update(r.id, { filter: e.target.value })} placeholder="state=steal, mountpoint=/var" style={{ minWidth: 220 }} />
            {rows.length > 1 && <button type="button" className="xp-btn xp-btn--ghost" onClick={() => setRows(rows.filter((x) => x.id !== r.id))} aria-label={`Remove series ${i + 1}`}>{'\u2715'}</button>}
          </div>
        ))}
        <div className="xp-row">
          <button type="button" className="xp-btn" disabled={rows.length >= 6} onClick={() => setRows([...rows, { ...DEFAULT_ROW, id: Math.max(...rows.map((x) => x.id)) + 1, metric: '' }])}>Add series</button>
          <button type="button" className="xp-btn" disabled={!req || saved || ctx.effectiveAccountIds.length === 0} onClick={() => { void xampulseApi.saveView(ctx.effectiveAccountIds[0], { name: `${rows[0].metric} ${rows[0].agg}`, kind: 'METRICS', shared: false, state: { rows } }).then(() => setSaved(true)).catch(() => setSaved(false)); }}>{saved ? 'View saved' : 'Save view'}</button>
          <button type="button" className="xp-btn" disabled={!req} onClick={() => navigate(ctx.link('/xampulse/alerts'), { state: { prefill: { metric: rows[0].metric, agg: rows[0].agg === 'p95' ? 'p95' : rows[0].agg === 'min' || rows[0].agg === 'max' || rows[0].agg === 'sum' ? rows[0].agg : 'avg' } } })}>Create alert from this selection</button>
        </div>
      </Card>
      <Card title="Chart" id="xp-mchart">
        {!req && <EmptyState title="Choose a metric" body="Pick one from the list. Try system.cpu.utilization or system.memory.utilization." />}
        {req && q.isLoading && <SkeletonBlock height={280} label="Querying metrics" />}
        {q.error && (tooBroad ? <div className="xp-banner xp-banner--warn">That time range is too wide. Pick 30 days or less.</div> : <ErrorState error={q.error} onRetry={() => { void q.refetch(); }} />)}
        {q.data && (chartSeries.length === 0 ? <EmptyState title="No data in this range" body="Try a longer range or another metric." /> : (
          <>
            <TimeSeriesChart series={chartSeries} height={300} ariaLabel="Metric chart" format={(v) => `${v.toFixed(1)}${unit(rows[0].metric) === '%' ? '%' : ''}`}
              onBrush={(a, b) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() })} fromMs={ctx.fromMs} toMs={ctx.toMs} />
            <ChartLegend items={chartSeries.map((s) => ({ name: s.name }))} />
            <p className="xp-muted" style={{ fontSize: 12, marginBottom: 0 }}>Drag on the chart to zoom. Answered in {q.data.tookMs} ms at {q.data.stepSec} s resolution.</p>
          </>
        ))}
      </Card>
    </>
  );
};

export default MetricsTab;
