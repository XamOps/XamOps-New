import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

// The Pods drawer's Cost section reads per-pod $ from the automation engine (FEAT-002), a different
// path than the agent dashboard. Mock it so a test can hand EksPodsTab a priced / unpriced read.
const { useAutomationRead } = vi.hoisted(() => ({ useAutomationRead: vi.fn() }));
vi.mock('../../../../hooks/useAutomationRead', () => ({ useAutomationRead }));

type PodCost = {
  podName: string; nodeName: string | null; monthlyCost: number | null; reason: string | null;
  source: string | null; capacityType: string | null; capacityTypeInferred: boolean;
};

/** An automation read that attributes `pods` to a single `production`-namespace workload. */
function automationReadWithPods(pods: PodCost[]) {
  return {
    loading: false,
    reload: vi.fn(),
    result: {
      kind: 'ok' as const,
      view: {
        vertical: {
          workloads: [{ ref: { namespace: 'production', kind: 'Deployment', name: 'api-server' }, cost: { pods } }],
        },
      },
    },
  };
}

import { K8sCapabilitiesProvider } from '../../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import { demoCapabilities } from '../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../demo/xamopsMockData';
import EksPodsTab, { PODS_CAPTION } from '../EksPodsTab';
import EksContainersTab, { CONTAINERS_CAPTION, NO_CONTAINER_USAGE } from '../EksContainersTab';

const eksData = { dashboard: demoEksDashboard } as unknown as UseEksDashboardReturn;

function renderWith(ui: React.ReactNode) {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  render(<K8sCapabilitiesProvider accountId="demo-aws-001" demo>{ui}</K8sCapabilitiesProvider>);
}

/** The row whose first cell's first line is `name`. */
function row(table: HTMLElement, name: string): HTMLElement {
  const found = [...table.querySelectorAll('tbody tr')].find((tr) => tr.querySelector('td span > span')?.textContent === name);
  if (!found) throw new Error(`no row ${name}`);
  return found as HTMLElement;
}

function cell(table: HTMLElement, name: string, header: string): HTMLElement {
  const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent?.trim() ?? '');
  const index = headers.findIndex((h) => h.startsWith(header));
  if (index < 0) throw new Error(`no column ${header}`);
  return row(table, name).querySelectorAll('td')[index] as HTMLElement;
}

beforeEach(() => {
  getCapabilities.mockReset();
  // Default: an idle read (no account/region), so the Cost section drops unless a test opts in.
  useAutomationRead.mockReset();
  useAutomationRead.mockReturnValue({ loading: false, result: null, reload: vi.fn() });
});
afterEach(() => vi.restoreAllMocks());

