/**
 * Activity feed entries as `AutomationActivityService` serialises them (FEAT-007), in the shapes the
 * real xamops-test-1 history produced: a deferral XamOps put back (throttled-encoder), a restart-free
 * apply (overprovisioned-api), a test run, an HPA bound change (php-apache), a refusal, a mode
 * switch, and a row written before the activity log existed (its "before" is the target it asked
 * for, labelled REQUESTED).
 */
import type { ActivityEntryWire, ActivityPageWire } from '../../../../../../types/automationActivity';

const MI = 1024 * 1024;
const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
const sizes = (cpuRequestMillis: number | null, cpuLimitMillis: number | null, memRequestMi: number | null, memLimitMi: number | null) => ({
  cpuRequestMillis,
  cpuLimitMillis,
  memRequestBytes: memRequestMi === null ? null : memRequestMi * MI,
  memLimitBytes: memLimitMi === null ? null : memLimitMi * MI,
});

export const PUT_BACK: ActivityEntryWire = {
  id: 41, at: iso(4 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
  target: { namespace: 'prod-sim', kind: 'Deployment', name: 'throttled-encoder' },
  dryRun: false, outcome: 'NOT_CHANGED', result: 'PUT_BACK', terminal: 'DEFERRED', restartFree: true,
  change: { containers: [{ container: 'encoder', before: sizes(10, 50, 64, 128), beforeBasis: 'OBSERVED', after: sizes(10, 50, 64, 128), requested: sizes(63, 200, null, null) }], hpa: null, mode: null },
  proof: {
    restartFree: true,
    reverted: true,
    pods: [{ pod: 'throttled-encoder-8b999cc87-mcw8q', node: null, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: { state: 'REVERTED', detail: 'XamOps put the pod back.' } }],
  },
  detail: "The kubelet deferred the resize of pod prod-sim/throttled-encoder-8b999cc87-mcw8q because its node has no room for it right now (Node didn't have enough resource: cpu, requested: 63, used: 1900, capacity: 1930). Revert: REVERTED.",
  reference: 'd3f3rr3d-0000-4000-8000-000000000000',
};

export const APPLIED: ActivityEntryWire = {
  id: 40, at: iso(5 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
  target: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
  dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: 'APPLIED', restartFree: true,
  change: { containers: [{ container: 'api', before: sizes(1000, 1000, 1024, 1024), beforeBasis: 'OBSERVED', after: sizes(25, 25, 250, 250), requested: sizes(25, 25, 250, 250) }], hpa: null, mode: null },
  proof: {
    restartFree: true,
    reverted: null,
    pods: [
      { pod: 'overprovisioned-api-6f7d8-fcnhh', node: null, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: null },
      { pod: 'overprovisioned-api-6f7d8-gtnh5', node: null, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: null },
    ],
  },
  detail: null,
  reference: '7b8d4cda-0000-4000-8000-000000000000',
};

export const TEST_RUN: ActivityEntryWire = {
  id: 39, at: iso(6 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
  target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
  dryRun: true, outcome: 'TEST_RUN', result: 'TEST_RUN', terminal: 'DRY_RUN', restartFree: false,
  change: { containers: [{ container: 'sleeper', before: null, beforeBasis: null, after: null, requested: sizes(25, 50, null, null) }], hpa: null, mode: null },
  proof: null,
  detail: 'Dry run: no patch was sent to the cluster, so nothing was applied.',
  reference: '940bc53d-0000-4000-8000-000000000000',
};

export const HPA_CHANGE: ActivityEntryWire = {
  id: 38, at: iso(7 * 60_000), feature: 'HPA', origin: 'MANUAL', who: 'A user', action: 'HPA_CHANGE',
  target: { namespace: 'team-b', kind: 'HorizontalPodAutoscaler', name: 'php-apache' },
  dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: null, restartFree: false,
  change: { containers: null, hpa: { before: { minReplicas: 1, maxReplicas: 5, targetUtilizationPct: 50 }, after: { minReplicas: 1, maxReplicas: 8, targetUtilizationPct: 50 } }, mode: null },
  proof: null,
  detail: null,
  reference: 'c5c6b919-13c6-4af2-a5cf-7dcacf0f8c0d',
};

export const REFUSED: ActivityEntryWire = {
  id: 37, at: iso(8 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
  target: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
  dryRun: false, outcome: 'REFUSED', result: 'REFUSED', terminal: null, restartFree: false,
  change: null, proof: null,
  detail: 'K8S_GITOPS_MANAGED: Template persistence is not available for team-a/Deployment/overprovisioned-api.',
  reference: null,
};

export const MODE: ActivityEntryWire = {
  id: 36, at: iso(9 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'MODE_CHANGE',
  target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
  dryRun: false, outcome: 'POLICY_CHANGE', result: null, terminal: null, restartFree: false,
  change: { containers: null, hpa: null, mode: { from: 'RECOMMEND', to: 'AUTO' } },
  proof: null,
  detail: 'Mode changed from RECOMMEND to AUTO.',
  reference: null,
};

/** A row written before the activity log, by the automatic loop: no observed "before", no proof. */
export const LEGACY: ActivityEntryWire = {
  id: 12, at: iso(3 * 24 * 3600_000), feature: 'VERTICAL', origin: 'AUTO', who: 'XamOps (automatic)', action: 'RESIZE',
  target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
  dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: null, restartFree: true,
  change: { containers: [{ container: 'sleeper', before: sizes(25, 50, null, null), beforeBasis: 'REQUESTED', after: sizes(25, 50, 128, 256), requested: sizes(25, 50, null, null) }], hpa: null, mode: null },
  proof: null,
  detail: null,
  reference: null,
};

export const ALL_ENTRIES: ActivityEntryWire[] = [PUT_BACK, APPLIED, TEST_RUN, HPA_CHANGE, REFUSED, MODE, LEGACY];

export const feedPage = (entries: ActivityEntryWire[] = ALL_ENTRIES, nextCursor: string | null = null, enabled = true): ActivityPageWire => ({
  engine: { enabled },
  entries,
  nextCursor,
});
