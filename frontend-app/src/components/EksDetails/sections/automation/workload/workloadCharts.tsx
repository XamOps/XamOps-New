/**
 * A workload's two time-series cards on its detail (UI v3 plan §2 "Workload detail"; design-delta
 * §3.1), both from the usage feed (FEAT-004, scope=workload: the same pods and containers XamOps's
 * suggestion reads, picked by the engine's own selector):
 *  - "CPU and memory over the window": the busiest pod's use against its request and limit over the
 *    look-back window the suggestion uses, with XamOps's suggestion now as a constant line (endpoint
 *    1), CPU or memory, one read at a time;
 *  - "Impact: CPU around the change": shown only when a change was applied live. From 3 hours before
 *    it to 3 hours after (or now), at 1-minute steps, with a marker at the change and, when the size
 *    before is recorded, the request it freed hatched between before and after.
 * Every point nothing measured is a gap; each card names its source and its state in words.
 */
import { useMemo, useState } from 'react';
import useAutomationUsage from '../../../../../hooks/useAutomationUsage';
import { windowWords, workloadWindowHours } from '../../../../../api/verticalRightsizerAdapter';
import type { UsageResource, UsageView, UsageWindow } from '../../../../../types/automationInsights';
import type { ActivityEntryView } from '../../../../../types/automationActivity';
import type { ApplyResult, WorkloadView } from '../../../../../types/verticalRightsizer';
import { ChartCard, type ChartEmptyState } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend, type LegendItem } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import { TimeSeriesChart, type TimeSeriesBand, type TimeSeriesReference } from '../../../../CloudK8s/shared/charts/TimeSeriesChart';
import { ResourceToggle } from '../insightCards';
import {
  AXIS_NAME,
  axisText,
  byTime,
  coverageInfo,
  insightCardState,
  istTick,
  millisText,
  nearest,
  noDataBand,
  sourceService,
  stepWords,
  timesOf,
  toAxis,
  windowText,
  type AxisUnit,
  type InsightCardState,
} from '../insightCharts';
import { MINUTE_MS, instantMs, isLiveApplied, istShort, liveOutcome, podRequests } from '../automationDerivations';
import { FAINT } from '../automationUi';

/** The fixed usage window that covers the policy's look-back window (at most 7 days). */
export function usageWindowFor(hours: number | null): UsageWindow {
  if (hours === null || hours <= 24) return '24h';
  if (hours <= 72) return '72h';
  return '168h';
}

interface UsageRow {
  t: number;
  x: string;
  /** Prometheus units (cores or bytes). */
  used: number | null;
  request: number | null;
  limit: number | null;
}

function usageRows(view: UsageView): UsageRow[] {
  const get = (kind: string) => byTime(view.series.find((s) => s.kind === kind || s.key === kind)?.points);
  const used = get('used');
  const request = get('request');
  const limit = get('limit');
  const span = view.start !== null && view.end !== null ? view.end - view.start : 0;
  return timesOf(view.series.map((s) => s.points)).map((t) => ({
    t,
    x: istTick(t, span),
    used: used.get(t) ?? null,
    request: request.get(t) ?? null,
    limit: limit.get(t) ?? null,
  }));
}

/** The card's state from the read, then the view's coverage, then whether anything was measured. */
function chartState(card: InsightCardState<UsageView>, rows: readonly UsageRow[]): { state: ChartEmptyState; bands: TimeSeriesBand[] } {
  if (!card.render || card.state.kind !== 'ok' || !card.view) return { state: card.render ? card.state : { kind: 'ok' }, bands: [] };
  const coverage = coverageInfo(card.view, istShort);
  if (coverage.state.kind !== 'unavailable' && !rows.some((r) => r.used !== null || r.request !== null)) {
    return { state: { kind: 'unavailable', reason: 'Prometheus in your cluster has no sample of these pods for this window.' }, bands: [] };
  }
  const bands = coverage.noDataBefore !== null ? noDataBand(rows, coverage.noDataBefore, `No data before ${istShort(coverage.noDataBefore)}`) : [];
  return { state: coverage.state, bands };
}

