/**
 * The Overview's 7-day timeline lanes (feedTimeline), on the shapes the live xamops-test-1 feed
 * produced: an HPA and the Deployment it scales share a name in one namespace (team-b/php-apache), so
 * each lane must still say which one it is.
 */
import { describe, expect, it } from 'vitest';
import { adaptActivityEntry } from '../../../../../../api/automationActivityAdapter';
import { adaptEngineResponse } from '../../../../../../api/verticalRightsizerAdapter';
import type { ActivityEntryWire, ActivityPodProofWire } from '../../../../../../types/automationActivity';
import { feedTimeline, savingNow } from '../activityInsights';
import { endpointOne } from '../../workload/__tests__/fixtures';
import { APPLIED, HPA_CHANGE, REFUSED } from './activityFixtures';

const NOW = Date.now();
const at = (minutesAgo: number) => new Date(NOW - minutesAgo * 60_000).toISOString();
const entry = (wire: ActivityEntryWire, i: number) => adaptActivityEntry(wire, i);

describe('feedTimeline lanes', () => {
  it('tells an HPA from the workload of the same name: the workload keeps the name, the HPA says what it is', () => {
    const refused = { ...REFUSED, id: 7, at: at(10), target: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' } };
    const hpa = { ...HPA_CHANGE, id: 6, at: at(20) };
    const t = feedTimeline([entry(refused, 0), entry(hpa, 1)], NOW);
    expect(t.lanes.map((l) => [l.name, l.feature])).toEqual([
      ['php-apache', 'workload'],
      ['php-apache (HPA)', 'hpa'],
    ]);
  });

  it('tells two workloads of the same name apart by their namespace', () => {
    const a = { ...REFUSED, id: 7, at: at(10), target: { namespace: 'team-a', kind: 'Deployment', name: 'api' } };
    const b = { ...REFUSED, id: 8, at: at(20), target: { namespace: 'team-b', kind: 'Deployment', name: 'api' } };
    const t = feedTimeline([entry(a, 0), entry(b, 1)], NOW);
    expect(t.lanes.map((l) => l.name)).toEqual(['team-a/api', 'team-b/api']);
  });

  it('keeps a lone HPA lane under its plain name', () => {
    const t = feedTimeline([entry({ ...HPA_CHANGE, at: at(5) }, 0)], NOW);
    expect(t.lanes.map((l) => l.name)).toEqual(['php-apache']);
  });
});

describe('savingNow: what the changes in place free', () => {
  // idle-batch-listener's live change, which endpoint 1 reports as its lastAction.
  const workloads = adaptEngineResponse(endpointOne()).workloads;
  const idle = workloads.find((w) => w.ref.name === 'idle-batch-listener')!;
  const pod = (name: string, over: Partial<ActivityPodProofWire>): ActivityPodProofWire => ({
    pod: name, node: null, changed: true, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: null, ...over,
  });
  /** The change as the activity log holds it: 100m → 25m per pod, with these pods' proof. */
  const change = (pods: ActivityPodProofWire[], outcome = 'APPLIED'): ActivityEntryWire => ({
    ...APPLIED, id: 9, at: idle.lastAction!.at, outcome,
    target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
    change: { containers: [{ container: 'sleeper', before: { cpuRequestMillis: 100, cpuLimitMillis: null, memRequestBytes: 128 * 1024 * 1024, memLimitBytes: null }, beforeBasis: 'OBSERVED', after: { cpuRequestMillis: 25, cpuLimitMillis: null, memRequestBytes: 64 * 1024 * 1024, memLimitBytes: null }, requested: null }], hpa: null, mode: null },
    proof: { restartFree: false, reverted: pods.some((p) => p.revert) ? true : null, pods },
  });

  it('counts every pod that runs the new size', () => {
    const s = savingNow([idle], [entry(change([pod('a', {}), pod('b', {})]), 0)]);
    expect(s.counted).toHaveLength(1);
    expect([s.cpuM, s.memMi, s.counted[0].pods]).toEqual([150, 128, 2]);
  });

  it('leaves out a pod a partial apply put back, which runs its old size', () => {
    const partly = change([
      pod('a', {}),
      pod('b', { changed: false, revert: { state: 'REVERTED', detail: 'XamOps put the pod back.' } }),
      pod('c', { changed: false }),
    ], 'PARTIALLY_APPLIED');
    const s = savingNow([idle], [entry(partly, 0)]);
    expect(s.counted).toHaveLength(1);
    expect(s.counted[0].pods).toBe(1);
    expect([s.cpuM, s.memMi]).toEqual([75, 64]);
  });

  it("doesn't count an amount when its pods don't say whether they changed", () => {
    const unknown = change([pod('a', { changed: null }), pod('b', {})]);
    const s = savingNow([idle], [entry(unknown, 0)]);
    expect(s.inPlace).toBe(1);
    expect(s.counted).toHaveLength(0);
    expect(s.notCounted).toBe(1);
  });
});
