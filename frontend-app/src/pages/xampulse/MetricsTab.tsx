import React, { useMemo, useState } from 'react';
import { ChartLine, Plus, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useMetricCatalog, useMetricQuery } from '../../hooks/xampulse/useXamPulseQueries';
import { Card, EmptyState, ErrorState, SkeletonBlock, PageHead } from '../../components/XamPulse/ui/ui';
import { SERIES_COLORS, TimeSeriesChart, type ChartSeries } from '../../components/XamPulse/charts/TimeSeriesChart';
import { xampulseApi } from '../../api/xampulseApi';
import { XpApiError } from '../../api/xampulseErrors';
import type { XpAgg, XpMetricFilter, XpMetricQueryRequest, XpMetricSeries, XpScope } from '../../types/xampulse';

/** splitBy: a label to draw one line per value of (state, direction, mountpoint...), on top of one line per host. */
export interface SeriesRow { id: number; metric: string; agg: XpAgg; byHost: boolean; filter: string; splitBy?: string }
const AGGS: XpAgg[] = ['avg', 'min', 'max', 'sum', 'p50', 'p95', 'p99', 'rate', 'last'];
/** CPU split by state (user, system, iowait, steal, idle...): averaging all states into one line always reads about 1/8. */
const DEFAULT_ROW: SeriesRow = { id: 1, metric: 'system.cpu.utilization', agg: 'avg', byHost: false, filter: '', splitBy: 'state' };

/**
 * Labels that partition a metric (CPU time by state, traffic by direction): combining their series into one line
 * gives a number with no meaning, so the page asks for a filter or a split instead.
 */
export const PARTITION_KEYS = ['state', 'direction'];

/** A partition label of this metric that is neither filtered nor split, so its series would be mixed into one line. */
export function mixedPartition(labelKeys: string[] | undefined, row: SeriesRow): string | null {
  if (!labelKeys || row.agg === 'rate') return null;
  const filtered = new Set(parseFilters(row.filter).map((f) => f.k));
  return labelKeys.find((k) => PARTITION_KEYS.includes(k) && k !== row.splitBy && !filtered.has(k)) ?? null;
}

/** Unit `1` means dimensionless, not necessarily a ratio. Only these bounded utilization metrics are 0–1 ratios. */
const RATIO_METRICS = new Set([
  'system.cpu.utilization',
  'system.memory.utilization',
  'system.paging.utilization',
  'system.filesystem.utilization',
]);

/**
 * Chart numbers: known 0–1 ratio metrics as percentages; otherwise with enough precision that neighbouring axis
 * ticks never print the same text (0.05 and 0.1 used to both read "0.1").
 */
export function metricFormat(unit: string, metric = ''): (v: number) => string {
  const ratio = unit === '1' && RATIO_METRICS.has(metric);
  const pct = unit === '%' || ratio;
  return (raw: number) => {
    const v = ratio ? raw * 100 : raw;
    const a = Math.abs(v);
    const text = a >= 100 ? Math.round(v).toLocaleString('en-US') : a >= 10 ? v.toFixed(1) : a >= 1 ? v.toFixed(2) : a === 0 ? '0' : Number(v.toPrecision(3)).toString();
    return `${text.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')}${pct ? '%' : ''}`;
  };
}

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
    series: valid.map((r) => {
      const groupBy = [...(r.byHost ? ['instance_id'] : []), ...(r.splitBy ? [r.splitBy] : [])];
      return { metric: r.metric.trim(), agg: r.agg, ...(groupBy.length ? { groupBy } : {}), ...(parseFilters(r.filter).length ? { filters: parseFilters(r.filter) } : {}) };
    }),
    ...(band ? { band: 'ANOMALY' as const } : {}),
    ...(compare ? { compareOffsetSec: spanSec } : {}),
  };
}

const label = (s: XpMetricSeries): string => Object.keys(s.labels).length ? `${s.metric.replace('system.', '')} ${Object.entries(s.labels).map(([key, value]) => `${key}=${value}`).join(' ')}` : s.metric;

