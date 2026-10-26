import { describe, expect, it } from 'vitest';
import type {
  ConsolidationPlanResponse,
  ConsolidationRealizedSavings,
  ConsolidationRun,
  ConsolidationRunResponse,
  ConsolidationSavings,
} from '../../../../../../types/k8sAutomation';
import {
  NO_RUNS_ANALYSIS_NOTE,
  NONE_ACTOR_NOTE,
  actorText,
  blockerSummary,
  freshnessText,
  metricCells,
  modeText,
  nodeCostText,
  pctText,
  realizedNodeCostText,
  realizedSavingsText,
  removalPathText,
  requestedText,
  runLines,
  runStatusTone,
  runTone,
  savingsText,
  shortNode,
} from '../consolidationDerivations';

const DASH = '—';
const node = (name: string, cpu = 20, mem = 30) => ({
  name, nodeGroup: 'ng-ondemand-1', instanceType: 't3.medium', capacityType: 'ON_DEMAND', zone: 'ap-south-1a', fargate: false,
  cpuRequestedPct: cpu, memRequestedPct: mem,
});
const savings = (over: Partial<ConsolidationSavings> = {}): ConsolidationSavings => ({
  projectedMonthlySavings: 60.74, pricedMonthlySubtotal: 60.74, pricedNodes: 2, unpricedNodes: 0,
  nodes: [
    { nodeName: 'a', instanceType: 't3.medium', capacityType: 'on_demand', capacityTypeInferred: false, monthlyCost: 30.37, source: 'PRICE_LIST', reason: null, note: '' },
    { nodeName: 'b', instanceType: 't3.medium', capacityType: 'on_demand', capacityTypeInferred: false, monthlyCost: 30.37, source: 'PRICE_LIST', reason: null, note: '' },
  ],
  ...over,
});
const response = (over: Partial<ConsolidationPlanResponse> = {}): ConsolidationPlanResponse => ({
  freshness: 'CURRENT',
  generatedAt: '2026-10-24T00:00:00Z',
  ageSeconds: 240,
  plan: {
    schemaVersion: 1, generatedAt: '2026-10-24T00:00:00Z', mode: 'analysis',
    actor: { kind: 'NONE', evidence: [], note: 'n' },
    settings: { underutilizedThresholdPct: 50, headroomPct: 15, maxNodesPerPlan: 100 },
    summary: { nodesEvaluated: 4, empty: 1, drain: 1, blocked: 1, kept: 1 },
    moves: [
      { type: 'EMPTY', node: node('a'), pods: [] },
      { type: 'DRAIN', node: node('b'), pods: [{ pod: 'team-a/web-1', owner: 'ReplicaSet/web', to: 'c', cpuMillis: 250, memBytes: 1 }] },
    ],
    blocked: [],
    kept: [],
  },
  savings: savings(),
  ...over,
});

