/**
 * Pieces the HPA list and the HPA detail share: the source words for a live number the XamOps
 * agent didn't report, and the two HPA history charts, which need kube-state-metrics' series that
 * XamOps doesn't read yet (UI v3 plan §2: wired in Phase 2), so they show the honest empty state.
 */
import type { FC } from 'react';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';

/** Names the source of an HPA's live numbers when one is missing. */
export const FROM_AGENT = 'from the XamOps agent';

/** The two HPA history charts, in the "History arrives with the usage feed" state. */
export const HpaHistoryCards: FC<{ subject?: string }> = ({ subject = 'each HPA' }) => (
  <div className="grid gap-4 lg:grid-cols-2">
    <ChartCard
      title="Replicas and CPU use, together"
      source={`HPA history (planned): kube-state-metrics in your cluster, kube_horizontalpodautoscaler_status_current_replicas, spec_min_replicas, spec_max_replicas and the CPU target metric for ${subject}.`}
      state={{ kind: 'history-pending', from: `It will come from kube-state-metrics in your cluster: replicas against the minimum and maximum, and CPU use against the target, for ${subject}.` }}
    />
    <ChartCard
      title="Time at its maximum"
      source="HPA history (planned): kube-state-metrics, current replicas >= maximum replicas, at 1-minute steps."
      state={{ kind: 'history-pending', from: `It will come from kube-state-metrics in your cluster: each minute ${subject} ran its maximum number of replicas.` }}
    />
  </div>
);