const MetricsTab: React.FC = () => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  const [rows, setRows] = useState<SeriesRow[]>([DEFAULT_ROW]);
  const [band, setBand] = useState(false);
  const [saved, setSaved] = useState(false);
  const catalog = useMetricCatalog(ctx.effectiveAccountIds[0], '');
  const req = useMemo(() => buildMetricRequest(ctx.scope, rows, ctx.compare, band), [ctx.scope, rows, ctx.compare, band]);
  const q = useMetricQuery(req);
  const entry = (metric: string) => catalog.data?.find((item) => item.name === metric.trim());

  const chartGroups = useMemo(() => {
    const groups = new Map<string, { key: string; unit: string; ratio: boolean; series: ChartSeries[]; metrics: XpMetricSeries[] }>();
    (q.data?.series ?? []).slice(0, 24).forEach((series, index) => {
      const seriesUnit = catalog.data?.find((item) => item.name === series.metric)?.unit ?? '';
      const ratio = seriesUnit === '1' && RATIO_METRICS.has(series.metric);
      const groupKey = `${seriesUnit}:${ratio ? 'ratio' : 'native'}`;
      const group = groups.get(groupKey) ?? { key: groupKey, unit: seriesUnit, ratio, series: [], metrics: [] };
      const color = SERIES_COLORS[index % SERIES_COLORS.length];
      group.series.push({ key: `s${index}`, name: label(series), points: series.points, band: series.band, color });
      if (series.compare) group.series.push({ key: `c${index}`, name: `${label(series)} (previous)`, points: series.compare, dashed: true, color });
      group.metrics.push(series);
      groups.set(groupKey, group);
    });
    return Array.from(groups.values());
  }, [q.data, catalog.data]);

  const update = (id: number, patch: Partial<SeriesRow>) => setRows((rs) => rs.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  const updateMetric = (id: number, metric: string) => {
    const match = catalog.data?.find((item) => item.name === metric.trim());
    update(id, { metric, ...(match?.kind === 'COUNTER' ? { agg: 'rate' as const } : {}) });
  };
  const tooBroad = q.error instanceof XpApiError && q.error.code === 'XP_QUERY_TOO_BROAD';

  return (
    <>
      <PageHead icon={ChartLine} title="Metrics Explorer" sub="Plot any agent metric across hosts. Drag across a chart to zoom the whole page to that window." actions={<>
        <button type="button" className="xp-btn" aria-pressed={ctx.compare} onClick={() => ctx.update({ compare: !ctx.compare })}>Compare to previous period</button>
        <button type="button" className="xp-btn" aria-pressed={band} onClick={() => setBand(!band)}>Normal range band</button>
      </>} />
      <Card title="Chart" id="xp-mchart">
        {!req && <EmptyState title="Choose a metric" body="Pick one from the builder below. Try system.cpu.utilization or system.memory.utilization." />}
        {req && q.isLoading && <SkeletonBlock height={280} label="Querying metrics" />}
        {q.error && (tooBroad ? <div className="xp-banner xp-banner--warn">That time range is too wide. Pick 30 days or less.</div> : <ErrorState error={q.error} onRetry={() => { void q.refetch(); }} />)}
        {q.data && (chartGroups.length === 0 ? <EmptyState title="No data in this range" body="Try a longer range or another metric." /> : (
          <>
            {q.data.series.length > 24 && <div className="xp-banner xp-banner--warn" role="status">The query returned {q.data.series.length} series. The charts show the first 24; add a filter or remove a split to inspect all results.</div>}
            <div className="xp-metric-groups">
              {chartGroups.map((group, groupIndex) => {
                const groupFormat = metricFormat(group.unit, group.ratio ? group.metrics[0]?.metric : '');
                const unitLabel = group.ratio ? 'percent (reported as a 0–1 ratio)' : group.unit === '%' ? 'percent' : group.unit === '1' ? 'dimensionless' : group.unit || 'unit not reported';
                return <section key={group.key} className="xp-metric-group" aria-labelledby={`xp-metric-group-${groupIndex}`}>
                  <h3 id={`xp-metric-group-${groupIndex}`} className="xp-section-title">{unitLabel}</h3>
                  <TimeSeriesChart showLegend series={group.series} height={300} ariaLabel={`Metric chart, ${unitLabel}`} format={groupFormat}
                    onBrush={(a, b) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() })} fromMs={ctx.fromMs} toMs={ctx.toMs} />
                  <div className="xp-metric-summary" aria-label={`Returned series summary, ${unitLabel}`}>{group.metrics.map((series) => {
                    const values = series.points.flatMap((point) => point[1] == null ? [] : [point[1]]);
                    return <div key={`${series.metric}-${JSON.stringify(series.labels)}`}><strong>{label(series)}</strong><span>{values.length ? `latest ${groupFormat(values[values.length - 1])} · min ${groupFormat(Math.min(...values))} · max ${groupFormat(Math.max(...values))}` : 'no returned values'}</span></div>;
                  })}</div>
                </section>;
              })}
            </div>
            <p className="xp-hint">Selected range · {q.data.stepSec} s resolution · answered in {q.data.tookMs} ms. Series with different units use separate charts. Catalog discovery uses the first selected account; typed metric names still query all selected accounts.</p>
          </>
        ))}
      </Card>
      <Card title="Series builder" id="xp-series">
        <datalist id="xp-metric-names">{(catalog.data ?? []).map((c) => <option key={c.name} value={c.name}>{c.kind.toLowerCase()} · {c.unit || 'unitless'}</option>)}</datalist>
        {rows.map((r, i) => (
          <div key={r.id} className="xp-row" style={{ marginBottom: 8 }}>
            <label className="xp-sr" htmlFor={`xp-m-${r.id}`}>Metric {i + 1}</label>
            <input id={`xp-m-${r.id}`} className="xp-input xp-input--mono" list="xp-metric-names" value={r.metric} onChange={(e) => updateMetric(r.id, e.target.value)} placeholder="Choose a metric" style={{ minWidth: 280 }} />
            {entry(r.metric) && <span className="xp-status-line">{entry(r.metric)!.kind.toLowerCase()} · {entry(r.metric)!.unit || 'unitless'}</span>}
            <label className="xp-sr" htmlFor={`xp-a-${r.id}`}>Aggregation {i + 1}</label>
            <select id={`xp-a-${r.id}`} className="xp-select" value={r.agg} onChange={(e) => update(r.id, { agg: e.target.value as XpAgg })}>{AGGS.map((a) => <option key={a} value={a}>{a}</option>)}</select>
            <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 12.5, color: 'var(--xp-text2)' }}><input type="checkbox" checked={r.byHost} onChange={(e) => update(r.id, { byHost: e.target.checked })} />One line per host</label>
            <label className="xp-sr" htmlFor={`xp-f-${r.id}`}>Filters {i + 1}</label>
            <input id={`xp-f-${r.id}`} className="xp-input xp-input--mono" value={r.filter} onChange={(e) => update(r.id, { filter: e.target.value })} placeholder="state=steal, mountpoint=/var" style={{ minWidth: 220 }} />
            <label className="xp-sr" htmlFor={`xp-s-${r.id}`}>Split by {i + 1}</label>
            <select id={`xp-s-${r.id}`} className="xp-select" value={r.splitBy ?? ''} onChange={(e) => update(r.id, { splitBy: e.target.value || undefined })} title="One line per value of a label">
              <option value="">No split</option>
              {(entry(r.metric)?.labelKeys ?? []).map((k) => <option key={k} value={k}>Split by {k}</option>)}
            </select>
            {rows.length > 1 && <button type="button" className="xp-btn xp-btn--ghost" onClick={() => setRows(rows.filter((x) => x.id !== r.id))} aria-label={`Remove series ${i + 1}`}><X size={14} strokeWidth={1.5} /></button>}
            {(() => {
              const k = mixedPartition(entry(r.metric)?.labelKeys, r);
              return k ? (
                <div className="xp-banner xp-banner--warn" role="status" style={{ flexBasis: '100%', marginTop: 4, fontSize: 12.5 }}>
                  {r.metric.trim()} has a separate series per {k}. Combined into one line they give a number that means nothing (for CPU it is always about 1/8).{' '}
                  <button type="button" className="xp-btn xp-btn--sm" onClick={() => update(r.id, { splitBy: k })}>Split by {k}</button>{' '}
                  or filter one value, e.g. <code className="xp-mono">{k}={k === 'state' ? 'user' : 'receive'}</code>.
                </div>
              ) : null;
            })()}
          </div>
        ))}
        <div className="xp-row" style={{ marginTop: 14 }}>
          <button type="button" className="xp-btn" disabled={rows.length >= 6} onClick={() => setRows([...rows, { ...DEFAULT_ROW, id: Math.max(...rows.map((x) => x.id)) + 1, metric: '' }])}><Plus size={14} strokeWidth={1.5} aria-hidden="true" />Add series</button>
          <button type="button" className="xp-btn" disabled={!req || saved || ctx.effectiveAccountIds.length === 0} onClick={() => { void xampulseApi.saveView(ctx.effectiveAccountIds[0], { name: `${rows[0].metric} ${rows[0].agg}`, kind: 'METRICS', shared: false, state: { rows } }).then(() => setSaved(true)).catch(() => setSaved(false)); }}>{saved ? 'View saved' : 'Save view'}</button>
          <button type="button" className="xp-btn" disabled={!req} onClick={() => navigate(ctx.link('/xampulse/alerts'), { state: { prefill: { metric: rows[0].metric, agg: rows[0].agg === 'p95' ? 'p95' : rows[0].agg === 'min' || rows[0].agg === 'max' || rows[0].agg === 'sum' ? rows[0].agg : 'avg' } } })}>Create alert from this selection</button>
        </div>
      </Card>
    </>
  );
};

export default MetricsTab;
