/**
 * Pieces the HPA list and the HPA detail share: the source words for a live number the XamOps agent
 * didn't report, and the HPA history cards (FEAT-004, kube-state-metrics through Prometheus in the
 * cluster, one GET per HPA):
 *  - "Replicas and CPU use, together": replicas running against the minimum and maximum, and CPU
 *    use against the target, on one time axis, with the zone above the target hatched while the HPA
 *    is held at its maximum;
 *  - "Time at its maximum, last 3 days": each minute at the maximum, from 1-minute samples, grey
 *    before the first sample ("not measured yet");
 *  - the detail's list of replica changes.
 * A backend without the history (an untyped 404) renders none of them; anything else shows its
 * state in words with the source named. Nothing is drawn where nothing was measured.
 */
import type { FC, ReactNode } from 'react';
import type { InsightReadState } from '../../../../../hooks/useInsightRead';
import type { HpaHistoryView } from '../../../../../types/automationInsights';
import { DASH } from '../../../../../api/hpaAdapter';
import { ChartCard, type ChartEmptyState } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import { TimeSeriesChart, type TimeSeriesBand } from '../../../../CloudK8s/shared/charts/TimeSeriesChart';
import { TimeStrip } from '../../../../CloudK8s/shared/charts/TimeStrip';
import { byTime, insightCardState, istTick, noDataBand, sourceService, stepWords, timesOf, windowText } from '../insightCharts';
import { DAY_MS, istShort } from '../automationDerivations';
import { Card, DIVIDE, FAINT, INK, MUTED } from '../automationUi';

/** Names the source of an HPA's live numbers when one is missing. */
export const FROM_AGENT = 'from the XamOps agent';

const HISTORY_ENDPOINT = 'GET /api/eks/automation/hpa/{cluster}/{namespace}/{name}/history?window=72h';

interface HpaHistoryRow {
  t: number;
  x: string;
  current: number | null;
  min: number | null;
  max: number | null;
  cpu: number | null;
  target: number | null;
}

export function hpaHistoryRows(view: HpaHistoryView): HpaHistoryRow[] {
  const get = (key: string) => byTime(view.series.find((s) => s.key === key)?.points);
  const current = get('currentReplicas');
  const min = get('minReplicas');
  const max = get('maxReplicas');
  const cpu = get('cpuUtilization');
  const target = get('cpuTarget');
  const span = view.start !== null && view.end !== null ? view.end - view.start : 3 * DAY_MS;
  return timesOf(view.series.map((s) => s.points)).map((t) => ({
    t,
    x: istTick(t, span),
    current: current.get(t) ?? null,
    min: min.get(t) ?? null,
    max: max.get(t) ?? null,
    cpu: cpu.get(t) ?? null,
    target: target.get(t) ?? null,
  }));
}

/** The history's state for its cards: the read first, then whether kube-state-metrics has samples. */
function historyState(read: InsightReadState<HpaHistoryView>): { render: boolean; state: ChartEmptyState; view: HpaHistoryView | null } {
  const card = insightCardState(read, 'hpa');
  if (!card.render) return { render: false, state: { kind: 'ok' }, view: null };
  const view = card.view;
  if (card.state.kind !== 'ok' || !view) return { render: true, state: card.state, view };
  if (view.firstSampleAt === null) {
    return { render: true, state: { kind: 'unavailable', reason: 'Not measured yet: kube-state-metrics has no sample for this HPA in the window.' }, view };
  }
  const step = (view.stepSeconds ?? 0) * 1000;
  if (view.start !== null && view.end !== null && view.firstSampleAt > view.start + step / 2) {
    const days = Math.round((view.end - view.firstSampleAt) / DAY_MS);
    const kept = days >= 1 ? `Prometheus keeps ${days} ${days === 1 ? 'day' : 'days'} here.` : 'Prometheus has less than a day here.';
    return { render: true, state: { kind: 'partial', note: `${kept} No data before ${istShort(view.firstSampleAt)}.` }, view };
  }
  return { render: true, state: { kind: 'ok' }, view };
}

const num = (v: number | null, unit = ''): string => (v === null ? DASH : `${Math.round(v * 10) / 10}${unit}`);

/**
 * The hatched zone above the target while the HPA ran at its maximum (glossary §10), one band per
 * 60 s run long enough to span a chart step; the run's start target is the band's floor.
 */
