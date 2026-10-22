import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  VerticalCostSummary,
  VerticalWorkload,
} from '../../../../types/eksTypes';

// The tab reads all money from useEksClusterCost; mock it so the render is deterministic and we can
// drive the honest-empty / unpriced shapes the engine produces. These assertions would FAIL against
// the old $0 code (which fabricated dailyCost × 30 and never rendered a dash + reason).
const hookReturn = vi.hoisted(() => ({
  value: {
    cost: null as VerticalCostSummary | null,
    workloads: [] as VerticalWorkload[],
    engine: undefined as unknown,
    loading: false,
    error: null as string | null,
  },
}));
vi.mock('../../../../hooks/useEksClusterCost', () => ({
  useEksClusterCost: () => hookReturn.value,
  default: () => hookReturn.value,
}));

import EksCostTab from '../EksCostTab';

const BASE_PROPS = {
  eksData: { dashboard: { nodes: [{ name: 'ip-10-0-1-10', instanceType: 'm5.large', condition: 'Ready' }] } },
  clusterName: 'prod-eks',
  accountId: '102227938259',
  region: 'ap-south-1',
};

/** A realistic engine payload: real cluster $, priced + unpriced namespaces/workloads/nodes. */
function fullCost(overrides: Partial<VerticalCostSummary> = {}): VerticalCostSummary {
  return {
    currency: 'USD',
    hoursPerMonth: 730,
    monthlySavings: 4.2,
    monthlySavingsUnavailableReason: null,
    clusterMonthlyCost: 23.67,
    pricedWorkloads: 2,
    unpricedWorkloads: 1,
    nodeCosts: [
      {
        nodeName: 'ip-10-0-1-10',
        instanceType: 'm5.large',
        capacityType: 'on-demand',
        capacityTypeInferred: false,
        monthlyCost: 70.08,
        source: 'PRICE_LIST',
        reason: null,
        note: null,
      },
      {
        // An unpriced node — must render a dash + mapped reason copy, never $0.00.
        nodeName: 'fargate-node-1',
        instanceType: null,
        capacityType: null,
        capacityTypeInferred: false,
        monthlyCost: null,
        source: null,
        reason: 'FARGATE_UNPRICEABLE',
        note: null,
      },
    ],
    namespaceCosts: [
      { namespace: 'default', monthlyCost: 18.5, pricedWorkloads: 1, unpricedWorkloads: 0 },
      { namespace: 'kube-system', monthlyCost: null, pricedWorkloads: 0, unpricedWorkloads: 1 },
    ],
    ...overrides,
  };
}

function workloadFixtures(): VerticalWorkload[] {
  return [
    {
      ref: { namespace: 'default', kind: 'Deployment', name: 'web-api' },
      cost: {
        monthlyCost: 12.34,
        monthlySavings: 2.1,
        reason: null,
        source: 'PRICE_LIST',
        capacityType: 'on-demand',
        capacityTypeInferred: false,
        note: null,
      },
    },
    {
      ref: { namespace: 'kube-system', kind: 'Deployment', name: 'coredns' },
      cost: {
        monthlyCost: null,
        monthlySavings: null,
        reason: 'NODE_CAPACITY_UNKNOWN',
        source: null,
        capacityType: null,
        capacityTypeInferred: false,
        note: null,
      },
    },
  ];
}

function mock(cost: VerticalCostSummary | null, workloads: VerticalWorkload[] = [], error: string | null = null) {
  hookReturn.value = { cost, workloads, engine: undefined, loading: false, error };
}

afterEach(() => {
  mock(null, []);
});

