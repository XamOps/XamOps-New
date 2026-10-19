/**
 * The vertical engine's enums as text (docs/cloudk8s/ui-revamp-design.md §9.2). Unknown values,
 * which the contract allows (§5.2: the lists can grow), render as their own code.
 */
import { isUnknown } from '../../../../api/verticalRecommendationsAdapter';
import type { Known, VerticalWorkload } from '../../../../types/verticalRecommendations';

const text = <T extends string>(labels: Record<T, string>, value: Known<T>): string =>
  isUnknown(value) ? value.raw : labels[value] ?? value;

export const MODE_LABELS = { OFF: 'Off', RECOMMEND: 'Recommend', AUTO: 'Auto' } as const;

export const SKIP_REASON_LABELS = {
  PROTECTED_NAMESPACE: 'Skipped: protected namespace',
  SINGLE_REPLICA: 'Skipped: single replica',
  ROLLOUT_IN_PROGRESS: 'Skipped: rollout in progress',
  LOCKED: 'Skipped: locked by annotation (xamops.io/lock)',
  HPA_VOLATILE: 'Skipped: HPA scaled recently',
  INSUFFICIENT_HISTORY: 'Collecting history',
  POLICY_OFF: 'Off · not evaluated',
} as const;

export const FINDING_LABELS = {
  REQUESTS_UNSET: 'Requests unset',
  IDLE: 'Idle',
  THROTTLED: 'CPU throttled',
  OOM_RISK: 'OOM kills in window',
  HPA_COEXISTENCE: 'HPA-managed',
  NODE_CAPACITY_CAPPED: 'Capped by node size',
} as const;

export const modeText = (mode: VerticalWorkload['policy']['mode']) => text(MODE_LABELS, mode);
export const skipReasonText = (reason: NonNullable<VerticalWorkload['skipReason']>) => text(SKIP_REASON_LABELS, reason);
export const findingText = (finding: VerticalWorkload['findings'][number]) => text(FINDING_LABELS, finding);

// ── Workload status buckets (glossary §2) ────────────────────────────────────

/** The Overview's KPI split, in the glossary's order; only the non-zero ones are listed. */
export const WORKLOAD_BUCKETS = [
  'Over-provisioned', 'Needs more resources', 'Right-sized', 'Left alone', 'Not running', 'Not enough data', 'Skipped',
] as const;
export type WorkloadBucket = (typeof WORKLOAD_BUCKETS)[number];

/** A code as the engine sent it, known or not. */
const raw = (value: Known<string>): string => (isUnknown(value) ? value.raw : value);

/**
 * The one bucket a workload falls in, by the first glossary §2 rule that matches. `notRunning` is
 * rule 1a, which comes from the pod list rather than the engine (k8sDerivations.notRunningWorkloads).
 * Direction compares each container's recommended request with its current one; containers whose
 * requests are unset are left out of that test.
 */
export function workloadBucket(workload: VerticalWorkload, notRunning = false): WorkloadBucket {
  const skip = workload.skipReason === null ? null : raw(workload.skipReason);
  if (skip === 'POLICY_OFF') return 'Skipped';
  if (notRunning) return 'Not running';
  // A mutating native VPA owns the workload: the engine carries VPA_MANAGED on the horizontal half,
  // not on skipReason, so read it here or a VPA-managed workload with no HPA mislabels as
  // 'Right-sized'.
  const hz = (workload.horizontal?.reasons ?? []).map(raw);
  if (hz.includes('VPA_MANAGED')) return 'Left alone';
  if (skip === 'VPA_MANAGED') return 'Left alone';
  if (skip === 'INSUFFICIENT_HISTORY') return 'Not enough data';
  if (skip !== null) return 'Skipped';
  const reasons = (c: VerticalWorkload['containers'][number]) => c.reasons.map(raw);
  const suggested = workload.containers.filter((c) => c.recommended !== null);
  if (suggested.length > 0 && suggested.every((c) => reasons(c).includes('REQUESTS_UNSET'))) return 'Left alone';
  const tested = suggested.filter((c) => !reasons(c).includes('REQUESTS_UNSET'));
  const moves = (sign: 1 | -1) => tested.some((c) => {
    const cpu = c.current.cpuRequestM !== null && c.recommended!.cpuRequestM !== null ? Math.sign(c.recommended!.cpuRequestM - c.current.cpuRequestM) : 0;
    const mem = c.current.memRequestMi !== null && c.recommended!.memRequestMi !== null ? Math.sign(c.recommended!.memRequestMi - c.current.memRequestMi) : 0;
    return cpu === sign || mem === sign;
  });
  if (moves(1)) return 'Needs more resources';
  if (moves(-1)) return 'Over-provisioned';
  const all = workload.containers.flatMap(reasons);
  if (all.includes('COOLDOWN') || all.includes('HPA_CPU_MANAGED') || all.includes('HPA_MEMORY_MANAGED')) return 'Left alone';
  return 'Right-sized';
}

/** Workloads per bucket, the non-zero buckets only, in the glossary's order. */
export function bucketCounts(workloads: readonly VerticalWorkload[], isNotRunning: (w: VerticalWorkload) => boolean = () => false): Array<{ bucket: WorkloadBucket; count: number }> {
  const counts = new Map<WorkloadBucket, number>();
  for (const w of workloads) {
    const bucket = workloadBucket(w, isNotRunning(w));
    counts.set(bucket, (counts.get(bucket) ?? 0) + 1);
  }
  return WORKLOAD_BUCKETS.filter((b) => counts.has(b)).map((bucket) => ({ bucket, count: counts.get(bucket)! }));
}
