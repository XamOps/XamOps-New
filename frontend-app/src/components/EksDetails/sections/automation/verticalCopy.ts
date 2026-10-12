/**
 * The vertical engine's enums as text (docs/cloudk8s/ui-revamp-design.md §9.2). Unknown values,
 * which the contract allows (§5.2: the lists can grow), render as their own code.
 */
import { isUnknown } from '../../../../api/verticalRightsizerAdapter';
import type { Known, VerticalWorkload } from '../../../../types/verticalRightsizer';

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
