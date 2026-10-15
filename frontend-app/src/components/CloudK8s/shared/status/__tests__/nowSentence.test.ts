import { describe, expect, it } from 'vitest';
import {
  automationOverviewSentence,
  workloadsSentence,
  workloadDetailSentence,
  policiesSentence,
  nowSentence,
  type AutomationOverviewInputs,
  type WorkloadsInputs,
} from '../nowSentence';

describe('nowSentence: Automation › Overview', () => {
  it('leaves out the ready clause when nothing is ready, and shows the Apply guard', () => {
    const inputs: AutomationOverviewInputs = { cluster: 'xamops-test-1', watching: 12 };
    const { clauses, chips } = automationOverviewSentence(inputs);
    expect(clauses[0]).toBe('XamOps is watching 12 workloads on xamops-test-1.');
    expect(clauses).toContain('Nothing changes until you click Apply.');
    expect(clauses.join(' ')).not.toContain('can be resized');
    expect(chips).toEqual([]);
  });

  it('adds the ready and need-more clauses with their chips when present', () => {
    const { clauses, chips } = automationOverviewSentence({
      cluster: 'c', watching: 12, ready: 3, readyMoney: '$42/mo', needMore: 1, needMoreWaitsForRoom: true,
    });
    expect(clauses).toContain('3 can be resized now, saving $42/mo.');
    expect(clauses).toContain('1 needs more CPU, which waits for room on its node.');
    expect(chips.map((c) => c.label)).toContain('3 can be resized, $42/mo');
    expect(chips.map((c) => c.label)).toContain('1 need more CPU');
  });

  it('replaces the Apply guard with the auto-apply clause', () => {
    const { clauses } = automationOverviewSentence({ cluster: 'c', watching: 5, autoApplyCount: 2 });
    expect(clauses).toContain('XamOps applies suggestions on its own for 2 workloads, one at a time.');
    expect(clauses).not.toContain('Nothing changes until you click Apply.');
  });

  it('says nothing is watched, with the reason, when watching is 0', () => {
    const { clauses } = automationOverviewSentence({ cluster: 'c', watching: 0, noneReason: 'the engine is off' });
    expect(clauses).toEqual(["XamOps isn't watching any workloads on c: the engine is off."]);
  });

  it("reads a failure clause and shows no chips when endpoint 1 failed", () => {
    const { clauses, chips } = automationOverviewSentence({ cluster: 'c' });
    expect(clauses).toEqual(["XamOps couldn't read its workloads just now."]);
    expect(chips).toEqual([]);
  });

  it("omits the last-change clause and chip when activity failed", () => {
    const { clauses, chips } = automationOverviewSentence({ cluster: 'c', watching: 2, failed: ['activity'] });
    expect(clauses).toContain("XamOps couldn't read recent activity just now.");
    expect(chips).toEqual([]);
  });

  // Today's first real apply: idle-batch-listener was applied, no restart.
  it("renders today's idle-batch-listener apply as a last-change clause with a good chip", () => {
    const { clauses, chips } = automationOverviewSentence({
      cluster: 'xamops-test-1', watching: 12, ready: 0,
      lastChange: {
        time: '10:43 AM IST', workload: 'idle-batch-listener', resource: 'CPU request',
        from: '250m', to: '60m', pods: 3, restartDetected: false,
      },
    });
    expect(clauses).toContain('Last change: 10:43 AM IST, idle-batch-listener CPU request 250m to 60m on 3 pods, no restart.');
    expect(chips).toEqual([{ tone: 'good', icon: 'mdi:check-circle', label: 'idle-batch-listener: applied' }]);
  });

  it('caps the chips at 5', () => {
    const { chips } = automationOverviewSentence({
      cluster: 'c', watching: 12, ready: 3, readyMoney: '$1/mo', needMore: 2, autoApplyCount: 1,
      lastChange: { time: 't', workload: 'w', resource: 'CPU', from: 'a', to: 'b', pods: 1, restartDetected: false },
    });
    expect(chips.length).toBeLessThanOrEqual(5);
  });
});

