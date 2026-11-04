/**
 * The words of Automation › Pending actions (Phase B design §3.10): action, status, outcome and
 * refusal reasons in plain language, and one line per approve error. The raw code stays available in
 * the evidence detail. Pure; nothing here touches the network.
 */
import type { Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type { ProposalDto, ProposalType } from '../../../../../types/automationProposals';

export const DASH = '—';

export function actionWord(type: ProposalType, operation: string | null): string {
  switch (type) {
    case 'WORKLOAD_RESIZE': return 'Resize workload';
    case 'NODE_DRAIN': return operation === 'CORDON_ONLY' ? 'Cordon node' : 'Drain node';
    case 'NODE_CONSOLIDATE': return operation === 'EMPTY' ? 'Empty node' : 'Consolidate node';
    case 'REBALANCE': return 'Rebalance node';
    default: return 'Action';
  }
}

export interface StatusView {
  tone: Tone;
  word: string;
}

/** The status word and tone of one row; the outcome refines EXECUTED. */
export function statusView(p: ProposalDto): StatusView {
  switch (p.status) {
    case 'PENDING': return { tone: 'info', word: 'Waiting for approval' };
    case 'APPROVED': return { tone: 'info', word: 'Approved, waiting for the agent' };
    case 'DISPATCHED': return { tone: 'info', word: 'Running' };
    case 'EXECUTED':
      if (p.dryRun || p.outcomeStatus === 'simulated') return { tone: 'off', word: 'Simulated, nothing changed' };
      if (p.outcomeVerified === false) return { tone: 'warn', word: 'Executed, not verified' };
      if (p.outcomeVerified === true) return { tone: 'good', word: 'Executed and verified' };
      return { tone: 'good', word: 'Executed' };
    case 'FAILED': return { tone: 'bad', word: 'Failed' };
    case 'REJECTED': return { tone: 'off', word: 'Rejected' };
    case 'EXPIRED': return { tone: 'off', word: 'Expired' };
    case 'STALE': return { tone: 'warn', word: 'Stale, the cluster changed before it ran' };
    case 'REFUSED': return { tone: 'warn', word: 'Refused' };
    default: return { tone: 'off', word: DASH };
  }
}

/** Words for the fit and stateful reason codes (`fitsim/types.go`, `stateful/reasons.go`). */
const REASON_WORDS: Record<string, string> = {
  'pod-affinity': 'A pod has an affinity rule that blocks the move',
  'pod-anti-affinity': 'A pod has an anti-affinity rule that blocks the move',
  'topology-spread': 'A topology spread rule blocks the move',
  'pvc-volume': 'A pod uses a volume that is tied to this node or zone',
  'pvc-volume-unmodelled': "A pod's volume placement can't be checked",
  'ephemeral-volume': 'A pod uses an ephemeral volume that is tied to this node',
  'host-port': 'A pod uses a host port that is taken elsewhere',
  'extended-resource': 'A pod needs a device that no other node has free',
  'critical-namespace': 'Hosts pods in a protected system namespace',
  'self-namespace': 'Hosts XamOps itself',
  statefulset: 'Hosts a StatefulSet pod',
  'single-replica': 'Hosts the only replica of a workload',
  'replicas-unknown': "A workload's replica count can't be read",
  'no-controller': 'Hosts a pod that nothing would recreate',
  'job-owner': 'Hosts a pod owned by a Job',
  'unknown-owner-kind': 'Hosts a pod with an owner XamOps does not know',
  'safe-to-evict-false': 'Hosts a pod marked as unsafe to evict',
  'multiple-pdbs': 'More than one PodDisruptionBudget covers a pod',
  'pdb-no-disruptions': 'A PodDisruptionBudget allows no disruptions',
  'pdb-stale': "A PodDisruptionBudget's status is out of date",
  'db-primary': 'Hosts a database primary',
  'db-sync-standby': 'Hosts a synchronous database standby',
  'db-role-unknown': "Hosts a database whose role can't be told",
  'db-only-ready-replica': 'Hosts the only ready database replica',
  'db-lag-unknown': "A database replica's lag can't be read",
  'db-lag-high': 'A database replica is lagging too far behind',
  'db-emptydir': 'Hosts a database that keeps its data in node-local storage',
};

/** "pdb-no-disruptions:shop/api" → "A PodDisruptionBudget allows no disruptions (shop/api)". */
export function reasonWord(code: string): string {
  const cut = code.indexOf(':');
  const base = cut < 0 ? code : code.slice(0, cut);
  const rest = cut < 0 ? '' : code.slice(cut + 1);
  const word = REASON_WORDS[base];
  if (!word) return code;
  return rest ? `${word} (${rest})` : word;
}

/** The reasons of a refused row, in words; the stored free-text reason when there are no codes. */
export function refusalLines(p: ProposalDto): string[] {
  if (p.refusalCodes.length > 0) return p.refusalCodes.map(reasonWord);
  return p.refusalReason ? [p.refusalReason] : [];
}

/** The outcome sentence of a terminal row. */
export function outcomeLine(p: ProposalDto): string {
  const who = p.decidedByName ? ` by ${p.decidedByName}` : '';
  switch (p.status) {
    case 'REJECTED': return `Rejected${who}`;
    case 'EXPIRED': return 'Expired before anyone decided';
    case 'STALE': return 'Stale, the cluster changed before it ran';
    case 'FAILED': return p.statusReason ? `Failed: ${p.statusReason}` : 'Failed';
    case 'EXECUTED': return statusView(p).word;
    default: return statusView(p).word;
  }
}

const ACTOR_LINE: Record<string, string> = {
  KARPENTER: 'the node is left to KARPENTER to remove',
  CLUSTER_AUTOSCALER: 'the node is left to CLUSTER_AUTOSCALER to remove',
  NONE: 'the node stays cordoned and empty; XamOps does not remove it',
};

/** What happens to the node afterwards, from `evidence.actor`. */
export function actorLine(evidence: Record<string, unknown> | null): string {
  const actor = typeof evidence?.actor === 'string' ? evidence.actor : null;
  return (actor && ACTOR_LINE[actor]) || 'removal is not decided by XamOps';
}

/** Approve error codes (without any K8S_ prefix) → one line each. */
const ERROR_WORDS: Record<string, string> = {
  VERSION_CONFLICT: 'Someone else already decided this.',
  ALREADY_DECIDED: 'Someone else already decided this.',
  FINGERPRINT_MISMATCH: 'This proposal changed just now. Review the updated details and approve again.',
  STALE: 'This action went stale before approval.',
  EXPIRED: 'This action went stale before approval.',
  NOT_FOUND: 'This proposal no longer exists.',
  REFUSED_NOT_APPROVABLE: "This action was refused, so it can't be approved.",
  NOT_YET_APPROVABLE: 'This action can be approved once the consolidation dwell has passed.',
  AGENT_NOT_REACHABLE:
    "The automation agent hasn't checked in recently. Approving now would wait indefinitely, so this is blocked until it does.",
  CONTROLLER_NOT_READY: "This action's controller isn't ready in this environment.",
  MODE_DISABLED: 'Automation is paused for this cluster. Resume it from the Automation mode control to approve.',
  NOT_MANUAL: "This cluster isn't in Manual mode anymore.",
  AGENT_TOO_OLD: 'The automation agent is too old for Manual mode. Upgrade it first.',
  AGENT_KEY_LEGACY: "This cluster's agent key predates Manual mode.",
  EXECUTOR_BUSY: 'The automation agent is busy with another node action. Try again shortly.',
  UNREADABLE: "That proposal can't be read.",
};

/** Codes after which the row should be re-read: what the user saw is no longer current. */
export const REFRESH_ON_CODES: ReadonlySet<string> = new Set([
  'VERSION_CONFLICT', 'ALREADY_DECIDED', 'FINGERPRINT_MISMATCH', 'STALE', 'EXPIRED', 'NOT_FOUND', 'REFUSED_NOT_APPROVABLE',
]);

export function errorLine(reason: string | null, fallback: string): string {
  return (reason && ERROR_WORDS[reason]) || fallback;
}

export const MODE_OFF_LINE = 'Manual mode is off for this cluster';
export const SWITCHING_LINE =
  'Switching to Manual: confirming with the automation agent. Until it does, the cluster may still act on its own for up to about a minute.';
export const UNKNOWN_EFFECTIVE_LINE =
  "The automation agent hasn't checked in recently, so XamOps can't confirm the switch. Nothing new will be proposed until it does.";

/** The one line for a Manual-unavailable reason code. */
export function unavailableLine(reason: string | null): string {
  switch (reason) {
    case 'CONTROLLER_NOT_READY': return "Manual mode isn't enabled in this environment.";
    case 'AGENT_KEY_LEGACY': return "This cluster's agent key predates Manual mode.";
    case 'AGENT_TOO_OLD': return 'The automation agent is too old for Manual mode. Upgrade it first.';
    default: return MODE_OFF_LINE;
  }
}
