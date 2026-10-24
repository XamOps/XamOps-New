/** Test fixtures for Automation › Spot interruptions, in the backend's SpotInterruptionsDto shape. */
import type { SpotInterruptionEvent, SpotInterruptionsDto } from '../../../../../../types/k8sAutomation';

export const NOW = Date.parse('2026-10-24T12:00:00Z');
export const isoAgo = (ms: number) => new Date(NOW - ms).toISOString();
const MIN = 60_000;

let nextId = 1;

export function event(over: Partial<SpotInterruptionEvent> = {}): SpotInterruptionEvent {
  return {
    id: nextId++,
    nodeName: 'ip-10-0-1-23.ap-south-1.compute.internal',
    nodeCreatedAt: isoAgo(3 * 24 * 60 * MIN),
    instanceId: null,
    instanceType: 't3.medium',
    zone: 'ap-south-1a',
    capacityType: 'spot',
    nodegroup: 'ng-spot-1',
    noticeKind: 'unconfirmed',
    sources: ['node-removed'],
    noticedAt: isoAgo(60 * MIN),
    rebalanceNoticedAt: null,
    interruptionNoticedAt: null,
    nodeRemovedAt: isoAgo(60 * MIN),
    cordonedBeforeRemoval: false,
    handling: { mode: 'unknown', reason: null },
    drain: null,
    workloads: [{ namespace: 'team-a', kind: 'Deployment', name: 'web', podsOnNode: 2 }],
    podCount: 2,
    workloadsTruncated: false,
    workloadsSnapshotAt: isoAgo(65 * MIN),
    nodeLastSeenAt: isoAgo(65 * MIN),
    recovery: { status: 'recovered', lowerSeconds: 0, upperSeconds: 300, recoveredByFirstSighting: false, observedAt: isoAgo(55 * MIN) },
    pending: null,
    ...over,
  };
}

export function dto(over: Partial<SpotInterruptionsDto> = {}): SpotInterruptionsDto {
  return {
    windowDays: 30,
    truncated: false,
    retentionDays: 365,
    recordingEnabled: true,
    fleet: { totalNodes: 6, spotNodes: 2, spotSharePct: 33, collectedAt: isoAgo(2 * MIN) },
    coverage: { agentSnapshotAt: isoAgo(2 * MIN), automationReportAt: isoAgo(MIN), handlingNow: { mode: 'simulate', reason: null } },
    events: [],
    ...over,
  };
}

/** The three-row window the screenshots use: a 2-min notice, a rebalance recommendation, an unconfirmed removal. */
export const MIXED: SpotInterruptionEvent[] = [
  event({
    noticeKind: 'interruption',
    sources: ['nth-taint'],
    interruptionNoticedAt: isoAgo(3 * 60 * MIN),
    noticedAt: isoAgo(3 * 60 * MIN),
    handling: { mode: 'simulate', reason: 'simulate: DRY_RUN is on' },
    drain: { status: 'simulated', reason: null, dryRun: true, finishedAt: isoAgo(3 * 60 * MIN - MIN) },
    recovery: { status: 'recovered', lowerSeconds: 180, upperSeconds: 480, recoveredByFirstSighting: false, observedAt: isoAgo(170 * MIN) },
  }),
  event({
    nodeName: 'ip-10-0-2-45.ap-south-1.compute.internal',
    noticeKind: 'rebalance',
    sources: ['karpenter-event'],
    rebalanceNoticedAt: isoAgo(30 * MIN),
    noticedAt: isoAgo(30 * MIN),
    nodeRemovedAt: null,
    handling: { mode: 'not-eligible', reason: 'live: the node carries no legacy interruption label, so XamOps does not drain it' },
    recovery: { status: 'tracking', lowerSeconds: null, upperSeconds: null, recoveredByFirstSighting: false, observedAt: null },
  }),
  event({
    nodeName: 'ip-10-0-3-77.ap-south-1.compute.internal',
    noticeKind: 'unconfirmed',
    sources: ['node-removed'],
    workloads: [{ namespace: 'batch', kind: 'Pod', name: 'one-off', podsOnNode: 1 }],
    podCount: 1,
    recovery: { status: 'not-measurable', lowerSeconds: null, upperSeconds: null, recoveredByFirstSighting: false, observedAt: null },
  }),
];
