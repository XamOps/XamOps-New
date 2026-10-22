/**
 * Machine reason code → human copy for the Cost tab's dash states (design Decisions 4/5).
 *
 * Total over all seven NodeRate.Reason codes the backend can emit for an unpriceable node
 * (NO_LIVE_PRICE, NO_SPOT_HISTORY, NODE_ZONE_UNKNOWN, FARGATE_UNPRICEABLE, UNKNOWN_INSTANCE_TYPE,
 * NODE_CAPACITY_UNKNOWN, PRICING_DISABLED). costReasonCopy() falls back to a generic line so an
 * unmapped / future code never renders a raw machine string or `undefined`.
 */
export const EKS_COST_REASONS: Record<string, string> = {
  NO_LIVE_PRICE: 'No live AWS price for this instance type in this region.',
  NO_SPOT_HISTORY: 'No recent AWS spot price history for this instance type / zone.',
  NODE_ZONE_UNKNOWN: 'This spot node has no readable availability zone.',
  FARGATE_UNPRICEABLE: 'Not priced per instance type (Fargate or no instance-type label).',
  UNKNOWN_INSTANCE_TYPE: 'This node reports no EC2 instance type.',
  NODE_CAPACITY_UNKNOWN: 'This node reported no usable CPU/memory capacity.',
  PRICING_DISABLED: 'Live Kubernetes cost pricing is not enabled for this cluster.',
};

/** Human copy for a machine reason code, with a generic fallback for any unmapped / null code. */
export const costReasonCopy = (code?: string | null): string =>
  (code && EKS_COST_REASONS[code]) || 'Cost unavailable.';
