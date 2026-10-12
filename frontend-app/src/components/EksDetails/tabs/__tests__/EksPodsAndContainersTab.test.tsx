import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

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

beforeEach(() => { getCapabilities.mockReset(); });
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

  it('filters by status and opens a pod to its containers', async () => {
    const user = userEvent.setup();
    renderWith(<EksPodsTab eksData={eksData} clusterName="prod-eks-cluster" />);
    const table = screen.getByRole('table', { name: PODS_CAPTION });
    await user.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Pending' }));
    expect(table.querySelectorAll('tbody tr')).toHaveLength(2);
    await user.click(within(row(table, 'data-pipeline-pending-1')).getByRole('button'));
    const containers = screen.getByRole('table', { name: 'Containers of data-pipeline-pending-1' });
    expect(containers).toHaveTextContent('Waiting · ContainerCreating');
    expect(containers).toHaveTextContent('Unset / None');
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
