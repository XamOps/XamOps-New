/**
 * Pure derivations for Automation › Spot interruptions (spot-interruptions design §B6.2): the
 * now-sentence, the metric cells, the row view models, the recovery text and the status mapping.
 * Nothing here fetches, reads a clock or renders.
 *
 * Two rules every function keeps:
 *  - unknown is not empty: a value the backend served as `null` is a dash with a reason, never a 0 or
 *    "None"; a 0 is shown only for what was looked for and found absent;
 *  - only a row with an interruption notice is called an interruption: every count is split by
 *    `noticeKind` through {@link kindParts}, so a removal with no recorded cause or a rebalance
 *    recommendation is never counted as an interruption.
 */
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import type { ReadState } from '../../../../../hooks/useNodeAutomationRead';
import type {
  HandlingMode,
  SpotInterruptionEvent,
  SpotInterruptionHandling,
  SpotInterruptionsDto,
} from '../../../../../types/k8sAutomation';
import type { StatusState } from '../../../../CloudK8s/shared/StatusMark';
import { formatAgo } from '../../../../CloudK8s/shared/SourceLabel';
import { shortNode } from './consolidationDerivations';

export { DASH };

/** The window choices, in days (the backend allows 1 to 365). */
export const WINDOW_DAYS = [7, 30, 90, 365] as const;
export const DEFAULT_WINDOW_DAYS = 30;

const pl = (n: number) => (n === 1 ? '' : 's');

// ─── Kind words ──────────────────────────────────────────────────────────────

// i = rows with noticeKind 'interruption' (including rebalance → interruption), r = 'rebalance', u = 'unconfirmed', all in the window.
export function kindParts(events: SpotInterruptionEvent[]): string[] {
  const i = events.filter((e) => e.noticeKind === 'interruption').length;
  const r = events.filter((e) => e.noticeKind === 'rebalance').length;
  const u = events.filter((e) => e.noticeKind === 'unconfirmed').length;
  const o = events.length - i - r - u;                                   // a kind a future backend adds
  return [
    i > 0 && `${i} interruption notice${pl(i)}`,
    r > 0 && `${r} rebalance recommendation${pl(r)}`,
    u > 0 && `${u} Spot node${pl(u)} removed · cause unconfirmed`,
    o > 0 && `${o} other Spot event${pl(o)}`,
  ].filter((p): p is string => typeof p === 'string');
}

export const RECORDING_OFF_SENTENCE = 'Recording is turned off on this XamOps deployment';

/**
 * The header sentence for a successful read. With recording off and no events nothing was looked
 * for, so it says that instead of claiming an empty history.
 */
export function nowSentence(
  events: SpotInterruptionEvent[],
  fleet: SpotInterruptionsDto['fleet'],
  days: number,
  recordingEnabled = true,
): string {
  const parts = kindParts(events);
  if (parts.length > 0) {
    const share = typeof fleet?.spotSharePct === 'number' ? ` · Spot is ${fleet.spotSharePct}% of nodes` : '';
    return `In the last ${days} days: ${parts.join(' · ')}${share}`;
  }
  if (!recordingEnabled) return RECORDING_OFF_SENTENCE;
  if (fleet?.spotNodes === 0) return 'No spot nodes in this cluster';
  return `No interruptions recorded in the last ${days} days`;
}

// ─── Page state ──────────────────────────────────────────────────────────────

export type SpotPageState = 'loading' | 'error' | 'not-monitored' | 'recording-off' | 'no-spot' | 'none' | 'events';

export function pageState(read: ReadState<SpotInterruptionsDto>): SpotPageState {
  if (read.kind === 'loading') return 'loading';
  if (read.kind === 'error') return 'error';
  const d = read.data;
  if (d.events.length > 0) return 'events';
  if (d.coverage.agentSnapshotAt === null && d.coverage.automationReportAt === null) return 'not-monitored';
  // Unknown, not empty: with the kill switch off nothing was recorded, so "none" would be a guess.
  if (!d.recordingEnabled) return 'recording-off';
  if (d.fleet?.spotNodes === 0) return 'no-spot';
  return 'none';
}

// ─── Status mapping ──────────────────────────────────────────────────────────

/** The Handling filter's values: the wire modes, with null folded into `unknown`. */
export type HandlingKey = HandlingMode;

export interface MarkView {
  state: StatusState;
  label: string;
  /** Why, when the word alone doesn't say it (Off, Not handled). */
  reason: string | null;
}

/** One mapping for the metric cell, the table and the drawer. */
export function handlingView(h: SpotInterruptionHandling | null): MarkView & { key: HandlingKey } {
  switch (h?.mode) {
    case 'live':
      return { key: 'live', state: 'healthy', label: 'Live', reason: h.reason };
    case 'simulate':
      return { key: 'simulate', state: 'simulated', label: 'Simulated (dry run)', reason: h.reason };
    case 'off':
      return { key: 'off', state: 'not-set-up', label: 'Off', reason: h.reason };
    case 'not-eligible':
      return { key: 'not-eligible', state: 'unknown', label: 'Not handled by XamOps', reason: h.reason };
    default:
      return { key: 'unknown', state: 'unknown', label: 'Not reported', reason: h?.reason ?? null };
  }
}

