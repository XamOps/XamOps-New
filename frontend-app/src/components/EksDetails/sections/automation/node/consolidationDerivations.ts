/**
 * Pure derivations for Automation › Consolidation: the automation agent's look-only plan
 * (ConsolidationPlanResponse) turned into the page's words and figures. Real data only: anything
 * the plan or the cost engine didn't give is a dash, never a guess or a 0.
 */
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { formatPercent, formatUsd } from '../../../../../utils/k8sQuantities';
import { costReasonCopy } from '../../../tabs/eksCostReasons';
import type {
  ConsolidationActor,
  ConsolidationBlocker,
  ConsolidationNodeRef,
  ConsolidationPlanResponse,
  ConsolidationSavings,
} from '../../../../../types/k8sAutomation';

/** "ip-10-0-1-106" from "ip-10-0-1-106.ap-south-1.compute.internal". */
export const shortNode = (name: string): string => name.split('.')[0];

/** "35%", "15.6%"; a dash for null. */
export const pctText = (v: number | null | undefined): string => formatPercent(v, 1);

/** A blank plan field (no label on the node) as a dash. */
export const orDash = (v: string | null | undefined): string => (v && v.trim() ? v : DASH);

/** "CPU 35% · memory 15.6%" of allocatable, from the plan's requests. */
export const requestedText = (node: Pick<ConsolidationNodeRef, 'cpuRequestedPct' | 'memRequestedPct'>): string =>
  `CPU ${pctText(node.cpuRequestedPct)} · memory ${pctText(node.memRequestedPct)}`;

export interface SavingsText {
  /** "$48.60", "$0.00", or a dash. */
  value: string;
  /** "2 of 2 nodes priced"; null when there is nothing to price or no pricing. */
  priced: string | null;
  /** The one unpriced reason, in words, when every unpriced node shares it. */
  reason: string | null;
  /** "Priced subtotal $24.30", when some but not all nodes are priced. */
  subtotal: string | null;
}

/**
 * The projected monthly savings: the cost engine's sum when every removed node is priced, else a
 * dash with how many are priced and, when there is one, the reason the rest aren't.
 */
export function savingsText(savings: ConsolidationSavings | null): SavingsText {
  if (!savings) return { value: DASH, priced: null, reason: null, subtotal: null };
  const total = savings.pricedNodes + savings.unpricedNodes;
  const priced = total > 0 ? `${savings.pricedNodes} of ${total} ${total === 1 ? 'node' : 'nodes'} priced` : null;
  if (savings.projectedMonthlySavings !== null) {
    return { value: formatUsd(savings.projectedMonthlySavings), priced, reason: null, subtotal: null };
  }
  const reasons = [...new Set(savings.nodes.filter((n) => n.monthlyCost === null).map((n) => n.reason ?? ''))];
  return {
    value: DASH,
    priced,
    reason: reasons.length === 1 && reasons[0] ? costReasonCopy(reasons[0]) : null,
    subtotal: savings.pricedMonthlySubtotal !== null ? `Priced subtotal ${formatUsd(savings.pricedMonthlySubtotal)}` : null,
  };
}

/** One removed node's monthly cost and, when unpriced, why. */
export function nodeCostText(response: ConsolidationPlanResponse, nodeName: string): { value: string; reason: string | null } {
  if (!response.savings) {
    return { value: DASH, reason: response.freshness === 'STALE' ? 'Not priced while the plan is out of date.' : null };
  }
  const cost = response.savings.nodes.find((n) => n.nodeName === nodeName);
  if (!cost) return { value: DASH, reason: costReasonCopy(null) };
  if (cost.monthlyCost === null) return { value: DASH, reason: costReasonCopy(cost.reason) };
  return { value: formatUsd(cost.monthlyCost), reason: null };
}

export interface MetricCellText {
  label: string;
  value: string | number;
  sub: string;
}

/**
 * The hero strip: Nodes to remove, Empty, Drain, Blocked and Projected savings / month. Without a
 * readable plan every figure is a dash.
 */
