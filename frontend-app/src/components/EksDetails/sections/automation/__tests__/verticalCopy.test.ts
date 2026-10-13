import { describe, expect, it } from 'vitest';
import { toVerticalEngineView } from '../../../../../api/verticalRecommendationsAdapter';
import { demoVerticalStatus } from '../../../../../demo/k8sAutomationMockData';
import type { Resources, VerticalWorkload } from '../../../../../types/verticalRecommendations';
import { bucketCounts, workloadBucket } from '../verticalCopy';

const res = (cpu: number | null, mem: number | null): Resources => ({ cpuRequestM: cpu, memRequestMi: mem, cpuLimitM: null, memLimitMi: null });
const base = toVerticalEngineView(demoVerticalStatus('prod-eks-cluster')!).workloads.find((w) => w.ref.name === 'grafana')!;

/** A workload with one container, built from the demo's grafana entry. */
function workload(over: { skipReason?: unknown; current?: Resources; recommended?: Resources | null; reasons?: string[] }): VerticalWorkload {
  return {
    ...base,
    skipReason: (over.skipReason ?? null) as VerticalWorkload['skipReason'],
    containers: [{
      ...base.containers[0],
      current: over.current ?? res(100, 256),
      recommended: over.recommended === undefined ? null : over.recommended,
      reasons: (over.reasons ?? []) as VerticalWorkload['containers'][number]['reasons'],
    }],
  };
}

describe('workload buckets (glossary §2), first matching rule wins', () => {
  it.each([
    ['POLICY_OFF is Skipped, before anything else', workload({ skipReason: 'POLICY_OFF' }), true, 'Skipped'],
    ['a workload no node can fit is Not running', workload({ recommended: res(400, 256) }), true, 'Not running'],
    ['a VPA that changes pods leaves it alone, even as a code this UI does not know yet', workload({ skipReason: { kind: 'unknown', raw: 'VPA_MANAGED' } }), false, 'Left alone'],
    ['too little history is Not enough data', workload({ skipReason: 'INSUFFICIENT_HISTORY' }), false, 'Not enough data'],
    ['any other skip reason is Skipped', workload({ skipReason: 'LOCKED' }), false, 'Skipped'],
    ['suggestions only for unset requests leave it alone', workload({ current: res(null, null), recommended: res(60, 250), reasons: ['REQUESTS_UNSET'] }), false, 'Left alone'],
    ['a request going up needs more resources, even when another goes down', workload({ recommended: res(400, 128) }), false, 'Needs more resources'],
    ['requests only going down is over-provisioned', workload({ recommended: res(25, 250) }), false, 'Over-provisioned'],
    ['no change during the cooldown is left alone', workload({ reasons: ['COOLDOWN'] }), false, 'Left alone'],
    ['no change because an HPA scales on CPU is left alone', workload({ reasons: ['HPA_CPU_MANAGED'] }), false, 'Left alone'],
    ['no change for any other reason is right-sized', workload({ reasons: ['WITHIN_HYSTERESIS'] }), false, 'Right-sized'],
  ])('%s', (_name, w, notRunning, bucket) => {
    expect(workloadBucket(w, notRunning)).toBe(bucket);
  });

  it("counts the demo's nine workloads into the non-zero buckets, in the glossary's order", () => {
    const view = toVerticalEngineView(demoVerticalStatus('prod-eks-cluster')!);
    expect(bucketCounts(view.workloads)).toEqual([
      { bucket: 'Over-provisioned', count: 2 },
      { bucket: 'Needs more resources', count: 3 },
      { bucket: 'Right-sized', count: 1 },
      { bucket: 'Left alone', count: 1 },
      { bucket: 'Skipped', count: 2 },
    ]);
  });
});
