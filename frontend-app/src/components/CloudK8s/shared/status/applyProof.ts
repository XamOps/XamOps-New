/**
 * What an apply reply proves, read the same way by every place that words it: the stepper, the
 * workload detail's Now sentence and its lifecycle (glossary v2 §8.2, §11.3; glossary-additions §6).
 *
 * The backend's `ResizeProof` is careful about two claims, and these helpers keep the UI as careful:
 *  - "every pod": `skippedPods` lists the workload's pods that were not resized (not running, being
 *    deleted, an older revision), so a reply that leaves any out reads "every running pod (N of M)",
 *    never "every pod" (review-2 SF3; main's `runningShare` in EksAutomationTab);
 *  - "no restart": `restartFree` is true only when every touched pod was proved not to restart.
 *    False means the question wasn't answered, which is not the same as "it restarted". A restart is
 *    claimed only when a container's own restart count rose between before and after.
 */
import type { ApplyOutcomeWire, ApplyPodProofWire } from '../../../../types/verticalRightsizer';

export type SkippedPodWire = NonNullable<ApplyOutcomeWire['skippedPods']>[number];

/** The workload's pods the reply left out, in its order; empty when it touched every pod. */
export const skippedPodsOf = (outcome: ApplyOutcomeWire): SkippedPodWire[] => outcome.skippedPods ?? [];

/** "N of M" when the reply left pods out, else null (main's `runningShare`). */
export function runningShare(outcome: ApplyOutcomeWire): { touched: number; total: number } | null {
  const skipped = skippedPodsOf(outcome).length;
  if (skipped === 0) return null;
  const touched = (outcome.pods ?? []).length;
  return { touched, total: touched + skipped };
}

/** "every pod", or "every running pod (2 of 3)" when the reply left pods out. */
export function everyPodPhrase(outcome: ApplyOutcomeWire): string {
  const share = runningShare(outcome);
  return share ? `every running pod (${share.touched} of ${share.total})` : 'every pod';
}

/** Why a pod was left out, in words (main's `skippedPodText`). */
const SKIPPED_POD_TEXT: Record<string, string> = {
  TERMINATING: 'being deleted',
  OLD_REVISION: 'from the previous version of the workload',
};

export function skippedPodText(pod: SkippedPodWire): string {
  if (pod.reason && SKIPPED_POD_TEXT[pod.reason]) return SKIPPED_POD_TEXT[pod.reason];
  return pod.phase ? `not running (${pod.phase})` : 'not running';
}

export interface RestartedContainer {
  pod: ApplyPodProofWire;
  /** The pod's place in the reply, canary first. */
  podIndex: number;
  container: string;
  before: number;
  after: number;
  /** The container restarts by its own setting (its resize policy is RestartContainer). */
  bySetting: boolean;
}

/** Every container whose restart count rose during the change: the only proof of a restart. */
export function restartedContainers(outcome: ApplyOutcomeWire): RestartedContainer[] {
  const out: RestartedContainer[] = [];
  (outcome.pods ?? []).forEach((pod, podIndex) => {
    for (const c of pod.containers ?? []) {
      const before = c.restartCountBefore;
      const after = c.restartCountAfter;
      if (before === null || before === undefined || after === null || after === undefined || after <= before) continue;
      const bySetting = (outcome.containers ?? []).some((r) => r.name === c.name && r.restartExpected === true);
      out.push({ pod, podIndex, container: c.name, before, after, bySetting });
    }
  });
  return out;
}