export const HANDLING_FILTER: ReadonlyArray<{ value: HandlingKey; label: string }> = [
  { value: 'live', label: 'Live' },
  { value: 'simulate', label: 'Simulated' },
  { value: 'off', label: 'Off' },
  { value: 'not-eligible', label: 'Not handled' },
  { value: 'unknown', label: 'Not reported' },
];

/** The Notice column's mark. "Interruption" is never used for a removal without a notice. */
export function noticeView(e: SpotInterruptionEvent): MarkView {
  switch (e.noticeKind) {
    case 'interruption':
      return e.rebalanceNoticedAt
        ? { state: 'attention', label: 'Rebalance → 2-min notice', reason: null }
        : { state: 'attention', label: '2-min notice', reason: null };
    case 'rebalance':
      return { state: 'attention', label: 'Rebalance recommendation', reason: null };
    case 'unconfirmed':
      return {
        state: 'unverified',
        label: 'Removed · cause unconfirmed',
        reason: 'The Spot node disappeared without an NTH taint or a Karpenter event, and no known scale-in explains it.',
      };
    default:
      return { state: 'unknown', label: 'Other Spot event', reason: null };
  }
}

export const NOTICE_FILTER: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'interruption', label: '2-min notice' },
  { value: 'rebalance', label: 'Rebalance recommendation' },
  { value: 'unconfirmed', label: 'Cause unconfirmed' },
];

/** The drain outcome's word, when XamOps drained (or simulated a drain of) the node. */
export function drainWord(drain: SpotInterruptionEvent['drain']): string | null {
  if (!drain) return null;
  switch (drain.status) {
    case 'succeeded':
      return drain.dryRun ? 'Simulated drain' : 'Drained';
    case 'simulated':
      return 'Simulated drain';
    case 'refused':
      return 'Drain refused';
    case 'failed':
      return 'Drain failed';
    case 'skipped':
      return 'Drain skipped';
    default:
      return `Drain ${drain.status}`;
  }
}

const SOURCE_WORDS: Record<string, string> = {
  'nth-taint': 'NTH taint',
  'nth-label-legacy': 'NTH label (legacy)',
  'karpenter-event': 'Karpenter event',
  'node-removed': 'Node removed',
};

export const sourceWords = (sources: readonly string[]): string | null =>
  sources.length === 0 ? null : sources.map((s) => SOURCE_WORDS[s] ?? s).join(', ');

// ─── Recovery ────────────────────────────────────────────────────────────────

export const NOT_MEASURABLE_REASON =
  "Pods not managed by a Deployment or StatefulSet aren't recreated, so recovery can't be measured";

/** "3–8 min", "≤ 5 min" or "Recovered by first sighting"; null unless recovered with bounds. Never "0 min". */
export function recoveryText(recovery: SpotInterruptionEvent['recovery']): string | null {
  if (recovery.status !== 'recovered') return null;
  if (recovery.recoveredByFirstSighting) return 'Recovered by first sighting';
  const { lowerSeconds: lower, upperSeconds: upper } = recovery;
  if (typeof upper !== 'number' || upper <= 0) return null;
  const hi = Math.max(1, Math.ceil(upper / 60));
  if (typeof lower !== 'number' || lower <= 0) return `≤ ${hi} min`;
  const lo = Math.floor(lower / 60);
  return lo <= 0 || lo >= hi ? `≤ ${hi} min` : `${lo}–${hi} min`;
}

export type RecoveryView =
  | { kind: 'text'; text: string }
  | { kind: 'mark'; mark: MarkView }
  | { kind: 'dash'; reason: string };

/** The Recovery column: a measured range, a status mark, or a dash with its reason. Never a duration it didn't measure. */
export function recoveryView(e: SpotInterruptionEvent): RecoveryView {
  const r = e.recovery;
  switch (r.status) {
    case 'recovered': {
      const text = recoveryText(r);
      return text ? { kind: 'text', text } : { kind: 'dash', reason: 'Recovered, but the time it took was not measured' };
    }
    case 'tracking':
      return { kind: 'mark', mark: { state: 'unknown', label: 'Tracking', reason: 'XamOps is waiting for the affected workloads to be Ready elsewhere' } };
    case 'not-observed':
      return { kind: 'dash', reason: 'Not observed in 6 h' };
    case 'node-not-removed':
      return { kind: 'text', text: 'Node not removed' };
    case 'no-workloads':
      return { kind: 'text', text: 'Nothing to move' };
    case 'no-snapshot':
      return { kind: 'dash', reason: 'No snapshot' };
    case 'not-measurable':
      return { kind: 'mark', mark: { state: 'unknown', label: 'Not measurable', reason: NOT_MEASURABLE_REASON } };
    default:
      return { kind: 'dash', reason: 'Not reported' };
  }
}

