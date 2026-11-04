/**
 * Manual mode proposals (Phase B design §3.8, §3.10): the wire shapes of the user-facing proposal API
 * under `/api/eks/autoscaling/{cluster}`. Every field is always present (null, never omitted), so the
 * types are exact. `params` and `evidence` are opaque on the server; the UI reads only what it renders.
 */
export const PROPOSAL_STATUSES = [
  'PENDING', 'APPROVED', 'DISPATCHED', 'EXECUTED', 'FAILED', 'REJECTED', 'EXPIRED', 'STALE', 'REFUSED',
] as const;
export type ProposalStatus = (typeof PROPOSAL_STATUSES)[number];

export const PROPOSAL_TYPES = ['WORKLOAD_RESIZE', 'NODE_DRAIN', 'NODE_CONSOLIDATE', 'REBALANCE'] as const;
export type ProposalType = (typeof PROPOSAL_TYPES)[number];

/** The agent's own word on whether the stored approval mode is in force (same tri-state as `effectiveActing`). */
export type EffectiveState = 'active' | 'switching' | 'unknown';
export type ApprovalMode = 'AUTONOMOUS' | 'MANUAL';

export interface ProposalDto {
  id: string;
  version: number;
  actionType: ProposalType;
  operation: string | null;
  originController: string;
  targetKind: 'WORKLOAD' | 'NODE';
  targetRef: string;
  status: ProposalStatus;
  statusReason: string | null;
  verdict: 'ALLOWED' | 'REFUSED';
  refusalCodes: string[];
  refusalReason: string | null;
  refusalStage: 'PROPOSAL' | 'EXECUTION' | null;
  planFingerprint: string;
  dryRun: boolean;
  proposedAt: string | null;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  expiresAt: string | null;
  approvableAfter: string | null;
  approvedUntil: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  outcomeStatus: string | null;
  outcomeVerified: boolean | null;
  params: Record<string, unknown> | null;
  evidence: Record<string, unknown> | null;
}

export interface ProposalEventDto {
  at: string | null;
  actorType: string;
  actorName: string | null;
  event: string;
  detail: string | null;
}

export interface ProposalDetailDto {
  proposal: ProposalDto;
  events: ProposalEventDto[];
}

export interface ProposalListManual {
  available: boolean;
  reason: string | null;
  workloadProposals: boolean;
}

export interface ProposalCounts {
  pending: number;
  approved: number;
  dispatched: number;
  refused: number;
}

export interface ProposalListDto {
  state: 'OK' | 'UNREADABLE';
  approvalMode: ApprovalMode;
  effective: EffectiveState;
  manual: ProposalListManual | null;
  counts: ProposalCounts | null;
  items: ProposalDto[];
  nextCursor: string | null;
  generatedAt: string | null;
  lastAgentCheckIn: string | null;
}

export interface ApproveBody {
  expectedVersion: number;
  planFingerprint: string;
  note?: string;
}

export interface RejectBody {
  expectedVersion: number;
  note?: string;
}

export interface DecisionResponse {
  id: string;
  status: string;
  alreadyDecided: boolean;
}

export interface ApprovalModeResponse {
  mode: ApprovalMode;
  effective: EffectiveState;
  changedAt: string | null;
}
