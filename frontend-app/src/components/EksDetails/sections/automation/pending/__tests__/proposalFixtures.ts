import type { ProposalCounts, ProposalDto, ProposalListDto } from '../../../../../../types/automationProposals';

export const NOW = Date.parse('2026-11-01T12:00:00Z');
const iso = (offsetMs: number) => new Date(NOW + offsetMs).toISOString();
export const IN_72_MIN = 72 * 60_000;

export function proposal(o: Partial<ProposalDto> = {}): ProposalDto {
  return {
    id: 'p-1',
    version: 3,
    actionType: 'NODE_DRAIN',
    operation: 'DRAIN',
    originController: 'node-optimizer',
    targetKind: 'NODE',
    targetRef: 'ip-10-0-1-5.ec2.internal',
    status: 'PENDING',
    statusReason: null,
    verdict: 'ALLOWED',
    refusalCodes: [],
    refusalReason: null,
    refusalStage: null,
    planFingerprint: 'sha256:' + 'a'.repeat(64),
    dryRun: false,
    proposedAt: iso(-60_000),
    firstSeenAt: iso(-60_000),
    lastSeenAt: iso(-30_000),
    expiresAt: iso(IN_72_MIN),
    approvableAfter: null,
    approvedUntil: null,
    decidedByName: null,
    decidedAt: null,
    decisionNote: null,
    outcomeStatus: null,
    outcomeVerified: null,
    params: { cpuUsagePct: 4.2, memUsagePct: 11, podsToMove: 7 },
    evidence: {
      fit: {
        drainable: true,
        moves: [{ pod: 'shop/api-1', from: 'ip-10-0-1-5', to: 'ip-10-0-2-9' }],
        pdbChecks: [{ pdb: 'shop/api', disruptionsAllowed: 1, ok: true }],
        summary: '7 pods fit on the remaining nodes',
      },
      guards: { respectPodDisruptionBudgets: true, protectStatefulSets: true },
      stateful: [],
      actor: 'KARPENTER',
    },
    ...o,
  };
}

export function workloadProposal(o: Partial<ProposalDto> = {}): ProposalDto {
  return proposal({
    id: 'w-1',
    actionType: 'WORKLOAD_RESIZE',
    operation: 'IN_PLACE',
    originController: 'vertical-engine',
    targetKind: 'WORKLOAD',
    targetRef: 'shop/Deployment/api',
    params: {
      namespace: 'shop', kind: 'Deployment', name: 'api', method: 'IN_PLACE',
      current: [{ container: 'api', cpuRequestM: 500, cpuLimitM: null, memRequestMi: 512, memLimitMi: null }],
      recommended: [{ container: 'api', cpuRequestM: 220, cpuLimitM: null, memRequestMi: 512, memLimitMi: null }],
    },
    evidence: {},
    ...o,
  });
}

export function list(items: ProposalDto[], o: Partial<ProposalListDto> = {}): ProposalListDto {
  const counts: ProposalCounts = {
    pending: items.filter((p) => p.status === 'PENDING').length,
    approved: items.filter((p) => p.status === 'APPROVED').length,
    dispatched: items.filter((p) => p.status === 'DISPATCHED').length,
    refused: items.filter((p) => p.status === 'REFUSED').length,
  };
  return {
    state: 'OK',
    approvalMode: 'MANUAL',
    effective: 'active',
    manual: { available: true, reason: null, workloadProposals: true },
    counts,
    items,
    nextCursor: null,
    generatedAt: iso(0),
    lastAgentCheckIn: iso(-20_000),
    ...o,
  };
}
export { iso };
