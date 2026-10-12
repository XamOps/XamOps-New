import { describe, expect, it } from 'vitest';
import {
  automationOverviewSentence,
  workloadsSentence,
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
  });
});