export function metricCells(response: ConsolidationPlanResponse | null): MetricCellText[] {
  const plan = response?.plan ?? null;
  const s = savingsText(response?.savings ?? null);
  const podsMoving = plan ? plan.moves.reduce((n, m) => n + m.pods.length, 0) : 0;
  const savingsSub =
    response?.freshness === 'STALE'
      ? 'Not priced: the plan is out of date'
      : !plan
        ? 'from the cost engine'
        : [s.priced, s.subtotal, s.reason].filter(Boolean).join(' · ') || (plan.moves.length === 0 ? 'nothing to remove' : 'from the cost engine');
  return [
    {
      label: 'Nodes to remove',
      value: plan ? plan.moves.length : DASH,
      sub: plan ? `of ${plan.summary.nodesEvaluated} ${plan.summary.nodesEvaluated === 1 ? 'node' : 'nodes'} evaluated` : 'from the automation agent',
    },
    { label: 'Empty', value: plan ? plan.summary.empty : DASH, sub: 'no pods to move' },
    {
      label: 'Drain',
      value: plan ? plan.summary.drain : DASH,
      sub: plan ? `${podsMoving} ${podsMoving === 1 ? 'pod moves' : 'pods move'} to other nodes` : 'pods move to other nodes',
    },
    { label: 'Blocked', value: plan ? plan.summary.blocked : DASH, sub: "can't be removed safely" },
    { label: 'Projected savings / month', value: response?.freshness === 'STALE' ? DASH : s.value, sub: savingsSub },
  ];
}

const ACTOR_LABEL: Record<ConsolidationActor['kind'], string> = {
  KARPENTER: 'Karpenter',
  CLUSTER_AUTOSCALER: 'Cluster Autoscaler',
  MULTIPLE: 'Karpenter and the Cluster Autoscaler',
  NONE: 'Neither',
};

/** The note shown when neither Karpenter nor the Cluster Autoscaler removes nodes. */
export const NONE_ACTOR_NOTE =
  "v1 will remove nodes with an ASG terminate that lowers desired capacity, never below the node group's minimum — display only";

/** Who removes nodes on this cluster, as the agent detected it. */
export function actorText(actor: ConsolidationActor): { label: string; detail: string | null } {
  const label = ACTOR_LABEL[actor.kind] ?? DASH;
  if (actor.kind === 'NONE') return { label, detail: `Neither Karpenter nor the Cluster Autoscaler was found. ${NONE_ACTOR_NOTE}.` };
  return { label, detail: actor.note?.trim() ? actor.note : null };
}

/** A blocker in words: its reason, and the pod when it is about one. */
const blockerLine = (b: ConsolidationBlocker): string => (b.pod ? `${b.reason} (${b.pod})` : b.reason);

/**
 * Why a node can't be removed: the first reason, "+N more", and the remaining reasons as a detail
 * line. The blocking pods are listed separately; a node-level reason has none.
 */
export function blockerSummary(blockers: ConsolidationBlocker[]): {
  label: string;
  summary: string;
  detail: string | null;
  pods: string[];
} {
  if (blockers.length === 0) return { label: 'Blocked', summary: 'Blocked', detail: null, pods: [] };
  const [first, ...rest] = blockers;
  return {
    label: first.reason,
    summary: rest.length > 0 ? `${first.reason} +${rest.length} more` : first.reason,
    detail: rest.length > 0 ? rest.map(blockerLine).join('; ') : null,
    pods: [...new Set(blockers.map((b) => b.pod).filter((p) => p))],
  };
}

/** "4 min", "3 h", "2 days". */
function ageText(seconds: number): string {
  const min = Math.max(0, Math.floor(seconds / 60));
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  if (h < 48) return `${h} h`;
  return `${Math.floor(h / 24)} days`;
}

/** How old the plan is, in words; null when there is no plan. */
export function freshnessText(response: ConsolidationPlanResponse): string | null {
  if (!response.plan) return null;
  if (response.freshness === 'STALE') {
    return response.ageSeconds === null
      ? "This plan's time can't be read, so it isn't priced. The automation agent hasn't reported a newer one."
      : `This plan is ${ageText(response.ageSeconds)} old: the automation agent hasn't reported a newer one, so it isn't priced.`;
  }
  return response.ageSeconds === null ? null : `This plan is ${ageText(response.ageSeconds)} old.`;
}