function atMaxBands(view: HpaHistoryView, rows: readonly HpaHistoryRow[]): TimeSeriesBand[] {
  const runs = view.atMax?.runs ?? [];
  const end = view.end ?? (rows.length > 0 ? rows[rows.length - 1].t : 0);
  const bands: TimeSeriesBand[] = [];
  for (const run of runs) {
    const inRun = rows.filter((r) => r.t >= run.from && r.t <= (run.to ?? end));
    if (inRun.length < 2) continue;
    const target = inRun.find((r) => r.target !== null)?.target ?? null;
    if (target === null) continue;
    bands.push({ kind: 'atMax', x1: inRun[0].x, x2: inRun[inRun.length - 1].x, y1: target });
  }
  return bands;
}

export interface HpaHistoryCardsProps {
  /** The HPA's name, for the words. */
  subject: string;
  read: InsightReadState<HpaHistoryView>;
  /** A control to pick which HPA's history shows (the list page with more than one HPA). */
  picker?: ReactNode;
}

/** The two history cards, side by side on wide screens. */
export const HpaHistoryCards: FC<HpaHistoryCardsProps> = ({ subject, read, picker }) => {
  const h = historyState(read);
  if (!h.render) return null;
  return (
    <div className="space-y-2" data-hpa-history={subject}>
      {picker}
      <div className="grid gap-4 xl:grid-cols-2">
        <ReplicasAndCpuCard subject={subject} state={h.state} view={h.view} />
        <TimeAtMaxCard subject={subject} state={h.state} view={h.view} />
      </div>
    </div>
  );
};

