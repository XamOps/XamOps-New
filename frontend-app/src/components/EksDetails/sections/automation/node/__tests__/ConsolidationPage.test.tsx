import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getConsolidationPlan: vi.fn(),
  setAutomationEnabled: vi.fn(),
  setAutomationScope: vi.fn(),
  saveEvictorPolicy: vi.fn(),
  saveNodeOptimizerPolicy: vi.fn(),
  saveRebalancerConfig: vi.fn(),
  saveSafetyGuards: vi.fn(),
  saveScaleOutPolicy: vi.fn(),
  saveNodeTemplate: vi.fn(),
  deleteNodeTemplate: vi.fn(),
  triggerRebalance: vi.fn(),
}));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, ...api };
});

import { K8sApiError } from '../../../../../../api/k8sAutomationApi';
import type { ConsolidationPlanResponse, ConsolidationSavings } from '../../../../../../types/k8sAutomation';
import ConsolidationPage from '../ConsolidationPage';

const DASH = '—';
const CLUSTER = 'xamops-test-1';
const FQDN = '.ap-south-1.compute.internal';
/** xamops-test-1 in miniature: on-demand t3.medium nodes in the managed node group ng-ondemand-1. */
const node = (name: string, cpu: number, mem: number) => ({
  name: `${name}${FQDN}`, nodeGroup: 'ng-ondemand-1', instanceType: 't3.medium', capacityType: 'ON_DEMAND', zone: 'ap-south-1a',
  fargate: false, cpuRequestedPct: cpu, memRequestedPct: mem,
});
const NONE_NOTE =
  'Neither Karpenter nor the Cluster Autoscaler was found. Engine v1 will remove nodes with an ASG terminate that decrements desired capacity, never below the node group minimum. Display only.';
const priced = (name: string, monthlyCost: number | null, reason: string | null = null) => ({
  nodeName: `${name}${FQDN}`, instanceType: 't3.medium', capacityType: 'on_demand', capacityTypeInferred: false,
  monthlyCost, source: monthlyCost === null ? null : 'PRICE_LIST', reason, note: 'AWS Price List',
});
const SAVINGS: ConsolidationSavings = {
  projectedMonthlySavings: 60.74, pricedMonthlySubtotal: 60.74, pricedNodes: 2, unpricedNodes: 0,
  nodes: [priced('ip-172-31-9-125', 30.37), priced('ip-172-31-43-198', 30.37)],
};

function current(over: Partial<ConsolidationPlanResponse> = {}): ConsolidationPlanResponse {
  return {
    freshness: 'CURRENT',
    generatedAt: '2026-10-24T06:00:00Z',
    ageSeconds: 120,
    plan: {
      schemaVersion: 1,
      generatedAt: '2026-10-24T06:00:00Z',
      mode: 'analysis',
      actor: { kind: 'NONE', evidence: [], note: NONE_NOTE },
      settings: { underutilizedThresholdPct: 50, headroomPct: 15, maxNodesPerPlan: 100 },
      summary: { nodesEvaluated: 4, empty: 1, drain: 1, blocked: 1, kept: 1 },
      moves: [
        { type: 'EMPTY', node: node('ip-172-31-9-125', 0, 0), pods: [] },
        {
          type: 'DRAIN',
          node: node('ip-172-31-43-198', 26, 16.5),
          pods: [
            { pod: 'prod-sim/enc-1', owner: 'ReplicaSet/enc-7d9', to: `ip-172-31-26-143${FQDN}`, cpuMillis: 500, memBytes: 536870912 },
            { pod: 'team-a/api-1', owner: 'ReplicaSet/api-5c4', to: `ip-172-31-5-10${FQDN}`, cpuMillis: 250, memBytes: 268435456 },
          ],
        },
      ],
      blocked: [
        {
          node: node('ip-172-31-26-143', 45.1, 40),
          blockers: [
            { pod: 'monitoring/prometheus-server', code: 'critical-namespace', reason: 'critical namespace' },
            { pod: 'monitoring/prometheus-server', code: 'local-storage-no-replica', reason: 'local storage, no redundant replica' },
            { pod: 'team-b/load-generator', code: 'single-replica', reason: 'single replica' },
          ],
        },
      ],
      kept: [{ node: node('ip-172-31-5-10', 82.4, 60.2), reason: 'requests above 50% of allocatable (CPU 82.4%, memory 60.2%)' }],
    },
    savings: SAVINGS,
    ...over,
  };
}

async function page() {
  const view = render(<ConsolidationPage clusterName={CLUSTER} accountId="102227938259" region="ap-south-1" />);
  await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
  return view;
}
const kpi = (label: string) => {
  const cell = [...document.querySelectorAll<HTMLElement>('[data-consolidation-kpis] [data-metric-cell]')].find(
    (c) => c.firstElementChild?.textContent === label,
  );
  if (!cell) throw new Error(`no KPI "${label}"`);
  return cell;
};
const row = (attr: string, name: string) => document.querySelector<HTMLElement>(`[${attr}="${name}${FQDN}"]`)!;