describe('EksCostTab — real engine $ (FEAT-002)', () => {
  it('renders real AWS $ on the Cluster KPI strip, Workloads, and Node Fleet', () => {
    mock(fullCost(), workloadFixtures());
    render(<EksCostTab {...BASE_PROPS} />);

    // Cluster KPI strip: Monthly Est. = clusterMonthlyCost (23.67 → $24), daily = 23.67/30.
    expect(screen.getByText('$24')).toBeTruthy();
    expect(screen.getByText(/per day · \$0\.03/)).toBeTruthy(); // 23.67/730 ≈ 0.0324/hr

    // Node Fleet: the priced node shows its real monthly ($70) and per-day figure, never $0.00.
    expect(screen.getByText('ip-10-0-1-10')).toBeTruthy();
    expect(screen.getByText(/\$70\/mo/)).toBeTruthy();

    // Workload ranking: the priced deployment shows real $ (12.34 → $12 monthly), never $0.
    expect(screen.getByText('web-api')).toBeTruthy();
    expect(screen.getByText('$12')).toBeTruthy();
  });

  it('renders a dash (not $0.00) with mapped reason copy for an unpriced node', () => {
    mock(fullCost(), workloadFixtures());
    render(<EksCostTab {...BASE_PROPS} />);

    // The Fargate node is present and shows the mapped reason — and NO $0.00 anywhere.
    expect(screen.getByText('fargate-node-1')).toBeTruthy();
    expect(screen.getByText(/Not priced per instance type/)).toBeTruthy();
    expect(screen.queryByText('$0.00')).toBeNull();
  });

  it('(b) cost == null renders fully honest-empty — dashed KPIs, empty panels, no $0.00', () => {
    mock(null, []);
    render(<EksCostTab {...BASE_PROPS} />);

    // KPI values are dashes, not $0.00.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.queryByText('$0.00')).toBeNull();
    expect(screen.getAllByText(/Live Kubernetes cost pricing is not enabled/).length).toBeGreaterThan(0);

    // Breakdown panels are empty (no nodeCosts / namespaceCosts).
    expect(screen.getByText('No namespace cost data available')).toBeTruthy();
    expect(screen.getByText('No node cost data available')).toBeTruthy();
  });

  it('(b2) cost != null but clusterMonthlyCost == null → dashed KPI strip + populated all-dash Node Fleet', () => {
    const allDash = fullCost({
      clusterMonthlyCost: null,
      monthlySavings: null,
      monthlySavingsUnavailableReason: 'NO_LIVE_PRICE',
      nodeCosts: [
        { nodeName: 'ip-10-0-1-10', instanceType: 'm5.large', capacityType: 'on-demand', capacityTypeInferred: false, monthlyCost: null, source: null, reason: 'NO_LIVE_PRICE', note: null },
        { nodeName: 'ip-10-0-1-11', instanceType: 'm5.xlarge', capacityType: 'spot', capacityTypeInferred: false, monthlyCost: null, source: null, reason: 'NO_SPOT_HISTORY', note: null },
      ],
    });
    mock(allDash, []);
    render(<EksCostTab {...BASE_PROPS} />);

    // KPI strip is dashed (no real monthly), never $0.00.
    expect(screen.queryByText('$0.00')).toBeNull();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);

    // Node Fleet is NOT empty — every node renders a dashed row with its reason.
    expect(screen.getByText('ip-10-0-1-10')).toBeTruthy();
    expect(screen.getByText('ip-10-0-1-11')).toBeTruthy();
    // NO_LIVE_PRICE copy appears on both the dashed KPI sub-label and the node row.
    expect(screen.getAllByText(/No live AWS price/).length).toBeGreaterThan(0);
    expect(screen.getByText(/No recent AWS spot price history/)).toBeTruthy();
    expect(screen.queryByText('No node cost data available')).toBeNull();
  });

  it('(e) monthlySavings == 0 → neutral $0/mo, not a green Delta', () => {
    mock(fullCost({ monthlySavings: 0 }), workloadFixtures());
    const { container } = render(<EksCostTab {...BASE_PROPS} />);

    expect(screen.getByText('$0/mo')).toBeTruthy();
    expect(screen.getByText('No rightsizing recommendations yet')).toBeTruthy();
    // No green money Delta rendered for a true-zero saving.
    expect(container.querySelector('[data-delta="saved"]')).toBeNull();
  });

  it('(f) monthlySavings > 0 → green Delta carrying its aria-hidden icon', () => {
    mock(fullCost({ monthlySavings: 9 }), workloadFixtures());
    const { container } = render(<EksCostTab {...BASE_PROPS} />);

    const delta = container.querySelector('[data-delta="saved"]') as HTMLElement | null;
    expect(delta).toBeTruthy();
    expect(within(delta!).getByText('$9/mo')).toBeTruthy();
    // Colour-audit compliance: the coloured money text carries an aria-hidden icon.
    expect(delta!.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
  });

  it('DaemonSets tab is honestly empty (not evaluated by the cost engine)', () => {
    mock(fullCost(), workloadFixtures());
    render(<EksCostTab {...BASE_PROPS} />);

    const daemonTab = screen.getByRole('button', { name: /DaemonSets/ });
    fireEvent.click(daemonTab);
    expect(screen.getByText('DaemonSets are not evaluated by the cost engine')).toBeTruthy();
  });
});
