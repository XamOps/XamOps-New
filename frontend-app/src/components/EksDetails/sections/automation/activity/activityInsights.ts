/**
 * Pure derivations over the activity feed (FEAT-007) and its per-day summary (FEAT-005) for the
 * Overview's 7-day timeline and KPIs and Activity's changes-per-day chart. Nothing here invents a
 * count: a day the summary didn't list is "not recorded yet", an amount whose size before wasn't
 * recorded is left out and counted as such, and an entry with no time isn't placed.
 */
import type { FeatureAccent } from '../../../../CloudK8s/shared/charts/chartTokens';
import type { Lane } from '../../../../CloudK8s/shared/charts/LanesTimeline';
import { RESULT_TONES, type Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type { ActivityDayCounts, ActivityEntryView, ActivitySummaryView } from '../../../../../types/automationActivity';
import type { WorkloadView } from '../../../../../types/verticalRightsizer';
import { DAY_MS, MINUTE_MS, instantMs, isLiveApplied, istShort } from '../automationDerivations';
import { activityResultChip } from './activityText';

const refKey = (t: { namespace: string | null; kind: string | null; name: string | null }) => `${t.namespace ?? ''}/${t.kind ?? ''}/${t.name ?? ''}`;

/** The short word for a kind in a lane label. */
const KIND_WORD: Record<string, string> = { HorizontalPodAutoscaler: 'HPA' };

// ─── The 7-day timeline ──────────────────────────────────────────────────────

export interface FeedTimeline {
  lanes: Lane[];
  /** The entries placed, newest first (the data table's rows). */
  placed: ActivityEntryView[];
  /** Lanes left out to keep the chart readable; every entry is on Activity. */
  hiddenLanes: number;
  /** Each result shown, once, for the legend chips. */
  results: Array<{ tone: Tone; icon: string; label: string }>;
}

/**
 * One lane per workload or HPA with an entry in the window, a dot per entry at its time, toned by its
 * result chip; a test run is a hollow dot. Lanes are ordered by their newest entry.
 */
export function feedTimeline(entries: readonly ActivityEntryView[], now: number, days = 7, maxLanes = 15): FeedTimeline {
  const span = days * DAY_MS;
  const from = now - span;
  const inWindow = entries.filter((e) => e.at !== null && e.at >= from && e.at <= now + 5 * MINUTE_MS);
  const groups = new Map<string, ActivityEntryView[]>();
  for (const e of inWindow) {
    const k = refKey(e.target);
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }
  const ordered = [...groups.values()].sort((a, b) => Math.max(...b.map((e) => e.at!)) - Math.max(...a.map((e) => e.at!)));
  const shown = ordered.slice(0, maxLanes);
  const lanes: Lane[] = shown.map((g) => {
    const head = g[0];
    const name = head.target.name ?? '—';
    // A shared name is told apart by its namespace; an HPA usually shares its workload's name in the
    // same namespace, so there the workload keeps the name and the other kind says what it is.
    const twins = shown.filter((o) => o !== g && (o[0].target.name ?? '') === (head.target.name ?? ''));
    const qualified = twins.some((o) => o[0].target.namespace !== head.target.namespace) ? `${head.target.namespace ?? '—'}/${name}` : name;
    const kind = head.target.kind ?? '';
    const kindTwin = kind !== '' && kind !== 'Deployment' && twins.some((o) => o[0].target.namespace === head.target.namespace);
    return {
      name: kindTwin ? `${qualified} (${KIND_WORD[kind] ?? kind})` : qualified,
      feature: (head.feature ?? 'workload') as FeatureAccent,
      entries: g.map((e) => {
        const chip = activityResultChip(e);
        const test = e.dryRun || e.result === 'test-run';
        return { at: (e.at! - from) / span, tone: chip.tone, dryRun: test, label: `${istShort(e.at!)}: ${chip.label}${test ? '' : ', live'}` };
      }),
    };
  });
  const placed = shown.flat().sort((a, b) => b.at! - a.at!);
  const results = [...new Map(placed.map((e) => {
    const chip = activityResultChip(e);
    return [chip.label, { tone: chip.tone, icon: chip.icon, label: chip.label }] as const;
  })).values()];
  return { lanes, placed, hiddenLanes: Math.max(0, ordered.length - shown.length), results };
}

// ─── Saving now ──────────────────────────────────────────────────────────────

export interface SavingNowItem {
  workload: WorkloadView;
  entry: ActivityEntryView;
  /** CPU request (m) and memory request (Mi) freed across the pods the change resized. */
  cpuM: number;
  memMi: number;
  /** The pods that run the new size: a pod put back, or never touched, isn't one. */
  pods: number;
}

export interface SavingNow {
  /** Workloads whose latest change that reached their pods (endpoint 1) was applied live. */
  inPlace: number;
  /** Of them, the ones whose change the activity log holds with the size before and after. */
  counted: SavingNowItem[];
  /** In place, but the amount isn't known: the log doesn't hold the size before, or the entry is older. */
  notCounted: number;
  /** Counted changes that raised the request rather than freeing it. */
  raised: number;
  cpuM: number;
  memMi: number;
}

/** Σ over the entry's containers of one size field; null when any container lacks it. */
function sizeSum(entry: ActivityEntryView, side: 'before' | 'after', field: 'cpuRequestM' | 'memRequestMi'): number | null {
  let total = 0;
  for (const c of entry.containers) {
    const v = c[side]?.[field] ?? null;
    if (v === null) return null;
    total += v;
  }
  return entry.containers.length > 0 ? total : null;
}

/**
 * The pods of an entry's proof that run the new size; null when a pod doesn't say (a row written
 * before the proof recorded it), since a pod put back must not be counted as resized.
 */
function resizedPods(entry: ActivityEntryView): number | null {
  const pods = entry.proof?.pods ?? [];
  if (pods.some((p) => p.changed === null)) return null;
  return pods.filter((p) => p.changed === true).length;
}

/**
 * What the changes in place free now (UI v3 plan: "Saving now"). Which workloads are in place comes
 * from endpoint 1 (its lastAction is the latest change that reached the pods: test runs, put-backs and
 * refusals are skipped); how much each frees comes from the activity log's entry for that same change
 * (matched on the workload and the instant), as (before − after) × the pods that run the new size, so
 * a pod a partial apply put back isn't counted. An entry without the size before (rows written before
 * the activity log), without per-pod proof, or whose pods don't say whether they changed isn't
 * counted, and is said to be. A raise frees nothing and is counted apart.
 */
export function savingNow(workloads: readonly WorkloadView[], entries: readonly ActivityEntryView[]): SavingNow {
  const out: SavingNow = { inPlace: 0, counted: [], notCounted: 0, raised: 0, cpuM: 0, memMi: 0 };
  for (const w of workloads) {
    if (!isLiveApplied(w.lastAction)) continue;
    out.inPlace += 1;
    const at = instantMs(w.lastAction?.at);
    const entry = at === null ? undefined : entries.find((e) =>
      e.action === 'resize' && !e.dryRun && e.result === 'applied' && e.at !== null && Math.abs(e.at - at) <= 1000
      && e.target.namespace === w.ref.namespace && e.target.kind === w.ref.kind && e.target.name === w.ref.name);
    const pods = entry ? resizedPods(entry) : null;
    const cpuBefore = entry ? sizeSum(entry, 'before', 'cpuRequestM') : null;
    const cpuAfter = entry ? sizeSum(entry, 'after', 'cpuRequestM') : null;
    if (!entry || entry.beforeNotRecorded || pods === null || pods === 0 || cpuBefore === null || cpuAfter === null) {
      out.notCounted += 1;
      continue;
    }
    const memBefore = sizeSum(entry, 'before', 'memRequestMi');
    const memAfter = sizeSum(entry, 'after', 'memRequestMi');
    const cpuM = Math.max(0, cpuBefore - cpuAfter) * pods;
    const memMi = memBefore !== null && memAfter !== null ? Math.max(0, memBefore - memAfter) * pods : 0;
    if (cpuM === 0 && memMi === 0) {
      out.raised += 1;
      continue;
    }
    out.counted.push({ workload: w, entry, cpuM, memMi, pods });
    out.cpuM += cpuM;
    out.memMi += memMi;
  }
  return out;
}

// ─── Changes per day ─────────────────────────────────────────────────────────

export interface ResultBar {
  key: keyof ActivityDayCounts;
  label: string;
  tone: Tone;
  icon: string;
}

/** The stacked results, bottom first, with the chips that name their tones. */
export const RESULT_BARS: readonly ResultBar[] = [
  { key: 'applied', label: 'Applied', ...RESULT_TONES.applied },
  { key: 'putBack', label: 'Deferred or put back', tone: 'warn', icon: 'mdi:undo-variant' },
  { key: 'refused', label: 'Refused', ...RESULT_TONES.refused },
  { key: 'failed', label: 'Failed', ...RESULT_TONES.failed },
  { key: 'testRuns', label: 'Test runs', ...RESULT_TONES['test-run'] },
];

const DAY_LABEL = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' });

/** "Tue, Oct 14" for an IST calendar day `yyyy-MM-dd`. */
export const dayLabel = (date: string): string => DAY_LABEL.format(new Date(`${date}T00:00:00Z`));

/** The days from `from` to `to` inclusive (`yyyy-MM-dd`, calendar arithmetic, no time zone). */
export function daysBetween(from: string, to: string): string[] {
  const out: string[] = [];
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return out;
  for (let t = start; t <= end && out.length < 60; t += DAY_MS) out.push(new Date(t).toISOString().slice(0, 10));
  return out;
}

export interface DayRow {
  date: string;
  label: string;
  /** False for a day the summary didn't list: not recorded yet, never 0. */
  recorded: boolean;
  counts: ActivityDayCounts | null;
  total: number | null;
}

/** Every day of the window, oldest first: the summary's counts, or "not recorded" for a day it left out. */
export function dayRows(summary: ActivitySummaryView): DayRow[] {
  const listed = new Map(summary.days.map((d) => [d.date, d]));
  const window = summary.from && summary.to ? daysBetween(summary.from, summary.to) : summary.days.map((d) => d.date);
  return window.map((date) => {
    const d = listed.get(date);
    const counts = d ? { applied: d.applied, putBack: d.putBack, refused: d.refused, testRuns: d.testRuns, failed: d.failed } : null;
    return {
      date,
      label: dayLabel(date),
      recorded: !!d,
      counts,
      total: counts ? counts.applied + counts.putBack + counts.refused + counts.testRuns + counts.failed : null,
    };
  });
}

export interface WeekCounts {
  total: number;
  byResult: Array<ResultBar & { count: number }>;
  testRuns: number;
  /** The first listed day, when the summary lists fewer days than the window. */
  since: string | null;
}

/** The summary's listed days added up; null when it lists none (nothing can be counted honestly). */
export function weekCounts(summary: ActivitySummaryView): WeekCounts | null {
  if (summary.days.length === 0) return null;
  const sum = (key: keyof ActivityDayCounts) => summary.days.reduce((n, d) => n + d[key], 0);
  const byResult = RESULT_BARS.map((b) => ({ ...b, count: sum(b.key) })).filter((b) => b.count > 0);
  const rows = dayRows(summary);
  const since = rows.length > 0 && !rows[0].recorded ? summary.days[0].date : null;
  return { total: byResult.reduce((n, b) => n + b.count, 0), byResult, testRuns: sum('testRuns'), since };
}

/** The same counts over feed entries in the window (a backend without the summary). Mode changes have no result and aren't counted. */
export function feedWeekCounts(entries: readonly ActivityEntryView[], now: number, days = 7): WeekCounts {
  const from = now - days * DAY_MS;
  const recent = entries.filter((e) => e.at !== null && e.at >= from && e.result !== null);
  const of = (key: keyof ActivityDayCounts): number => {
    const result = key === 'putBack' ? 'put-back' : key === 'testRuns' ? 'test-run' : key;
    return recent.filter((e) => e.result === result).length;
  };
  const byResult = RESULT_BARS.map((b) => ({ ...b, count: of(b.key) })).filter((b) => b.count > 0);
  return { total: recent.length, byResult, testRuns: of('testRuns'), since: null };
}
