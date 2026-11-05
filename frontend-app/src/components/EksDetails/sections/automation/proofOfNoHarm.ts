/**
 * The "Proof of no harm" panel's numbers (Phase A design §4.3), pure.
 *
 * Everything here is counted from what the activity log recorded. A value the log doesn't have is
 * never 0: it stays out of the denominator and is reported as "not recorded". A change with no pod
 * proof (a row written before the log kept proofs) is never counted as restart-free, even though the
 * wire flag says so, which is why the headline reads the proof and not `entry.restartFree`.
 */
import type { ActivityEntryView, ActivityPodProof } from '../../../../types/automationActivity';
import type { ConsolidationRunResponse } from '../../../../types/k8sAutomation';
import { podRestarted, touchedPods } from './activity/activityText';

export interface ProofSummary {
  /** Entries that touched pods (live resizes). */
  changes: number;
  changesRestartFree: number;
  /** Touched changes with no recorded pod proof at all. */
  changesWithoutProof: number;
  pods: number;
  podsRestarted: number;
  /** Pods whose restart status can be determined. */
  podsChecked: number;
  podsUnrecorded: number;
  samePodKnown: number;
  samePod: number;
  readyKnown: number;
  ready: number;
  putBackConfirmed: number;
  putBackUnconfirmed: number;
}

const restartKnown = (p: ActivityPodProof): boolean =>
  p.uidChanged === true || (p.restartsBefore !== null && p.restartsAfter !== null);

export function summarizeProof(entries: readonly ActivityEntryView[]): ProofSummary {
  const touched = entries.filter(touchedPods);
  const pods = touched.flatMap((e) => e.proof?.pods ?? []);
  const sameKnown = pods.filter((p) => p.uidChanged !== null);
  const readyKnown = pods.filter((p) => p.ready !== null);
  const checked = pods.filter(restartKnown).length;
  return {
    changes: touched.length,
    changesRestartFree: touched.filter((e) => e.proof !== null && e.proof.restartFree && !e.proof.pods.some(podRestarted)).length,
    changesWithoutProof: touched.filter((e) => e.proof === null).length,
    pods: pods.length,
    podsRestarted: pods.filter(podRestarted).length,
    podsChecked: checked,
    podsUnrecorded: pods.length - checked,
    samePodKnown: sameKnown.length,
    samePod: sameKnown.filter((p) => p.uidChanged === false).length,
    readyKnown: readyKnown.length,
    ready: readyKnown.filter((p) => p.ready === true).length,
    putBackConfirmed: touched.filter((e) => e.proof?.reverted === true).length,
    putBackUnconfirmed: touched.filter((e) => e.proof?.reverted === false).length,
  };
}

export type DrainSummary =
  | { kind: 'unreadable' }
  | { kind: 'none' }
  | {
    kind: 'runs';
    runsKept: number;
    /** Epoch ms of the newest / oldest run with a parseable date; null when none parses. */
    newest: number | null;
    oldest: number | null;
    liveRuns: number;
    simulatedRuns: number;
    podsEvicted: number;
    abortedOrRefused: number;
    /** True when the history starts after the window start, so a short history isn't read as a quiet week. */
    historyStartsNote: boolean;
  };

/** `windowStartMs` is the start of the Overview's window (now minus 7 days), passed in to stay pure. */
export function summarizeDrains(response: ConsolidationRunResponse, windowStartMs: number): DrainSummary {
  if (response.status === 'UNREADABLE') return { kind: 'unreadable' };
  const runs = response.runs ?? [];
  if (response.status === 'NO_RUNS' || runs.length === 0) return { kind: 'none' };
  const times = runs.map((r) => Date.parse(r.generatedAt)).filter((t) => !Number.isNaN(t));
  const live = runs.filter((r) => r.mode === 'live');
  const oldest = times.length ? Math.min(...times) : null;
  return {
    kind: 'runs',
    runsKept: runs.length,
    newest: times.length ? Math.max(...times) : null,
    oldest,
    liveRuns: live.length,
    simulatedRuns: runs.filter((r) => r.mode !== 'live').length,
    podsEvicted: live.reduce((sum, r) => sum + (r.summary?.podsEvicted ?? 0), 0),
    abortedOrRefused: live.reduce((sum, r) => sum + (r.summary?.aborted ?? 0) + (r.summary?.refused ?? 0), 0),
    historyStartsNote: oldest !== null && windowStartMs < oldest,
  };
}