const LEGEND: LegendItem[] = [
  { label: 'Used (busiest pod)', kind: 'used' },
  { label: 'Request now', kind: 'requested' },
  { label: 'Limit', kind: 'limit' },
];

export interface WorkloadChartProps {
  clusterName: string;
  accountId: string;
  region: string;
  workload: WorkloadView;
}

export function WorkloadUsageCard({ clusterName, accountId, region, workload }: WorkloadChartProps) {
  const [resource, setResource] = useState<UsageResource>('cpu');
  const hours = workloadWindowHours(workload);
  const window = usageWindowFor(hours);
  const ref = workload.ref;
  const read = useAutomationUsage(
    { clusterName, accountId, region },
    { scope: 'workload', resource, window, workload: { namespace: ref.namespace, kind: ref.kind, name: ref.name } },
  );
  const card = insightCardState(read);
  if (!card.render) return null;
  const cpu = resource === 'cpu';
  const unit: AxisUnit = cpu ? 'millis' : 'mib';
  const rows = card.view ? usageRows(card.view) : [];
  const { state, bands } = chartState(card, rows);
  const drawn = state.kind === 'ok' || state.kind === 'partial';
  const pair = podRequests(workload, resource);
  const suggestion = pair.suggested !== null && pair.suggested !== pair.now ? pair.suggested : null;
  // podRequests is in m and Mi, the axis units of a workload chart.
  const references: TimeSeriesReference[] = suggestion !== null ? [{ y: suggestion, kind: 'suggested', label: 'Suggested now' }] : [];
  const suggestionText = suggestion === null ? null : cpu ? `${suggestion}m` : axisText(suggestion * 1024 * 1024, 'mib');
  const view = card.view;
  const lookBack = hours !== null ? windowWords(hours) : 'the look-back window';
  const shown = view ? windowText(view.window) : windowText(window);
  return (
    <ChartCard
      title="CPU and memory over the window"
      subline={`The busiest pod's ${cpu ? 'CPU use' : 'memory working set'} against its request and limit, per pod, over the ${shown}${
        hours !== null && windowText(window) !== windowWords(hours) ? ` around the ${lookBack} the suggestion reads` : ' the suggestion reads'
      }. ${suggestionText ? `XamOps suggests ${suggestionText} per pod now (the dashed line).` : 'XamOps suggests no change now.'}`}
      source={`Usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}?scope=workload&resource=${resource}&window=${window}): ${
        view ? sourceService(view.source) : 'Prometheus in your cluster'
      }, the same pods and containers the suggestion reads; used ${cpu ? 'max(sum by (pod) (rate(container_cpu_usage_seconds_total[5m])))' : 'max(sum by (pod) (container_memory_working_set_bytes))'}, request and limit from kube-state-metrics, step ${
        view ? stepWords(view.stepSeconds) : stepWords(window === '24h' ? 600 : window === '72h' ? 1800 : 3600)
      }, ${shown}. Suggested: Workload autoscaler (endpoint 1).`}
      state={state}
      actions={<ResourceToggle value={resource} onChange={setResource} />}
      table={
        drawn && rows.length > 0 ? (
          <ChartDataTable
            caption={`${workload.ref.name}: ${cpu ? 'CPU' : 'memory'} over the window`}
            rowHeader="Time (IST)"
            columns={[{ key: 'used', header: 'Used (busiest pod)' }, { key: 'request', header: 'Request now' }, { key: 'limit', header: 'Limit' }]}
            rows={rows.map((r) => ({
              key: String(r.t),
              label: r.x,
              values: { used: axisText(r.used, unit), request: axisText(r.request, unit), limit: axisText(r.limit, unit) },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend items={suggestion !== null ? [...LEGEND, { label: 'Suggested now', kind: 'suggested' }] : LEGEND} className="mb-2" />
      <TimeSeriesChart
        data={rows.map((r) => ({ x: r.x, used: toAxis(r.used, unit), request: toAxis(r.request, unit), limit: toAxis(r.limit, unit) }))}
        series={[
          { dataKey: 'used', kind: 'used', name: 'Used (busiest pod)' },
          { dataKey: 'request', kind: 'requested', name: 'Request now', lineOnly: true },
          { dataKey: 'limit', kind: 'limit', name: 'Limit', lineOnly: true },
        ]}
        xKey="x"
        bands={bands}
        references={references}
        height={220}
        ariaLabel={`${workload.ref.name}: ${cpu ? 'CPU' : 'memory'} use, request and limit, in ${AXIS_NAME[unit]}`}
      />
      <p className={`mt-1 text-[11px] ${FAINT}`}>{AXIS_NAME[unit]}, per pod</p>
    </ChartCard>
  );
}

// ─── Impact around the change ────────────────────────────────────────────────

/** A change applied live, with one pod's CPU request before and after it (m), when recorded. */
export interface ImpactChange {
  at: number;
  beforeM: number | null;
  afterM: number | null;
  /** Where the change was read from. */
  from: 'reply' | 'feed' | 'endpoint-1';
}

const sumOrNull = (values: Array<number | null | undefined>): number | null =>
  values.length === 0 || values.some((v) => v === null || v === undefined) ? null : (values as number[]).reduce((a, b) => a + b, 0);

/**
 * The latest change applied live to this workload: this session's apply reply when it is the newest,
 * else the activity log's newest live APPLIED resize, else endpoint 1's lastAction (time only).
 */
export function latestLiveChange(
  workload: WorkloadView,
  entries: readonly ActivityEntryView[] | null,
  reply: ApplyResult | undefined,
  replyAt: number | undefined,
): ImpactChange | null {
  const candidates: ImpactChange[] = [];
  const live = liveOutcome(reply);
  if (live && live.terminal === 'APPLIED' && replyAt !== undefined) {
    const first = live.pods?.[0];
    candidates.push({
      at: replyAt,
      beforeM: sumOrNull((first?.containers ?? []).map((c) => c.observedBefore?.cpuRequestM)),
      afterM: sumOrNull((first?.containers ?? []).map((c) => c.observedAfter?.cpuRequestM)),
      from: 'reply',
    });
  }
  if (entries) {
    const { namespace, kind, name } = workload.ref;
    const e = entries.find((x) =>
      x.action === 'resize' && !x.dryRun && x.result === 'applied' && x.at !== null
      && x.target.namespace === namespace && x.target.kind === kind && x.target.name === name);
    if (e && e.at !== null) {
      candidates.push({
        at: e.at,
        beforeM: e.beforeNotRecorded ? null : sumOrNull(e.containers.map((c) => c.before?.cpuRequestM)),
        afterM: sumOrNull(e.containers.map((c) => c.after?.cpuRequestM)),
        from: 'feed',
      });
    }
  } else if (isLiveApplied(workload.lastAction)) {
    const at = instantMs(workload.lastAction?.at);
    if (at !== null) candidates.push({ at, beforeM: null, afterM: null, from: 'endpoint-1' });
  }
  return candidates.sort((a, b) => b.at - a.at)[0] ?? null;
}

const HOUR_MS = 60 * MINUTE_MS;

export function ImpactCard({ clusterName, accountId, region, workload, change }: WorkloadChartProps & { change: ImpactChange }) {
  // From 3 hours before the change to 3 hours after it, or now: fixed per change, to the minute, so
  // the read isn't repeated on every render. At 60 s that is at most 361 points.
  const span = useMemo(() => {
    const from = Math.floor((change.at - 3 * HOUR_MS) / MINUTE_MS) * MINUTE_MS;
    const to = Math.min(Math.floor(Date.now() / MINUTE_MS) * MINUTE_MS, Math.ceil((change.at + 3 * HOUR_MS) / MINUTE_MS) * MINUTE_MS);
    return { from, to: Math.max(to, from + 2 * MINUTE_MS) };
  }, [change.at]);
  const ref = workload.ref;
  const read = useAutomationUsage(
    { clusterName, accountId, region },
    { scope: 'workload', resource: 'cpu', window: 'custom', from: span.from, to: span.to, step: 60, workload: { namespace: ref.namespace, kind: ref.kind, name: ref.name } },
  );
  const card = insightCardState(read);
  if (!card.render) return null;
  const rows = card.view ? usageRows(card.view) : [];
  const { state, bands } = chartState(card, rows);
  const drawn = state.kind === 'ok' || state.kind === 'partial';
  const at = nearest(rows, change.at);
  const last = rows[rows.length - 1];
  const freed = change.beforeM !== null && change.afterM !== null && change.beforeM > change.afterM;
  const allBands: TimeSeriesBand[] = [...bands];
  if (freed && at && last) allBands.push({ kind: 'freed', x1: at.x, x2: last.x, y1: change.afterM!, y2: change.beforeM!, label: 'Freed' });
  const sizes = change.beforeM !== null && change.afterM !== null ? ` (${change.beforeM}m → ${change.afterM}m per pod)` : '';
  const view = card.view;
  return (
    <ChartCard
      title="Impact: CPU around the change"
      subline={`CPU of this workload's pods from 3 hours before the change applied at ${istShort(change.at)}${sizes}, at 1-minute steps. ${
        freed
          ? 'The hatched band is the request it freed in each pod.'
          : change.beforeM === null
            ? "The size before isn't recorded for this change, so the freed request isn't drawn."
            : 'This change raised the request, so it freed nothing.'
      }`}
      source={`Usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}?scope=workload&resource=cpu&window=custom&step=60): ${
        view ? sourceService(view.source) : 'Prometheus in your cluster'
      }, used and the request and limit in the pod spec (kube-state-metrics), step 1 min, ${windowText('custom', span.from, span.to)}. The change: ${
        change.from === 'reply' ? "this session's apply reply" : change.from === 'feed' ? 'the activity log (FEAT-007)' : 'Workload autoscaler (endpoint 1), lastAction'
      }.`}
      state={state}
      table={
        drawn && rows.length > 0 ? (
          <ChartDataTable
            caption={`${workload.ref.name}: CPU around the change`}
            rowHeader="Time (IST)"
            columns={[{ key: 'used', header: 'Used (busiest pod)' }, { key: 'request', header: 'Request' }, { key: 'limit', header: 'Limit' }]}
            rows={rows.map((r) => ({
              key: String(r.t),
              label: r.x,
              values: { used: millisText(r.used), request: millisText(r.request), limit: millisText(r.limit) },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend
        items={[...LEGEND.map((l) => (l.kind === 'requested' ? { ...l, label: 'Request' } : l)), ...(freed ? [{ label: 'Freed', kind: 'saved' as const, money: true }] : [])]}
        className="mb-2"
      />
      <TimeSeriesChart
        data={rows.map((r) => ({ x: r.x, used: toAxis(r.used, 'millis'), request: toAxis(r.request, 'millis'), limit: toAxis(r.limit, 'millis') }))}
        series={[
          { dataKey: 'used', kind: 'used', name: 'Used (busiest pod)' },
          { dataKey: 'request', kind: 'requested', name: 'Request', lineOnly: true },
          { dataKey: 'limit', kind: 'limit', name: 'Limit', lineOnly: true },
        ]}
        xKey="x"
        bands={allBands}
        markers={at ? [{ x: at.x, label: 'Change' }] : []}
        height={220}
        ariaLabel={`${workload.ref.name}: CPU use, request and limit around the change at ${istShort(change.at)}, in millicores`}
      />
      <p className={`mt-1 text-[11px] ${FAINT}`}>millicores, per pod</p>
    </ChartCard>
  );
}