function ReplicasAndCpuCard({ subject, state, view }: { subject: string; state: ChartEmptyState; view: HpaHistoryView | null }) {
  const rows = view ? hpaHistoryRows(view) : [];
  const drawn = state.kind === 'ok' || state.kind === 'partial';
  const noData = view && state.kind === 'partial' ? noDataBand(rows, view.firstSampleAt, 'No data before') : [];
  const max = rows.reduce<number | null>((m, r) => (r.max !== null && (m === null || r.max > m) ? r.max : m), null);
  const cpuTop = Math.max(100, ...rows.map((r) => r.cpu ?? 0));
  const step = view ? stepWords(view.stepSeconds) : '30 min';
  return (
    <ChartCard
      title="Replicas and CPU use, together"
      subline={`Every ${step}, ${view ? windowText(view.window) : 'last 3 days'}. Top: replicas of ${subject} running against its minimum and maximum. Bottom: CPU use against the target. The hatched zone is CPU above the target while replicas were already at the maximum: the HPA wanted more pods but couldn't add them.`}
      source={`HPA history (FEAT-004, ${HISTORY_ENDPOINT}): ${view ? sourceService(view.source) : 'Prometheus in your cluster'}, kube-state-metrics kube_horizontalpodautoscaler_status_current_replicas, spec_min_replicas, spec_max_replicas, status_target_metric{metric_name="cpu",metric_target_type="utilization"} and spec_target_metric, step ${step}, ${view ? windowText(view.window) : '3 days'}.`}
      state={state}
      table={
        drawn && rows.length > 0 ? (
          <ChartDataTable
            caption={`${subject}: replicas and CPU use`}
            rowHeader="Time (IST)"
            columns={[
              { key: 'current', header: 'Replicas running' },
              { key: 'min', header: 'Minimum' },
              { key: 'max', header: 'Maximum' },
              { key: 'cpu', header: 'CPU used, % of request' },
              { key: 'target', header: 'Target' },
            ]}
            rows={rows.map((r) => ({
              key: String(r.t),
              label: r.x,
              values: { current: num(r.current), min: num(r.min), max: num(r.max), cpu: num(r.cpu, '%'), target: num(r.target, '%') },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend items={[{ label: 'Replicas running', kind: 'used' }, { label: 'Minimum and maximum', kind: 'limit' }]} className="mb-1" />
      <TimeSeriesChart
        data={rows.map((r) => ({ x: r.x, current: r.current, min: r.min, max: r.max }))}
        series={[
          { dataKey: 'current', kind: 'used', name: 'Replicas running' },
          { dataKey: 'min', kind: 'limit', name: 'Minimum', lineOnly: true },
          { dataKey: 'max', kind: 'limit', name: 'Maximum', lineOnly: true },
        ]}
        xKey="x"
        bands={noData}
        height={150}
        ariaLabel={`${subject}: replicas running against its minimum and maximum${max !== null ? ` (up to ${max})` : ''}`}
      />
      <SeriesLegend
        items={[
          { label: 'CPU used, % of request (average pod)', kind: 'used' },
          { label: 'Target', kind: 'limit' },
          { label: 'Zone above the target while at the maximum: the HPA wants more pods', kind: 'limit', hatch: 'atMax' },
        ]}
        className="mb-1 mt-3"
      />
      <TimeSeriesChart
        data={rows.map((r) => ({ x: r.x, cpu: r.cpu, target: r.target }))}
        series={[
          { dataKey: 'cpu', kind: 'used', name: 'CPU used, % of request' },
          { dataKey: 'target', kind: 'limit', name: 'Target', lineOnly: true },
        ]}
        xKey="x"
        bands={[...noData, ...(view ? atMaxBands(view, rows) : [])]}
        height={150}
        tickFormat={(v) => `${v}%`}
        ariaLabel={`${subject}: CPU use against the target, up to ${Math.round(cpuTop)}% of request`}
      />
    </ChartCard>
  );
}

/** "71 h 52 min", "1 min". */
export function minutesText(minutes: number | null): string {
  if (minutes === null) return DASH;
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes - h * 60);
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

function TimeAtMaxCard({ subject, state, view }: { subject: string; state: ChartEmptyState; view: HpaHistoryView | null }) {
  const a = view?.atMax ?? null;
  const start = view?.start ?? null;
  const end = view?.end ?? null;
  const span = start !== null && end !== null && end > start ? end - start : null;
  const frac = (ms: number) => (span === null || start === null ? 0 : (ms - start) / span);
  let cardState: ChartEmptyState = state;
  if ((state.kind === 'ok' || state.kind === 'partial') && (!a || a.share === null)) {
    cardState = { kind: 'unavailable', reason: 'Not measured yet: kube-state-metrics has no 1-minute sample for this HPA in the window.' };
  }
  const drawn = cardState.kind === 'ok' || cardState.kind === 'partial';
  const firstMeasured = view?.firstSampleAt ?? null;
  const notMeasured = firstMeasured !== null && start !== null ? Math.max(0, frac(firstMeasured)) : 0;
  const notMeasuredMinutes = firstMeasured !== null && start !== null ? Math.max(0, Math.round((firstMeasured - start) / 60_000)) : 0;
  const below = a && a.minutesMeasured !== null && a.minutesAtMax !== null ? a.minutesMeasured - a.minutesAtMax : null;
  const pct = a?.share !== null && a?.share !== undefined ? Math.round(a.share * 10000) / 100 : null;
  const shown = view ? windowText(view.window) : '3 days';
  return (
    <ChartCard
      title="Time at its maximum, last 3 days"
      subline={
        drawn && a
          ? `${pct}% of the last ${shown} · below it for ${minutesText(below)}. Each minute: the strip is filled where ${subject} ran its maximum number of replicas.${
            a.since !== null ? ` At its maximum since ${istShort(a.since)}.` : ''
          }`
          : `Each minute ${subject} ran its maximum number of replicas.`
      }
      source={`HPA history (FEAT-004, ${HISTORY_ENDPOINT}): kube_horizontalpodautoscaler_status_current_replicas >= bool kube_horizontalpodautoscaler_spec_max_replicas, step ${stepWords(a?.stepSeconds ?? 60)}, ${shown}.`}
      state={cardState}
      table={
        drawn && a && a.runs.length > 0 ? (
          <ChartDataTable
            caption={`${subject}: time at its maximum`}
            rowHeader="From (IST)"
            columns={[{ key: 'to', header: 'To' }, { key: 'length', header: 'Length' }]}
            rows={a.runs.map((r) => ({
              key: String(r.from),
              label: istShort(r.from),
              values: {
                to: r.to !== null ? istShort(r.to) : 'still at its maximum',
                length: minutesText(Math.round(((r.to ?? end ?? r.from) - r.from) / 60_000)),
              },
            }))}
          />
        ) : undefined
      }
    >
      <ul className={`mb-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] ${MUTED}`}>
        <li>At its maximum: {minutesText(a?.minutesAtMax ?? null)}</li>
        <li>Below it: {minutesText(below)}</li>
        {notMeasuredMinutes > 0 && <li>Not measured yet: the first {minutesText(notMeasuredMinutes)} of the window</li>}
        {a?.runsTruncated && <li>Only the newest 1,000 spells are listed</li>}
      </ul>
      <TimeStrip
        segments={(a?.runs ?? []).map((r) => ({ from: frac(r.from), to: frac(r.to ?? end ?? r.from) }))}
        kind="limit"
        notMeasuredBefore={notMeasured}
        width={560}
        height={16}
        className="max-w-full"
        ariaLabel={`${subject} was at its maximum ${pct ?? DASH}% of the last ${shown}`}
      />
      {start !== null && end !== null && (
        <div className={`mt-1 flex w-full max-w-[560px] justify-between text-[11px] ${FAINT}`} aria-hidden="true">
          <span>{istShort(start)}</span>
          <span>{istShort(end)}</span>
        </div>
      )}
    </ChartCard>
  );
}

/** The detail's list of replica changes, newest first, from the same 60 s history. */
export const HpaChangesCard: FC<{ subject: string; read: InsightReadState<HpaHistoryView> }> = ({ subject, read }) => {
  const h = historyState(read);
  if (!h.render) return null;
  const changes = [...(h.view?.changes ?? [])].reverse();
  return (
    <Card title="Replica changes, last 3 days">
      <div data-hpa-changes="">
        {h.state.kind === 'loading' ? (
          <div className="skeleton h-8 rounded dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" aria-busy="true" />
        ) : h.state.kind === 'unavailable' || h.state.kind === 'error' ? (
          <p className={`text-[12px] ${MUTED}`}>{h.state.kind === 'unavailable' ? h.state.reason : h.state.message}</p>
        ) : changes.length === 0 ? (
          <p className={`text-[12px] ${MUTED}`}>
            {heldAtMaxThroughout(h.view)
              ? `${subject} didn't change its replica count in the window: it was held at its maximum the whole time.`
              : `${subject} didn't change its replica count in the window: it never scaled.`}
          </p>
        ) : (
          <ul className={`divide-y ${DIVIDE}`}>
            {changes.map((c) => (
              <li key={`${c.at}-${c.from}-${c.to}`} className={`flex items-center gap-3 py-1.5 text-[12px] ${INK}`}>
                <span className="w-[150px] shrink-0 tabular-nums">{istShort(c.at)}</span>
                <span className="tabular-nums">{`${c.from} → ${c.to} replicas`}</span>
              </li>
            ))}
          </ul>
        )}
        {h.view?.changesTruncated && <p className={`mt-1 text-[11px] ${FAINT}`}>The newest 200 changes; older ones are left out.</p>}
      </div>
      <p className={`mt-2 text-[11px] ${FAINT}`}>
        Source: HPA history (FEAT-004, {HISTORY_ENDPOINT}): kube_horizontalpodautoscaler_status_current_replicas at 1-minute steps, each
        time the running count moved.
      </p>
    </Card>
  );
};

/** Whether the HPA ran at its maximum every minute its history measured. */
function heldAtMaxThroughout(view: HpaHistoryView | null | undefined): boolean {
  const a = view?.atMax ?? null;
  return !!a && a.minutesMeasured !== null && a.minutesMeasured > 0 && a.minutesAtMax === a.minutesMeasured;
}

/** The table's "Last scaled" words for one HPA, from its history. */
export function lastScaled(read: InsightReadState<HpaHistoryView> | null): { text: string; sub: string | null; never: boolean; title?: string } {
  if (!read) return { text: DASH, sub: 'history read for the first HPAs only', never: false };
  const h = historyState(read);
  if (!h.render) return { text: DASH, sub: null, never: false };
  if (h.state.kind === 'loading') return { text: 'Reading…', sub: null, never: false };
  if (h.state.kind === 'unavailable' || h.state.kind === 'error') {
    return { text: DASH, sub: 'from kube-state-metrics', never: false, title: h.state.kind === 'unavailable' ? h.state.reason : h.state.message };
  }
  const changes = h.view?.changes ?? [];
  if (changes.length === 0) {
    // Held at its maximum every minute measured: it didn't move because it couldn't add pods, which
    // is not "never scales".
    return heldAtMaxThroughout(h.view)
      ? { text: 'Not in 3 days', sub: 'held at its maximum the whole window', never: false }
      : { text: 'Not in 3 days', sub: 'never scaled in the window', never: true };
  }
  const last = changes[changes.length - 1];
  const n = h.view?.changesTruncated ? '200+' : String(changes.length);
  return { text: istShort(last.at), sub: `${n} ${changes.length === 1 && !h.view?.changesTruncated ? 'change' : 'changes'} in 3 days`, never: false };
}

/** The history read for one HPA out of the page's histories, as a single read. */
export function historyOf(
  histories: { results: Record<string, InsightReadState<HpaHistoryView>['result']>; read: ReadonlySet<string>; reload: () => void },
  id: string,
): InsightReadState<HpaHistoryView> | null {
  if (!histories.read.has(id)) return null;
  const result = histories.results[id] ?? null;
  return { loading: result === null, result, reload: histories.reload };
}