describe('nowSentence: Workload autoscaler › Workloads', () => {
  it('builds the full sentence with ready names and the Apply guard', () => {
    const inputs: WorkloadsInputs = {
      analysed: 12, analysedAt: '10:40 AM IST', ready: 2, readyNames: ['a', 'b'], readyMoney: '$30/mo',
    };
    const { clauses } = workloadsSentence(inputs);
    expect(clauses[0]).toBe('12 workloads analysed at 10:40 AM IST.');
    expect(clauses).toContain('2 are over-provisioned and can be resized now: a, b, saving $30/mo.');
    expect(clauses).toContain('Nothing changes until you click Apply.');
  });

  // Today's second real apply: throttled-encoder was deferred — its node had no room.
  it('adds the apply-first clause for the deferred throttled-encoder', () => {
    const { clauses, chips } = workloadsSentence({
      analysed: 12, ready: 0,
      needsRoom: { workload: 'throttled-encoder', freeingWorkload: 'overprovisioned-api', freed: '1.4 cores' },
    });
    expect(clauses).toContain('throttled-encoder needs more CPU, but its node has no free CPU: apply overprovisioned-api first, which frees 1.4 cores there.');
    expect(chips.map((c) => c.label)).toContain('throttled-encoder: needs room');
  });

  it('adds the resized clause with a good chip', () => {
    const { clauses, chips } = workloadsSentence({
      analysed: 12, resized: { workload: 'idle-batch-listener', time: '10:43 AM IST', money: '$12/mo' },
    });
    expect(clauses).toContain('idle-batch-listener was resized at 10:43 AM IST and saves $12/mo.');
    expect(chips.map((c) => c.label)).toContain('idle-batch-listener: applied');
  });

  it('reads a failure clause when endpoint 1 failed', () => {
    expect(workloadsSentence({}).clauses).toEqual(["XamOps couldn't read its workloads just now."]);
  });
});

describe('nowSentence dispatcher', () => {
  it('routes by page', () => {
    expect(nowSentence('automation-overview', { cluster: 'c', watching: 1 }).clauses[0]).toContain('watching 1');
    expect(nowSentence('workloads', { analysed: 1 }).clauses[0]).toContain('1 workloads analysed');
    expect(nowSentence('workload-detail', { workload: 'w', status: { label: 'Right-sized' } }).clauses[0]).toBe('w: Right-sized.');
    expect(nowSentence('policies', { workloads: 0 }).clauses[0]).toBe('There are no workloads for these rules to apply to yet.');
  });
});

describe('nowSentence: freed capacity when there is no price (UI v3 plan §1)', () => {
  it('says what the ready ones free on Overview, and never a dollar figure', () => {
    const { clauses, chips } = automationOverviewSentence({ cluster: 'c', watching: 9, ready: 2, readyFreed: '0.98 vCPU and 750Mi' });
    expect(clauses).toContain('2 can be resized now, freeing 0.98 vCPU and 750Mi.');
    expect(chips.map((c) => c.label)).toContain('2 can be resized');
    expect(clauses.join(' ')).not.toMatch(/\$/);
  });

  it('names the ready ones on Workloads, and a resize without a price', () => {
    const { clauses } = workloadsSentence({
      analysed: 9, ready: 1, readyNames: ['overprovisioned-api'], readyFreed: '1.95 vCPU',
      resized: { workload: 'idle-batch-listener', time: '4:13 PM IST' },
    });
    expect(clauses).toContain('1 is over-provisioned and can be resized now: overprovisioned-api, freeing 1.95 vCPU.');
    expect(clauses).toContain('idle-batch-listener was resized at 4:13 PM IST.');
  });

  it("adds the latest recorded change when the full change isn't known", () => {
    const { clauses } = automationOverviewSentence({
      cluster: 'c', watching: 2, latestRecorded: { time: '6:41 PM IST', workload: 'overprovisioned-api', result: 'applied' },
    });
    expect(clauses).toContain('Last change: 6:41 PM IST, overprovisioned-api: applied.');
  });
});