const realFetch = global.fetch;
beforeEach(() => {
  // Nothing but the mocked plan read may go out.
  global.fetch = vi.fn(async (input: RequestInfo | URL) => {
    throw new Error(`unexpected request ${String(input)}`);
  }) as typeof fetch;
});
afterEach(() => {
  global.fetch = realFetch;
  vi.clearAllMocks();
});

describe('Automation › Consolidation (look-only plan)', () => {
  it('shows the Empty and Drain rows with the pods that move and where', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    await page();
    const empty = row('data-move', 'ip-172-31-9-125');
    expect(within(empty).getByRole('rowheader')).toHaveTextContent('ip-172-31-9-125t3.medium · ON_DEMAND · ap-south-1a');
    expect(empty.querySelector('[data-tone="good"]')).toHaveTextContent('Empty');
    expect(empty).toHaveTextContent('ng-ondemand-1');
    expect(empty).toHaveTextContent('CPU 0% · memory 0%');
    const drain = row('data-move', 'ip-172-31-43-198');
    expect(drain.querySelector('[data-tone="good"]')).toHaveTextContent('Drain');
    expect(drain).toHaveTextContent('CPU 26% · memory 16.5%');
    expect([...drain.querySelectorAll('[data-pod-move]')].map((li) => li.textContent)).toEqual([
      'prod-sim/enc-1 → ip-172-31-26-143',
      'team-a/api-1 → ip-172-31-5-10',
    ]);
    expect(drain.querySelector('[data-move-cost]')).toHaveTextContent('$30.37');
    expect(screen.getByRole('table', { name: 'Plan preview: nodes XamOps could empty or drain' })).toBeInTheDocument();
  });

  it('lists blocked nodes with the reason and the blocking pods, and kept nodes with why', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    await page();
    const blocked = row('data-blocked', 'ip-172-31-26-143');
    const chip = blocked.querySelector('[data-tone="warn"]')!;
    expect(chip).toHaveTextContent('critical namespace');
    expect(chip).toHaveTextContent('local storage, no redundant replica (monitoring/prometheus-server); single replica (team-b/load-generator)');
    expect(blocked).toHaveTextContent('monitoring/prometheus-server');
    expect(blocked).toHaveTextContent('team-b/load-generator');
    expect(row('data-kept', 'ip-172-31-5-10')).toHaveTextContent('requests above 50% of allocatable (CPU 82.4%, memory 60.2%)');
  });

  it('says who removes nodes: neither, with the display-only note', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    await page();
    const actor = document.querySelector('[data-consolidation-actor="NONE"]')!;
    expect(actor.querySelector('[data-tone="info"]')).toHaveTextContent('Neither');
    expect(actor).toHaveTextContent(
      "v1 will remove nodes with an ASG terminate that lowers desired capacity, never below the node group's minimum — display only",
    );
  });

  it('shows the projected savings from the cost engine, and the settings and source lines', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    await page();
    expect(kpi('Nodes to remove')).toHaveTextContent('2of 4 nodes evaluated');
    expect(kpi('Empty')).toHaveTextContent('1');
    expect(kpi('Drain')).toHaveTextContent('2 pods move to other nodes');
    expect(kpi('Blocked')).toHaveTextContent('1');
    expect(kpi('Projected savings / month')).toHaveTextContent('$60.74');
    expect(kpi('Projected savings / month')).toHaveTextContent('2 of 2 nodes priced');
    expect(document.querySelector('[data-consolidation-settings]')).toHaveTextContent(
      'Underutilized below 50% of allocatable requests · keeps 15% headroom on receiving nodes',
    );
    expect(document.querySelector('[data-consolidation-source]')).toHaveTextContent(
      'GET /api/eks/autoscaling/{cluster}/consolidation-plan',
    );
  });

  it('dashes the savings when a node is unpriced, with how many are priced and why', async () => {
    api.getConsolidationPlan.mockResolvedValue(current({
      savings: {
        projectedMonthlySavings: null, pricedMonthlySubtotal: 30.37, pricedNodes: 1, unpricedNodes: 1,
        nodes: [priced('ip-172-31-9-125', 30.37), priced('ip-172-31-43-198', null, 'NO_LIVE_PRICE')],
      },
    }));
    await page();
    const savings = kpi('Projected savings / month');
    expect(savings).toHaveTextContent(DASH);
    expect(savings).not.toHaveTextContent('$60.74');
    expect(savings).toHaveTextContent('1 of 2 nodes priced');
    const cost = row('data-move', 'ip-172-31-43-198').querySelector('[data-move-cost]')!;
    expect(cost).toHaveTextContent(DASH);
    expect(cost).toHaveTextContent('No live AWS price for this instance type in this region.');
  });

  it('shows the empty state when the agent has pushed no plan', async () => {
    api.getConsolidationPlan.mockResolvedValue({ freshness: 'NO_PLAN', generatedAt: null, ageSeconds: null, plan: null, savings: null });
    await page();
    expect(screen.getByText('No consolidation plan yet')).toBeInTheDocument();
    expect(screen.getByText('The automation agent reports one every cycle while node automation is on (full or scoped).')).toBeInTheDocument();
    expect(kpi('Nodes to remove')).toHaveTextContent(DASH);
    expect(kpi('Projected savings / month')).toHaveTextContent(DASH);
    expect(document.querySelector('table')).toBeNull();
  });

  it('says there are no opportunities, and saves $0.00, when the plan has no moves', async () => {
    const base = current();
    api.getConsolidationPlan.mockResolvedValue(current({
      plan: { ...base.plan!, moves: [], summary: { ...base.plan!.summary, empty: 0, drain: 0 } },
      savings: { projectedMonthlySavings: 0, pricedMonthlySubtotal: null, pricedNodes: 0, unpricedNodes: 0, nodes: [] },
    }));
    await page();
    expect(document.querySelector('[data-no-moves]')).toHaveTextContent('No consolidation opportunities');
    expect(kpi('Projected savings / month')).toHaveTextContent('$0.00');
    expect(kpi('Nodes to remove')).toHaveTextContent('0');
  });

  it('marks a stale plan with a notice and dashes its savings', async () => {
    api.getConsolidationPlan.mockResolvedValue(current({ freshness: 'STALE', ageSeconds: 660, savings: null }));
    await page();
    expect(screen.getByRole('status')).toHaveTextContent(
      "This plan is 11 min old: the automation agent hasn't reported a newer one, so it isn't priced.",
    );
    expect(kpi('Projected savings / month')).toHaveTextContent(DASH);
    expect(row('data-move', 'ip-172-31-43-198').querySelector('[data-move-cost]')).toHaveTextContent(DASH);
    // The plan itself still shows.
    expect(row('data-move', 'ip-172-31-9-125')).toBeInTheDocument();
  });

  it('shows a read failure with Retry, which reads the plan again', async () => {
    const user = userEvent.setup();
    api.getConsolidationPlan
      .mockRejectedValueOnce(new K8sApiError({ status: 500 }))
      .mockResolvedValueOnce(current());
    await page();
    const failure = document.querySelector('[data-consolidation-failure]')!;
    expect(failure).toHaveTextContent("Couldn't read the consolidation plan: XamOps had a problem with this request. Try again.");
    await user.click(within(failure as HTMLElement).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(row('data-move', 'ip-172-31-9-125')).toBeInTheDocument());
    expect(api.getConsolidationPlan).toHaveBeenCalledTimes(2);
  });

  it('shows an unreadable plan as a read failure with Retry', async () => {
    api.getConsolidationPlan.mockResolvedValue({ freshness: 'UNREADABLE', generatedAt: null, ageSeconds: null, plan: null, savings: null });
    await page();
    expect(document.querySelector('[data-consolidation-failure]')).toHaveTextContent("the automation agent's last plan couldn't be read.");
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('only reads: one GET of the plan, no write API and no controls', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    const { container } = await page();
    expect(api.getConsolidationPlan).toHaveBeenCalledTimes(1);
    expect(api.getConsolidationPlan.mock.calls[0][0]).toEqual({ cluster: CLUSTER, accountId: '102227938259', region: 'ap-south-1' });
    for (const [name, fn] of Object.entries(api)) if (name !== 'getConsolidationPlan') expect(fn, name).not.toHaveBeenCalled();
    expect(global.fetch).not.toHaveBeenCalled();
    expect(container.querySelectorAll('button, input, select, textarea, [role="switch"], [role="checkbox"]')).toHaveLength(0);
  });

  it('marks its root data-k8s-kit, uses dot-and-word statuses and no pills', async () => {
    api.getConsolidationPlan.mockResolvedValue(current());
    const { container } = await page();
    const root = container.querySelector('[data-consolidation-page]')!;
    expect(root).toHaveAttribute('data-k8s-kit', '');
    // No pill in the body or on a status (the FeatureMark's thin accent rule is the only round shape).
    expect(container.querySelectorAll('table .rounded-full, [data-tone].rounded-full, [data-tone] .rounded-full')).toHaveLength(0);
    const statuses = container.querySelectorAll('[data-move-type] td:nth-child(3) > *, [data-blocked] td:nth-child(2) > *');
    expect(statuses.length).toBe(3);
    for (const s of statuses) expect(s).toHaveAttribute('data-tone');
    // De-boxed: no section sits inside another bordered card.
    expect(container.querySelectorAll('[data-consolidation-section] [data-consolidation-section]')).toHaveLength(0);
  });
});
