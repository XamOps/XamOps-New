import { render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getAutomationDashboard, getNodeFleet, getRebalanceStatus } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  getAutomationDashboard: vi.fn(),
  getNodeFleet: vi.fn(),
  getRebalanceStatus: vi.fn(),
}));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities, getAutomationDashboard, getNodeFleet, getRebalanceStatus };
});
// The usage feed (FEAT-004) is read through apiClient; every other read above is mocked.
vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import { demoUsage } from '../../../../../../demo/k8sAutomationSeriesMockData';
import { K8sApiError } from '../../../../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../../../../hooks/useK8sCapabilities';
import { demoAutomationDashboard, demoCapabilities, demoNodeFleet } from '../../../../../../demo/k8sAutomationMockData';
import type { CapabilitiesDto } from '../../../../../../types/k8sAutomation';
import type { UseEksDashboardReturn } from '../../../../../../hooks/useEksDashboard';
import NodeAutoscalerPage from '../NodeAutoscalerPage';

const CLUSTER = 'prod-eks-cluster';

/** xamops-test-1 in miniature: two on-demand t3.medium nodes in one managed node group, a pod that can't fit. */
const EKS = {
  loading: false,
  dashboard: {
    k8sNodes: [
      { name: 'ip-172-31-9-125.ap-south-1.compute.internal', capacityType: 'on-demand', allocatableCpu: '1930m', allocatableMemory: '3200Mi', allocatablePods: '17', labels: { 'eks.amazonaws.com/nodegroup': 'ng-ondemand-1', 'node.kubernetes.io/instance-type': 't3.medium' }, conditions: [{ type: 'Ready', status: 'True' }] },
      { name: 'ip-172-31-43-198.ap-south-1.compute.internal', capacityType: 'on-demand', allocatableCpu: '1930m', allocatableMemory: '3200Mi', allocatablePods: '17', labels: { 'eks.amazonaws.com/nodegroup': 'ng-ondemand-1', 'node.kubernetes.io/instance-type': 't3.medium' }, conditions: [{ type: 'Ready', status: 'True' }] },
    ],
    k8sPods: [
      { name: 'api-1', namespace: 'team-a', phase: 'Running', nodeName: 'ip-172-31-9-125.ap-south-1.compute.internal', containers: [{ name: 'api', cpuRequest: '1000m', memoryRequest: '1Gi' }] },
      { name: 'enc-1', namespace: 'prod-sim', phase: 'Running', nodeName: 'ip-172-31-43-198.ap-south-1.compute.internal', containers: [{ name: 'enc', cpuRequest: '500m', memoryRequest: '512Mi' }] },
      { name: 'unschedulable-giant-1', namespace: 'team-a', phase: 'Pending', unschedulable: true, containers: [{ name: 'g', cpuRequest: '32', memoryRequest: '64Gi' }] },
    ],
    k8sDeployments: [{ name: 'api', namespace: 'team-a' }],
    karpenter: null,
  },
} as unknown as UseEksDashboardReturn;

function caps(edit?: (dto: CapabilitiesDto) => void): CapabilitiesDto {
  const dto = demoCapabilities('demo-aws-001')!;
  edit?.(dto);
  return dto;
}

async function page(dto: CapabilitiesDto = caps(), eksData: UseEksDashboardReturn = EKS) {
  getCapabilities.mockResolvedValue(dto);
  const view = render(
    <K8sCapabilitiesProvider accountId={dto.accountId} demo>
      <NodeAutoscalerPage clusterName={CLUSTER} accountId={dto.accountId} region="us-east-1" eksData={eksData} />
    </K8sCapabilitiesProvider>,
  );
  await waitFor(() => expect(document.querySelector('[data-node-changer="automation-agent"]')).not.toHaveTextContent('checking'));
  return view;
}