describe('nowSentence: workload detail (glossary §3)', () => {
  // Today's idle-batch-listener success.
  it('Applied: the move, the pods, no restart, and the wait', () => {
    const { clauses, chips } = workloadDetailSentence({
      workload: 'idle-batch-listener',
      applied: {
        time: '4:13 PM IST', change: { resource: 'CPU request', from: '100m', to: '25m' }, limit: { from: '200m', to: '50m' },
        pods: 3, restartFree: true, next: '5:13 PM IST',
      },
    });
    expect(clauses).toEqual([
      'Resized 4:13 PM IST: CPU request 100m → 25m and limit 200m → 50m on all 3 pods, in place, with no restart.',
      'XamOps waits until 5:13 PM IST before it suggests another change.',
    ]);
    expect(chips.map((c) => c.label)).toEqual(['idle-batch-listener: applied', 'Watching until 5:13 PM IST']);
  });

  it('Applied with a restart reads restart detected, with a bad chip', () => {
    const { clauses, chips } = workloadDetailSentence({ workload: 'w', applied: { restartFree: false } });
    expect(clauses[0]).toBe('Resized, restart detected.');
    expect(chips[0]).toMatchObject({ tone: 'bad', label: 'w: restart detected' });
  });

  // Today's throttled-encoder deferral, put back by XamOps (fix S7).
  it('Deferred: the suggestion, the deferral and the put-back', () => {
    const put = workloadDetailSentence({ workload: 'throttled-encoder', deferred: { suggests: '63m', limit: '200m', putBack: true } });
    expect(put.clauses).toEqual([
      'throttled-encoder: XamOps suggests 63m (limit 200m). Kubernetes deferred the change: its node had no free room.',
      'XamOps put the pod back. Nothing changed.',
    ]);
    const notBack = workloadDetailSentence({ workload: 'throttled-encoder', deferred: { putBack: false } });
    expect(notBack.clauses).toContain('The new size may still be applied later.');
    expect(notBack.clauses.join(' ')).not.toContain('Nothing changed');
  });

  it('Ready: from → to per pod, what it frees, and the Apply guard', () => {
    const { clauses, chips } = workloadDetailSentence({
      workload: 'overprovisioned-api', ready: { from: '1000m', to: '25m', direction: 'freeing', amount: '1.95 vCPU and 1.5 GiB', pods: 2 },
    });
    expect(clauses).toEqual([
      'XamOps suggests 1000m → 25m per pod, freeing 1.95 vCPU and 1.5 GiB across 2 pods.',
      'Nothing changes until you click Apply.',
    ]);
    expect(chips[0]).toMatchObject({ tone: 'warn', label: 'Suggestion ready' });
  });

  it('Not enough data: what it has of the window, and only what answered', () => {
    expect(workloadDetailSentence({ workload: 'w', measuring: { covered: '9 hours', window: '24 hours' } }).clauses[0])
      .toBe('XamOps is still measuring w: it has 9 hours of the 24 hours it needs.');
    expect(workloadDetailSentence({ workload: 'w', measuring: {} }).clauses[0]).toBe('XamOps is still measuring w.');
  });

  it('adds the latest recorded change, and the wait while it runs', () => {
    const { clauses } = workloadDetailSentence({
      workload: 'w', status: { label: 'Left alone: changed recently' }, lastChange: { time: '4:13 PM IST', result: 'applied', next: '5:13 PM IST' },
    });
    expect(clauses).toContain('Last change XamOps recorded: 4:13 PM IST, applied.');
    expect(clauses).toContain('XamOps waits until 5:13 PM IST before it suggests another change.');
  });

  it("reads a failure clause when the workload couldn't be read", () => {
    expect(workloadDetailSentence({}).clauses).toEqual(["XamOps couldn't read this workload just now."]);
  });
});

describe('nowSentence: Policies', () => {
  it('says what most workloads use, the saved and automatic ones, and what is left alone', () => {
    const { clauses, chips } = policiesSentence({
      workloads: 9, commonMode: { mode: 'Suggest only', count: 7 }, ownRules: 2, automatic: 1, leftAlone: 3,
    });
    expect(clauses).toEqual([
      '7 of 9 workloads use Suggest only.',
      '2 have their own saved rules.',
      '1 is set to Apply automatically.',
      '3 are left alone by a rule below.',
    ]);
    expect(chips.map((c) => c.label)).toEqual(['Apply automatically: 1', 'Left alone: 3']);
  });

  it('keeps the Apply guard when nothing is automatic, and fails honestly', () => {
    expect(policiesSentence({ workloads: 2, commonMode: { mode: 'Suggest only', count: 2 }, automatic: 0 }).clauses).toEqual([
      'All 2 workloads use Suggest only.',
      'Nothing changes until someone clicks Apply.',
    ]);
    expect(policiesSentence({}).clauses).toEqual(["XamOps couldn't read its rules just now."]);
  });
});
