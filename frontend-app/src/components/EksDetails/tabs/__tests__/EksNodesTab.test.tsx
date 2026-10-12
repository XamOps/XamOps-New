import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sCapabilitiesProvider } from '../../../../hooks/useK8sCapabilities';
import { useEksSectionRoute } from '../../../../hooks/useEksSectionRoute';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import { demoCapabilities } from '../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../demo/xamopsMockData';
import { nodeRows } from '../../../../utils/k8sDerivations';
import EksNodesTab, { NODES_CAPTION } from '../EksNodesTab';
import NodeGroupsPanel, { NODE_GROUPS_CAPTION, NODE_GROUP_API_NOTE } from '../../sections/nodes/NodeGroupsPanel';

const clone = (edit?: (d: any) => void) => {
  const d = structuredClone(demoEksDashboard) as any;
  edit?.(d);
  return d;
};

function renderNodes(dashboard: unknown = demoEksDashboard) {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  render(
    <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
      <EksNodesTab eksData={{ dashboard } as unknown as UseEksDashboardReturn} clusterName="prod-eks-cluster" />
    </K8sCapabilitiesProvider>,
  );
  return screen.getByRole('table', { name: NODES_CAPTION });
}

function GroupsHarness({ dashboard }: { dashboard: unknown }) {
  const route = useEksSectionRoute();
  return (
    <NodeGroupsPanel
      clusterName="prod-eks-cluster" accountId="demo-aws-001" region="us-east-1"
      eksData={{ dashboard } as unknown as UseEksDashboardReturn} route={route} onOpenGuide={() => {}}
    />
  );
}

const count = (id: string) => document.querySelector(`[data-count="${id}"] dd`)?.textContent;
const rowOf = (table: HTMLElement, name: string) =>
  [...table.querySelectorAll('tbody tr')].find((tr) => tr.querySelector('td span > span')?.textContent === name) as HTMLElement;

beforeEach(() => { getCapabilities.mockReset(); });
afterEach(() => vi.restoreAllMocks());

describe('Nodes › Nodes (§10.5, cast.ai-shaped)', () => {
  it('splits the nodes by capacity type from their labels, and counts statuses', async () => {
    renderNodes();
    expect(count('total')).toBe('6');
    expect(count('on-demand')).toBe('3');
    expect(count('spot')).toBe('3');
    expect(count('ready')).toBe('6');
    expect(count('not-ready')).toBe('0');
    expect(count('cordoned')).toBe('0');
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('says Unknown when nothing reports the capacity type, and counts cordoned nodes apart', async () => {
    renderNodes(clone((d) => {
      d.k8sNodes[5].capacityType = undefined;
      d.k8sNodes[0].unschedulable = true;
      d.k8sNodes[1].conditions = [{ type: 'Ready', status: 'False' }];
    }));
    expect(count('unknown-offering')).toBe('1');
    expect(count('cordoned')).toBe('1');
    expect(count('not-ready')).toBe('1');
    expect(count('ready')).toBe('4');
    const table = screen.getByRole('table', { name: NODES_CAPTION });
    expect(rowOf(table, 'ip-10-0-1-106')).toHaveTextContent('Unknown');
    expect(rowOf(table, 'ip-10-0-1-101')).toHaveTextContent('Cordoned');
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('shows CPU and memory requests of allocatable with a bar', async () => {
    const table = renderNodes();
    // ip-10-0-1-101 runs api-server (256m), frontend (50m), prometheus (500m) and coredns (100m).
    const row = rowOf(table, 'ip-10-0-1-101');
    expect(row).toHaveTextContent('906m of 3.92 cores');
    expect(row).toHaveTextContent('23%');
    expect(row.querySelectorAll('[data-request-bar] [aria-hidden="true"]').length).toBe(2);
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('filters by offering and status', async () => {
    const user = userEvent.setup();
    const table = renderNodes();
    await user.click(within(screen.getByRole('group', { name: 'Offering' })).getByRole('button', { name: 'Spot' }));
    expect(table.querySelectorAll('tbody tr')).toHaveLength(3);
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('derives requests only from pods still on the node', () => {
    const rows = nodeRows(clone((d) => { d.k8sPods.push({ ...d.k8sPods[0], name: 'done', phase: 'Succeeded' }); }));
    const before = nodeRows(demoEksDashboard as any).find((n) => n.name === 'ip-10-0-1-101')!;
    expect(rows.find((n) => n.name === 'ip-10-0-1-101')!.cpuRequestM).toBe(before.cpuRequestM);
  });
});

describe('Nodes › Node groups (§10.5)', () => {
  it('groups by managed node group, then NodePool, else Unmanaged, with the F1 note', async () => {
    getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
    render(
      <MemoryRouter>
        <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
          <GroupsHarness dashboard={demoEksDashboard} />
        </K8sCapabilitiesProvider>
      </MemoryRouter>,
    );
    const table = screen.getByRole('table', { name: NODE_GROUPS_CAPTION });
    const groups = [...table.querySelectorAll('tbody tr')].map((tr) => tr.querySelector('td span > span')?.textContent);
    expect(groups).toEqual(['general', 'default', 'Unmanaged']);
    expect(rowOf(table, 'general')).toHaveTextContent('Managed node group');
    expect(rowOf(table, 'default')).toHaveTextContent('3 / 3');
    expect(rowOf(table, 'default')).toHaveTextContent('0 / 3');
    expect(screen.getByText(NODE_GROUP_API_NOTE)).toBeInTheDocument();
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });
});