describe('Workloads › Pods (§10.4, D10)', () => {
  it('counts pods by phase from the same list it shows', async () => {
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    expect(document.querySelector('[data-count="Total"]')).toHaveTextContent('18');
    expect(document.querySelector('[data-count="Running"]')).toHaveTextContent('16');
    expect(document.querySelector('[data-count="Pending"]')).toHaveTextContent('2');
    expect(document.querySelector('[data-count="Failed"]')).toHaveTextContent('0');
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('shows usage only from the top-20 lists, and requests in their own columns', async () => {
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    // In topCpuPods and topMemoryPods.
    expect(cell(table, 'api-server-6d8f7b9c4-xk2pq', 'CPU used')).toHaveTextContent('320m');
    expect(cell(table, 'api-server-6d8f7b9c4-xk2pq', 'Memory used')).toHaveTextContent('512 MiB');
    expect(cell(table, 'api-server-6d8f7b9c4-xk2pq', 'CPU requests')).toHaveTextContent('256m');
    // Not in the top 20: unknown, and never the request standing in for it.
    expect(cell(table, 'frontend-5b8c9d7f6-qw2xp', 'CPU used')).toHaveTextContent('—');
    expect(cell(table, 'worker-staging-92f1e4c8-kd2mz', 'CPU requests')).toHaveTextContent('Unset');
    expect(cell(table, 'data-pipeline-pending-1', 'Status')).toHaveTextContent('Pending');
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('filters by status and opens a pod row to its drawer, with full untruncated container detail', async () => {
    const user = userEvent.setup();
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    await user.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Pending' }));
    expect(table.querySelectorAll('tbody tr')).toHaveLength(2);
    // The whole row is the activator (v3 migration: expand chevron → DetailDrawer).
    await user.click(row(table, 'data-pipeline-pending-1'));
    const drawer = screen.getByRole('dialog');
    // Identity reads from the real pod snapshot.
    expect(drawer).toHaveTextContent('data-pipeline-pending-1');
    expect(drawer).toHaveTextContent('staging');
    expect(drawer).toHaveTextContent('BestEffort');
    // The container's FULL untruncated image and its state.
    expect(drawer).toHaveTextContent('xammer/data-pipeline:v0.9.1');
    expect(drawer).toHaveTextContent('Waiting · ContainerCreating');
    expect(drawer).toHaveTextContent('Unset / None');
  });

  it('shows the phase with a StatusDot and never fabricates a per-pod cost', async () => {
    const user = userEvent.setup();
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    // Phase column is a StatusDot: a tiny aria-hidden svg beside the visible word.
    const statusCell = cell(table, 'api-server-6d8f7b9c4-xk2pq', 'Status');
    expect(statusCell.querySelector('[data-tone]')).not.toBeNull();
    expect(statusCell.querySelector('[data-tone] svg[aria-hidden="true"]')).not.toBeNull();
    expect(statusCell).toHaveTextContent('Running');
    // Fixed-width node column truncates but carries the full value as a title tooltip.
    const nodeCell = cell(table, 'api-server-6d8f7b9c4-xk2pq', 'Node');
    expect(nodeCell.querySelector('[title="ip-10-0-1-101"]')).not.toBeNull();
    // The drawer shows the full untruncated node + image, and NO Cost section (per-pod $ GAP).
    await user.click(row(table, 'api-server-6d8f7b9c4-xk2pq'));
    const drawer = screen.getByRole('dialog');
    expect(drawer).toHaveTextContent('ip-10-0-1-101');
    expect(drawer).toHaveTextContent('xammer/api-server:v2.4.1');
    expect(drawer).toHaveTextContent('Guaranteed');
    // Cost section is dropped (all-dash), and no dollar figure is invented anywhere in the drawer.
    expect(within(drawer).queryByText('Cost')).toBeNull();
    expect(within(drawer).queryByText(/\$/)).toBeNull();
  });

  it('shows the Cost section with the real per-pod figure when the engine priced the pod (FEAT-002)', async () => {
    const user = userEvent.setup();
    useAutomationRead.mockReturnValue(automationReadWithPods([
      { podName: 'api-server-6d8f7b9c4-xk2pq', nodeName: 'ip-10-0-1-101', monthlyCost: 12.34, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false },
    ]));
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" accountId="demo-aws-001" region="us-east-1" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    await user.click(row(table, 'api-server-6d8f7b9c4-xk2pq'));
    const drawer = screen.getByRole('dialog');
    // The Cost section renders with the real $X/mo figure, never a fabricated one.
    expect(within(drawer).getByText('Cost')).toBeInTheDocument();
    expect(within(drawer).getByText('$12/mo')).toBeInTheDocument();
  });

  it('keeps the Cost section dropped when the engine has no priced entry for the pod', async () => {
    const user = userEvent.setup();
    // The read carries a different pod only, so this pod joins to nothing -> all-dash -> dropped.
    useAutomationRead.mockReturnValue(automationReadWithPods([
      { podName: 'some-other-pod', nodeName: 'ip-10-0-1-109', monthlyCost: 9.99, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false },
    ]));
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" accountId="demo-aws-001" region="us-east-1" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    await user.click(row(table, 'api-server-6d8f7b9c4-xk2pq'));
    const drawer = screen.getByRole('dialog');
    expect(within(drawer).queryByText('Cost')).toBeNull();
    expect(within(drawer).queryByText(/\$/)).toBeNull();
  });

  it('exports CSV and keeps the search box', async () => {
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    expect(screen.getByRole('button', { name: /Export CSV/i })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: /Search pods/i })).toBeInTheDocument();
  });

  it("opens on the status a link asked for with phase= (Overview's View pending pods)", async () => {
    window.history.pushState({}, '', '/eks-details?name=prod-eks-cluster&tab=workloads&sub=pods&phase=Pending');
    try {
      renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
      const table = screen.getByRole('table', { name: PODS_CAPTION });
      expect(table.querySelectorAll('tbody tr')).toHaveLength(2);
      expect(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Pending' })).toHaveAttribute('aria-pressed', 'true');
    } finally {
      window.history.pushState({}, '', '/');
    }
  });
});

describe('Workloads › Containers (§10.4, D10)', () => {
  it('lists every container with requests and limits as set, and says usage is not reported', async () => {
    renderWith(<EksContainersTab eksData={eksData} clusterName="prod-eks-cluster" />);
    const table = screen.getByRole('table', { name: CONTAINERS_CAPTION });
    expect(table.querySelectorAll('tbody tr')).toHaveLength(18);
    expect(screen.getByText(NO_CONTAINER_USAGE)).toBeInTheDocument();
    expect(cell(table, 'coredns', 'CPU limit')).toHaveTextContent('None');
    expect(cell(table, 'aws-node', 'Memory request')).toHaveTextContent('Unset');
    expect(cell(table, 'prometheus', 'Memory limit')).toHaveTextContent('4 GiB');
    expect(table).not.toHaveTextContent(/\b0m\b/);
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });
});