describe('consolidationDerivations', () => {
  it('formats node names, percentages and requests, with a dash for unknown', () => {
    expect(shortNode('ip-172-31-26-143.ap-south-1.compute.internal')).toBe('ip-172-31-26-143');
    expect(pctText(35)).toBe('35%');
    expect(pctText(15.6)).toBe('15.6%');
    expect(pctText(null)).toBe(DASH);
    expect(requestedText({ cpuRequestedPct: 35, memRequestedPct: 15.6 })).toBe('CPU 35% · memory 15.6%');
  });

  it('builds the metric strip from the plan and the cost engine', () => {
    const cells = metricCells(response());
    expect(cells.map((c) => [c.label, c.value])).toEqual([
      ['Nodes to remove', 2],
      ['Empty', 1],
      ['Drain', 1],
      ['Blocked', 1],
      ['Projected savings / month', '$60.74'],
    ]);
    expect(cells[0].sub).toBe('of 4 nodes evaluated');
    expect(cells[2].sub).toBe('1 pod moves to other nodes');
    expect(cells[4].sub).toBe('2 of 2 nodes priced');
  });

  it('dashes every figure without a plan, and the savings of a stale one', () => {
    expect(metricCells(null).map((c) => c.value)).toEqual([DASH, DASH, DASH, DASH, DASH]);
    expect(metricCells(response({ freshness: 'NO_PLAN', plan: null, savings: null })).map((c) => c.value)).toEqual([DASH, DASH, DASH, DASH, DASH]);
    const stale = metricCells(response({ freshness: 'STALE', savings: null }));
    expect(stale[0].value).toBe(2);
    expect(stale[4]).toMatchObject({ value: DASH, sub: 'Not priced: the plan is out of date' });
  });

  it('prices savings only when every node is priced', () => {
    expect(savingsText(savings())).toEqual({ value: '$60.74', priced: '2 of 2 nodes priced', reason: null, subtotal: null });
    expect(savingsText(savings({ projectedMonthlySavings: 0, pricedMonthlySubtotal: null, pricedNodes: 0, nodes: [] }))).toMatchObject({ value: '$0.00', priced: null });
    const partial = savingsText(savings({
      projectedMonthlySavings: null, pricedMonthlySubtotal: 30.37, pricedNodes: 1, unpricedNodes: 1,
      nodes: [savings().nodes[0], { ...savings().nodes[1], monthlyCost: null, source: null, reason: 'NO_LIVE_PRICE' }],
    }));
    expect(partial).toEqual({
      value: DASH,
      priced: '1 of 2 nodes priced',
      reason: 'No live AWS price for this instance type in this region.',
      subtotal: 'Priced subtotal $30.37',
    });
    expect(savingsText(null).value).toBe(DASH);
  });

  it("names each removed node's cost, or why it is unpriced", () => {
    expect(nodeCostText(response(), 'a')).toEqual({ value: '$30.37', reason: null });
    const r = response({ savings: savings({ nodes: [{ ...savings().nodes[0], monthlyCost: null, reason: 'PRICING_DISABLED' }] }) });
    expect(nodeCostText(r, 'a')).toEqual({ value: DASH, reason: 'Live Kubernetes cost pricing is not enabled for this cluster.' });
    expect(nodeCostText(response({ freshness: 'STALE', savings: null }), 'a')).toEqual({ value: DASH, reason: 'Not priced while the plan is out of date.' });
  });

  it('names who removes nodes', () => {
    expect(actorText({ kind: 'KARPENTER', evidence: [], note: 'Karpenter was found on this cluster. Display only.' })).toEqual({
      label: 'Karpenter', detail: 'Karpenter was found on this cluster. Display only.',
    });
    expect(actorText({ kind: 'CLUSTER_AUTOSCALER', evidence: [], note: '' }).label).toBe('Cluster Autoscaler');
    expect(actorText({ kind: 'MULTIPLE', evidence: [], note: '' }).label).toBe('Karpenter and the Cluster Autoscaler');
    const none = actorText({ kind: 'NONE', evidence: [], note: 'n' });
    expect(none.label).toBe('Neither');
    expect(none.detail).toContain(NONE_ACTOR_NOTE);
  });

  it('summarises blockers: the first reason, +N more, the rest and the blocking pods', () => {
    const b = blockerSummary([
      { pod: 'monitoring/prometheus-server', code: 'critical-namespace', reason: 'critical namespace' },
      { pod: 'monitoring/prometheus-server', code: 'local-storage-no-replica', reason: 'local storage, no redundant replica' },
      { pod: 'team-b/load-generator', code: 'single-replica', reason: 'single replica' },
    ]);
    expect(b).toEqual({
      label: 'critical namespace',
      summary: 'critical namespace +2 more',
      detail: 'local storage, no redundant replica (monitoring/prometheus-server); single replica (team-b/load-generator)',
      pods: ['monitoring/prometheus-server', 'team-b/load-generator'],
    });
    expect(blockerSummary([{ pod: '', code: 'node-cordoned', reason: 'node is cordoned' }])).toEqual({
      label: 'node is cordoned', summary: 'node is cordoned', detail: null, pods: [],
    });
  });

  it('says how old the plan is', () => {
    expect(freshnessText(response())).toBe('This plan is 4 min old.');
    expect(freshnessText(response({ freshness: 'STALE', ageSeconds: 660, savings: null }))).toBe(
      "This plan is 11 min old: the automation agent hasn't reported a newer one, so it isn't priced.",
    );
    expect(freshnessText(response({ freshness: 'STALE', ageSeconds: 3 * 3600, savings: null }))).toContain('3 h old');
    expect(freshnessText(response({ freshness: 'STALE', ageSeconds: null, savings: null }))).toContain("time can't be read");
    expect(freshnessText(response({ freshness: 'NO_PLAN', plan: null, savings: null }))).toBeNull();
  });

  const run = (over: Partial<ConsolidationRun> = {}): ConsolidationRun => ({
    schemaVersion: 1, runId: 'consolidation-1793000000', generatedAt: '2026-10-25T00:05:00Z',
    mode: 'live', dryRun: false, actor: { kind: 'NONE', evidence: [], note: '' },
    summary: { considered: 2, removed: 1, deferred: 1, simulated: 0, skipped: 0, refused: 0, aborted: 0, podsEvicted: 7 },
    nodes: [
      { node: node('a'), moveType: 'DRAIN', status: 'removed', decision: 'xamops-asg', reason: 'terminated', podsEvicted: 7 },
      { node: node('b'), moveType: 'EMPTY', status: 'deferred', decision: 'defer-to-actor', reason: 'left to actor', podsEvicted: 0 },
    ],
    ...over,
  });
  const realized = (over: Partial<ConsolidationRealizedSavings> = {}): ConsolidationRealizedSavings => ({
    realizedMonthlySavings: 30.37, pricedMonthlySubtotal: 30.37, pricedNodes: 1, unpricedNodes: 0,
    nodes: [{ nodeName: 'a', instanceType: 't3.medium', capacityType: 'on_demand', capacityTypeInferred: false, monthlyCost: 30.37, source: 'PRICE_LIST', reason: null, note: '' }],
    ...over,
  });

  it('maps each node outcome to a tone and a word', () => {
    expect(runStatusTone('removed')).toEqual({ tone: 'good', label: 'Removed' });
    expect(runStatusTone('deferred')).toEqual({ tone: 'info', label: 'Deferred' });
    expect(runStatusTone('simulated')).toEqual({ tone: 'info', label: 'Simulated' });
    expect(runStatusTone('skipped')).toEqual({ tone: 'warn', label: 'Skipped' });
    expect(runStatusTone('aborted')).toEqual({ tone: 'warn', label: 'Aborted' });
    expect(runStatusTone('refused')).toEqual({ tone: 'bad', label: 'Refused' });
  });

  it("reads a run's headline tone, mode and removal path", () => {
    expect(runTone(run())).toEqual({ tone: 'good', label: 'Removed' });
    expect(runTone(run({ mode: 'simulate', dryRun: true }))).toEqual({ tone: 'info', label: 'Simulated' });
    expect(runTone(run({ summary: { ...run().summary, aborted: 1 } }))).toEqual({ tone: 'warn', label: 'Aborted' });
    expect(runTone(run({ summary: { considered: 1, removed: 0, deferred: 0, simulated: 0, skipped: 0, refused: 1, aborted: 0, podsEvicted: 0 } }))).toEqual({ tone: 'bad', label: 'Refused' });
    expect(modeText(run())).toBe('Live');
    expect(modeText(run({ dryRun: true }))).toBe('Simulate (dry run)');
    expect(modeText(run({ mode: 'simulate', dryRun: true }))).toBe('Simulate (dry run)');
    expect(removalPathText(run().nodes[0])).toBe('XamOps ASG terminate');
    expect(removalPathText(run().nodes[1])).toBe('Left to the cluster actor');
    expect(removalPathText({ ...run().nodes[0], decision: 'refuse' })).toBe('Refused');
    expect(removalPathText({ ...run().nodes[0], decision: '' })).toBe(DASH);
  });

  it('prices realized savings only when every removed node is priced', () => {
    expect(realizedSavingsText(realized())).toEqual({ value: '$30.37', priced: '1 of 1 node priced', reason: null, subtotal: null });
    const partial = realizedSavingsText(realized({
      realizedMonthlySavings: null, pricedMonthlySubtotal: 30.37, pricedNodes: 1, unpricedNodes: 1,
      nodes: [realized().nodes[0], { nodeName: 'b', instanceType: 't3.medium', capacityType: 'spot', capacityTypeInferred: false, monthlyCost: null, source: null, reason: 'NO_LIVE_PRICE', note: '' }],
    }));
    expect(partial).toEqual({
      value: DASH,
      priced: '1 of 2 nodes priced',
      reason: 'No live AWS price for this instance type in this region.',
      subtotal: 'Priced subtotal $30.37',
    });
    expect(realizedSavingsText(null).value).toBe(DASH);
    expect(realizedNodeCostText(realized(), 'a')).toEqual({ value: '$30.37', reason: null });
    expect(realizedNodeCostText(realized({ nodes: [{ ...realized().nodes[0], monthlyCost: null, reason: 'PRICING_DISABLED' }] }), 'a'))
      .toEqual({ value: DASH, reason: 'Live Kubernetes cost pricing is not enabled for this cluster.' });
    expect(realizedNodeCostText(null, 'a')).toEqual({ value: DASH, reason: null });
  });

  it('builds the recent-runs rows newest-first, and nothing without an OK read', () => {
    const okResponse: ConsolidationRunResponse = { status: 'OK', runs: [run()], realizedSavings: realized() };
    const lines = runLines(okResponse);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ tone: 'good', statusLabel: 'Removed', mode: 'Live', nodesRemoved: 1, podsEvicted: 7 });
    expect(runLines({ status: 'NO_RUNS', runs: [], realizedSavings: null })).toEqual([]);
    expect(runLines({ status: 'UNREADABLE', runs: [], realizedSavings: null })).toEqual([]);
    expect(runLines(null)).toEqual([]);
    expect(NO_RUNS_ANALYSIS_NOTE).toContain('look-only');
  });
});