/** The status sentence the drawer shows for a row's recovery. */
export function recoverySentence(e: SpotInterruptionEvent): string {
  const v = recoveryView(e);
  if (v.kind === 'text') return e.recovery.status === 'recovered' ? `Recovered: workloads Ready elsewhere in ${v.text}` : v.text;
  if (v.kind === 'mark') return v.mark.reason ? `${v.mark.label}: ${v.mark.reason}` : v.mark.label;
  return v.reason;
}

/** Median of the recovered rows' upper bounds (seconds); first-sighting and every other status are excluded. */
export function recoveryMedianSeconds(events: SpotInterruptionEvent[]): number | null {
  const values = events
    .filter((e) => e.recovery.status === 'recovered' && !e.recovery.recoveredByFirstSighting)
    .map((e) => e.recovery.upperSeconds)
    .filter((v): v is number => typeof v === 'number' && v > 0)
    .sort((a, b) => a - b);
  if (values.length === 0) return null;
  const mid = Math.floor(values.length / 2);
  return values.length % 2 === 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
}

// ─── Metric cells ────────────────────────────────────────────────────────────

export interface SpotMetricCells {
  share: { value: string; sub: string };
  events: { value: string; sub: string };
  recovery: { value: string; sub: string };
  handling: { mark: MarkView; sub: string | null };
}

const agoWords = (iso: string | null | undefined, now: number): string | null => formatAgo(iso, now);

export function metricCells(dto: SpotInterruptionsDto, now: number): SpotMetricCells {
  const fleet = dto.fleet;
  const fleetAge = agoWords(fleet?.collectedAt, now);
  const share = !fleet
    ? { value: DASH, sub: 'No XamOps agent snapshot' }
    : typeof fleet.spotSharePct !== 'number'
      ? { value: DASH, sub: 'No nodes in the latest snapshot' }
      : { value: `${fleet.spotSharePct}%`, sub: `${fleet.spotNodes} of ${fleet.totalNodes} nodes${fleetAge ? ` · as of ${fleetAge}` : ''}` };

  const n = dto.events.length;
  const events = n > 0
    ? { value: String(n), sub: kindParts(dto.events).join(' · ') }
    : dto.recordingEnabled
      ? { value: '0', sub: 'None in the window' }
      : { value: DASH, sub: 'Recording is off' };

  const median = recoveryMedianSeconds(dto.events);
  const recovery = median === null
    ? { value: DASH, sub: 'No recovery measured yet' }
    : { value: `≤ ${Math.max(1, Math.ceil(median / 60))} min`, sub: 'Median, Ready elsewhere' };

  const mark = handlingView(dto.coverage.handlingNow);
  let handlingSub: string | null = mark.reason;
  if (dto.coverage.handlingNow === null) {
    const reportAge = agoWords(dto.coverage.automationReportAt, now);
    handlingSub = reportAge ? `Automation agent report ${reportAge.replace(/ ago$/, '')} old` : 'No automation agent report';
  }
  return { share, events, recovery, handling: { mark, sub: handlingSub } };
}

// ─── Rows ────────────────────────────────────────────────────────────────────

export interface SpotEventRow {
  key: string;
  event: SpotInterruptionEvent;
  noticedMs: number | null;
  node: string;
  notice: MarkView;
  handling: MarkView & { key: HandlingKey };
  drain: string | null;
  recovery: RecoveryView;
  /** "3", "3+" when truncated, null when the workloads weren't captured. */
  workloads: string | null;
}

export function eventRows(events: SpotInterruptionEvent[]): SpotEventRow[] {
  return events.map((e) => {
    const ms = Date.parse(e.noticedAt);
    return {
      key: String(e.id),
      event: e,
      noticedMs: Number.isNaN(ms) ? null : ms,
      node: shortNode(e.nodeName),
      notice: noticeView(e),
      handling: handlingView(e.handling),
      drain: drainWord(e.drain),
      recovery: recoveryView(e),
      workloads: e.workloads === null ? null : `${e.workloads.length}${e.workloadsTruncated ? '+' : ''}`,
    };
  });
}

/** The Notice filter: an unknown future kind matches no option (only "All"). */
export const matchNotice = (row: SpotEventRow, value: string): boolean => row.event.noticeKind === value;
export const matchHandling = (row: SpotEventRow, value: string): boolean => row.handling.key === value;

/** "team-a/Deployment/web · 2 pods on node". */
export const workloadLine = (w: NonNullable<SpotInterruptionEvent['workloads']>[number]): string =>
  `${w.namespace}/${w.kind}/${w.name} · ${w.podsOnNode} pod${pl(w.podsOnNode)} on node`;