const lever = (id: string) => document.querySelector<HTMLElement>(`[data-node-lever="${id}"]`)!;

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;
const json = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => (body === null ? '' : JSON.stringify(body)) }) as unknown as Response;
/** The usage feed answers from the demo fixture (prod-eks-cluster), or `answer` when given. */
function serveUsage(answer?: { status: number; body: unknown }) {
  mockedFetch.mockImplementation(async (url: string) => {
    const u = new URL(String(url), 'http://test');
    if (!u.pathname.startsWith('/api/eks/automation/usage/')) throw new Error(`unexpected request ${url}`);
    if (answer) return json(answer.status, answer.body);
    const p = (k: string) => u.searchParams.get(k) ?? '';
    const a = demoUsage(CLUSTER, { scope: p('scope'), resource: p('resource'), window: p('window'), namespace: p('namespace'), kind: p('kind'), name: p('name'), from: p('from'), to: p('to'), step: p('step') });
    return json(a.status, a.body);
  });
}
const usageCalls = () => mockedFetch.mock.calls.map(([url]) => new URL(String(url), 'http://test'));

describe('Automation › Node autoscaler (UI v3 plan §2)', () => {
  beforeEach(() => {
    getAutomationDashboard.mockResolvedValue(demoAutomationDashboard(CLUSTER));
    getNodeFleet.mockResolvedValue(demoNodeFleet(CLUSTER));
    getRebalanceStatus.mockResolvedValue({ status: 'idle', message: 'No rebalance has been run yet' });
    serveUsage();
  });
  afterEach(() => vi.clearAllMocks());

  it('renders no controls and no settings form: nothing on the page but reads', async () => {
    const { container } = await page();
    await screen.findByText('What node autoscaling would do now');
    expect(container.querySelectorAll('button, input, select, textarea, [role="switch"], [role="checkbox"], [role="radio"]')).toHaveLength(0);
    expect(screen.queryByRole('button')).toBeNull();
    // Only reads went out.
    for (const fn of [getAutomationDashboard, getNodeFleet, getRebalanceStatus]) expect(fn).toHaveBeenCalledTimes(1);
  });

  it('says who changes the node count, from the agent, and the automation agent from capabilities and its dashboard', async () => {
    await page();
    expect(document.querySelector('[data-node-changer="karpenter"]')).toHaveTextContent('Karpenter: not installed');
    expect(document.querySelector('[data-node-changer="cluster-autoscaler"]')).toHaveTextContent('Cluster Autoscaler: not installed');
    expect(document.querySelector('[data-node-changer="node-group:ng-ondemand-1"]')).toHaveTextContent('Node group ng-ondemand-1: EKS managed · t3.medium, 2 on-demand · 2 nodes now');
    expect(document.querySelector('[data-node-changer="none"]')).toHaveTextContent(`Nothing adds or removes nodes on ${CLUSTER} today.`);
    const agent = document.querySelector('[data-node-changer="automation-agent"]')!;
    expect(agent).toHaveTextContent('XamOps node automation agent: installed (1.0.0-autoscaler, last report');
    await waitFor(() => expect(agent).toHaveTextContent('Node automation mode: full.'));
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      `Nothing adds or removes nodes on ${CLUSTER} today. 2 nodes: 2 on-demand, 0 Spot. Pods reserve 39% of the nodes' CPU.`,
    );
  });

  it('shows the KPIs and the per-node bars from the agent, and idle nodes only from the automation agent', async () => {
    await page();
    const kpis = [...document.querySelectorAll('[data-node-kpis] [data-kpi-tone]')].map((k) => k.textContent);
    expect(kpis[0]).toMatch(/^Nodes22 readyOn-demand 2 · Spot 0/);
    expect(kpis[1]).toMatch(/^Pods waiting for a node1team-a\/unschedulable-giant-1 asks for 32.00 vCPU and 64 GiB/);
    expect(kpis[2]).toMatch(/^CPU requested39%1.50 vCPU of 3.86 vCPU pods can use/);
    expect(kpis[2]).toContain('Use: not reported by Prometheus in the cluster');
    await waitFor(() => expect(document.querySelectorAll('[data-node-kpis] [data-kpi-tone]')[4]).toHaveTextContent("Idle nodes0from the node automation agent's node report"));
    const cpu = screen.getByRole('heading', { name: 'CPU on each node: requested of allocatable' }).closest('section')!;
    const row = within(cpu).getByRole('rowheader', { name: 'ip-172-31-9-125.ap-south-1.compute.internal', hidden: true }).closest('tr')!;
    expect([...row.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['1 vCPU', '1.93 vCPU', '52%']);
    expect(screen.getByRole('heading', { name: 'Pods on each node' })).toBeInTheDocument();
  });

  it("stacks the cluster's CPU over 3 days up to allocatable, from one usage read", async () => {
    await page();
    const chart = await waitFor(() => {
      const el = screen.getByRole('heading', { name: 'CPU: what pods can use, reserve and use' }).closest('section')!;
      expect(el.querySelectorAll('tbody tr')).toHaveLength(145);
      return el;
    });
    expect(usageCalls().map((u) => `${u.searchParams.get('scope')}:${u.searchParams.get('resource')}:${u.searchParams.get('window')}`)).toEqual(['cluster:cpu:72h']);
    // The demo cluster's allocatable CPU at every point, and no memory switch on this CPU-only card.
    const first = [...chart.querySelectorAll('tbody tr')][0];
    expect(first.querySelectorAll('td')[2]).toHaveTextContent('25.52 vCPU');
    expect(within(chart).queryByRole('button', { name: 'Memory' })).toBeNull();
    expect(chart).toHaveTextContent('The layers add up to what the nodes give pods (allocatable); the line is allocatable.');
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('scope=cluster&resource=cpu&window=72h');
  });

  it('renders no cluster CPU chart for a backend without the usage feed', async () => {
    serveUsage({ status: 404, body: null });
    await page();
    await waitFor(() => expect(usageCalls()).toHaveLength(1));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'CPU: what pods can use, reserve and use' })).toBeNull());
  });

  it('marks every part "Not available yet" with its reason; without the automation agent, says it is needed', async () => {
    await page(caps((dto) => {
      dto.clusters[0].automationAgent = { status: 'NOT_INSTALLED', lastSeenAt: null, version: null };
    }));
    for (const id of ['scale-up', 'remove-empty', 'bin-packing', 'rebalancing', 'spot']) {
      expect(lever(id).querySelector('[data-tone="off"]')).toHaveTextContent('Not available yet');
    }
    expect(lever('scale-up')).toHaveTextContent("XamOps doesn't add nodes. Not built in XamOps yet.");
    expect(lever('scale-up')).toHaveTextContent('1 pod is waiting.');
    expect(lever('remove-empty')).toHaveTextContent("Needs the node automation agent, which isn't installed on this cluster.");
    expect(lever('bin-packing')).toHaveTextContent("Needs the node automation agent, which isn't installed on this cluster.");
    expect(lever('rebalancing')).toHaveTextContent('No node provisioner detected.');
    expect(lever('spot')).toHaveTextContent('All 2 nodes are on-demand.');
    // The fleet answered, but without the automation agent its idle nodes aren't claimed.
    expect(document.querySelectorAll('[data-node-kpis] [data-kpi-tone]')[4]).toHaveTextContent("Idle nodes—Needs the node automation agent's node report");
    expect(document.querySelector('[data-node-changer="automation-agent"]')).toHaveTextContent(
      'XamOps node automation agent: not installed. It is the part of XamOps that empties and packs nodes.',
    );
  });

  it("shows a read that failed as Not checked with its reason, never a guess", async () => {
    getAutomationDashboard.mockRejectedValue(new K8sApiError({ status: 500 }));
    getRebalanceStatus.mockRejectedValue(new K8sApiError({ status: 503 }));
    await page();
    await waitFor(() => expect(document.querySelector('[data-node-changer="automation-agent"]')).toHaveTextContent(
      "XamOps couldn't read its node automation settings: XamOps had a problem with this request. Try again.",
    ));
    expect(screen.getByText('Last rebalance').nextElementSibling).toHaveTextContent('Not checked: XamOps had a problem with this request. Try again.');
  });

  it("says Not checked when the agent hasn't reported the cluster, and claims nothing about its nodes", async () => {
    await page(caps(), { loading: false, dashboard: null } as unknown as UseEksDashboardReturn);
    expect(document.querySelector('[data-node-changer="not-checked"]')).toHaveTextContent("the XamOps agent hasn't reported this cluster's nodes and Deployments");
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read who changes the node count just now.");
    expect(screen.getByRole('heading', { name: 'CPU on each node: requested of allocatable' }).closest('section')).toHaveTextContent(
      "The XamOps agent hasn't reported this cluster's nodes.",
    );
  });
});
