import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getVerticalStatus } = vi.hoisted(() => ({ getCapabilities: vi.fn(), getVerticalStatus: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../api/verticalRightsizerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/verticalRightsizerApi')>();
  return { ...actual, getVerticalStatus };
});

import { K8sApiError } from '../../../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { EKS_SECTIONS, LEGACY_TAB_MAP, resolveSectionRoute, useEksSectionRoute } from '../../../../../hooks/useEksSectionRoute';
import { clearVerticalCacheForTests } from '../../../../../hooks/useVerticalRightsizer';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { demoCapabilities, demoVerticalStatus } from '../../../../../demo/k8sAutomationMockData';
import { demoEksDashboard, demoEksEmptyShellDashboard } from '../../../../../demo/xamopsMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import { CAPACITY_TERMS } from '../../../../CloudK8s/shared/CapacityDonut';
import { NOT_MONITORED_TITLE } from '../../../../CloudK8s/shared/NotMonitoredPanel';
import SummaryPanel, {
  ALERTS_HREF, ATTENTION_TITLE, AUTOMATION_TITLE, CLUSTER_TITLE, COST_TITLE, FREED_TOOLTIP, NODES_TITLE, NODE_AUTOSCALER,
  PODS_TITLE, REVIEW_SUGGESTIONS, USED_NOT_REPORTED, VIEW_ALL_ALERTS, VIEW_PENDING_PODS, WORKLOAD_AUTOSCALER,
} from '../SummaryPanel';

const ACCOUNT = 'demo-aws-001';
const PROD = 'prod-eks-cluster';
const clone = (edit?: (d: any) => void) => {
  const d = structuredClone(demoEksDashboard) as any;
  edit?.(d);
  return d;
};

function Harness({ cluster, dashboard, onOpenGuide }: { cluster: string; dashboard: unknown; onOpenGuide: () => void }) {
  const route = useEksSectionRoute();
  return (
    <SummaryPanel
      clusterName={cluster}
      accountId={ACCOUNT}
      region="us-east-1"
      eksData={{ dashboard } as unknown as UseEksDashboardReturn}
      route={route}
      onOpenGuide={onOpenGuide}
    />
  );
}

function renderPanel({ cluster = PROD, dashboard = clone(), caps, engine = demoVerticalStatus(PROD) }: {
  cluster?: string; dashboard?: unknown; caps?: CapabilitiesDto | Error; engine?: unknown;
} = {}) {
  if (caps instanceof Error) getCapabilities.mockRejectedValue(caps);
  else getCapabilities.mockResolvedValue(caps ?? demoCapabilities(ACCOUNT));
  if (engine instanceof Error) getVerticalStatus.mockRejectedValue(engine);
  else getVerticalStatus.mockResolvedValue(engine);
  const onOpenGuide = vi.fn();
  render(
    <MemoryRouter initialEntries={[`/eks-details?name=${cluster}&region=us-east-1&accountIds=${ACCOUNT}&version=1.29&tab=overview&sub=summary`]}>
      <K8sCapabilitiesProvider accountId={ACCOUNT} demo>
        <Harness cluster={cluster} dashboard={dashboard} onOpenGuide={onOpenGuide} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  return { onOpenGuide };
}

const region = (name: string) => screen.getByRole('region', { name });
const fact = (card: HTMLElement, term: string) => card.querySelector(`[data-fact="${term}"] dd`);
const capacityRow = (resource: string, term: string) => region(resource).querySelector(`[data-capacity-row="${term}"]`) as HTMLElement;
/** The section and subtab a link opens, through the page's own resolver. */
const resolved = (href: string) => {
  const url = new URL(href, 'http://x');
  expect(url.pathname).toBe('/eks-details');
  expect(url.searchParams.get('name')).toBe(PROD);
  return { ...resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, url.search.slice(1)), params: url.searchParams };
};

// A block body: a function returned from beforeEach would run as teardown.
beforeEach(() => {
  getCapabilities.mockReset();
  getVerticalStatus.mockReset();
  clearVerticalCacheForTests();
});
afterEach(() => vi.restoreAllMocks());

describe('Overview › Summary, laid out like the cast.ai Dashboard (23b)', () => {
  it('shows the Not monitored panel for the empty shell, never zeros', async () => {
    const user = userEvent.setup();
    const { onOpenGuide } = renderPanel({ cluster: 'staging-eks-cluster', dashboard: demoEksEmptyShellDashboard('staging-eks-cluster') });
    expect(await screen.findByRole('heading', { name: NOT_MONITORED_TITLE })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: NODES_TITLE })).toBeNull();
    expect(document.body).not.toHaveTextContent('100');
    await user.click(screen.getByRole('button', { name: 'Set up monitoring' }));
    expect(onOpenGuide).toHaveBeenCalledTimes(1);
  });

  it('lays out the mock: four cards, CPU and memory, the Workload autoscaler, Automation and Needs attention', async () => {
    renderPanel();
    for (const name of [CLUSTER_TITLE, NODES_TITLE, PODS_TITLE, COST_TITLE, 'CPU', 'Memory', `${WORKLOAD_AUTOSCALER} summary`, AUTOMATION_TITLE, ATTENTION_TITLE]) {
      expect(await screen.findByRole('region', { name })).toBeInTheDocument();
    }
    // No time-series charts and no list-price dollars until their sources exist.
    expect(document.body).not.toHaveTextContent(/over time/i);
    expect(document.body).not.toHaveTextContent(/list price/i);
    expect(document.body).not.toHaveTextContent(/score/i);
  });

  it('describes the cluster from the link and the agent', async () => {
    renderPanel();
    const cluster = await screen.findByRole('region', { name: CLUSTER_TITLE });
    await waitFor(() => expect(fact(cluster, 'XamOps agent')).toHaveTextContent('v2.1.0'));
    expect(cluster).toHaveTextContent('Connected');
    expect(fact(cluster, 'Kubernetes')).toHaveTextContent('1.29');
    expect(fact(cluster, 'Region')).toHaveTextContent('us-east-1, 3 zones');
    expect(fact(cluster, 'Namespaces')).toHaveTextContent('8');
  });

  it('counts nodes by capacity type, names instance types and node groups, and shows unused requests', async () => {
    renderPanel({ dashboard: clone((d) => { d.clusterOverview.cpuUsageMillicores = 1000; }) });
    const nodes = await screen.findByRole('region', { name: NODES_TITLE });
    expect(nodes).toHaveTextContent(/^Nodes6/);
    const chip = (label: string) => nodes.querySelector(`[data-capacity-type="${label}"]`);
    expect(chip('On-demand')).toHaveTextContent('On-demand3');
    expect(chip('Spot')).toHaveTextContent('Spot3');
    expect(chip('Unknown')).toHaveTextContent('Unknown0');
    const types = nodes.querySelector('[data-instance-types]')!;
    expect(types).toHaveTextContent('2 · c5.xlarge (4 vCPU, 7.57 GiB), 2 · m5.xlarge (4 vCPU, 15.36 GiB), +2 more types');
    expect(nodes.querySelector('[data-node-groups]')).toHaveTextContent('Node groups: general, default, self-managed');
    // 2143m requested and 1000m used: 53% of the requests sit unused.
    expect(nodes.querySelector('[data-unused-requests]')).toHaveTextContent('Unused requests: CPU 53%');
  });

  it('says unused requests are unknown, not 0%, when Prometheus reported no usage', async () => {
    renderPanel({ dashboard: clone((d) => { d.clusterOverview.cpuUsagePercent = 0; d.clusterOverview.memoryUsagePercent = 0; delete d.nodeUtilization; }) });
    const nodes = await screen.findByRole('region', { name: NODES_TITLE });
    expect(nodes.querySelector('[data-unused-requests]')).toHaveTextContent('Unused requests: — (usage not reported by Prometheus in your cluster)');
  });

  it('splits pods into running on a node and waiting for one, linking to the pending pods', async () => {
    renderPanel();
    const pods = await screen.findByRole('region', { name: PODS_TITLE });
    expect(pods).toHaveTextContent(/^Pods18/);
    expect(pods.querySelector('[data-pod-placement]')).toHaveTextContent('16 running on a node · 2 waiting for a node');
    expect(pods.querySelector('[data-unschedulable]')).toBeNull();
    const link = within(pods).getByRole('link', { name: VIEW_PENDING_PODS });
    const target = resolved(link.getAttribute('href')!);
    expect([target.tab, target.sub, target.corrected]).toEqual(['workloads', 'pods', null]);
    expect(target.params.get('phase')).toBe('Pending');
  });

  it('calls out a pod no node can fit, with what it asks for and the largest node', async () => {
    renderPanel({
      dashboard: clone((d) => {
        const pod = d.k8sPods.find((p: any) => p.name === 'data-pipeline-pending-1');
        pod.unschedulable = true;
        pod.containers[0].cpuRequest = '32';
        pod.containers[0].memoryRequest = '64Gi';
      }),
    });
    const pods = await screen.findByRole('region', { name: PODS_TITLE });
    expect(pods.querySelector('[data-unschedulable]')).toHaveTextContent(
      'staging/data-pipeline-pending-1 asks for 32 vCPU and 64 GiB. The largest node has 7.91 vCPU allocatable.',
    );
    const attention = region(ATTENTION_TITLE);
    const item = attention.querySelector('[data-attention="unschedulable"]')!;
    expect(item).toHaveTextContent("1 pod can't be scheduled");
    expect(item).toHaveTextContent('no node is that large.');
    // The scheduler event that says the same thing isn't listed twice.
    expect(attention).not.toHaveTextContent('FailedScheduling');
  });

  it('shows cost as the cost source reports it, in whole dollars', async () => {
    renderPanel();
    const cost = await screen.findByRole('region', { name: COST_TITLE });
    expect(cost).toHaveTextContent('$1,480/mo');
    expect(cost).toHaveTextContent('Today $49');
    expect(cost).toHaveTextContent('From XamOps cost exporter (Prometheus)');
  });

  it('never shows $0 for a cluster without a cost source', async () => {
    renderPanel({ dashboard: clone((d) => { delete d.agentCostMetrics; delete d.openCost; delete d.costMetrics; }) });
    const cost = await screen.findByRole('region', { name: COST_TITLE });
    expect(cost).toHaveTextContent('$—');
    expect(cost).toHaveTextContent('No cost source reported for this cluster.');
    expect(cost).not.toHaveTextContent('$0');
  });

  it('draws CPU from node capacity, pod requests and Prometheus, each with its glossary definition', async () => {
    renderPanel();
    await screen.findByRole('region', { name: 'CPU' });
    // Six nodes: 26 cores of capacity, 25.52 allocatable; 16 scheduled pods request 2143m.
    expect(region('CPU')).toHaveTextContent('26.00 vCPUprovisioned');
    expect(capacityRow('CPU', 'Requested')).toHaveTextContent('Requested2.14 vCPU8% of allocatable');
    expect(capacityRow('CPU', 'Used')).toHaveTextContent('Used12.48 vCPU');
    expect(capacityRow('CPU', 'Allocatable')).toHaveTextContent('Allocatable25.52 vCPUwhat pods can use');
    expect(capacityRow('CPU', 'Overhead')).toHaveTextContent('Overhead0.48 vCPUkept by the node for the system');
    expect(capacityRow('CPU', 'Requested').querySelector('dt')).toHaveAttribute('title', CAPACITY_TERMS.requested);
    expect(capacityRow('Memory', 'Used')).toHaveTextContent('Used40 GiB');
  });

  it('shows used as — with the missing source, never 0, when Prometheus reported nothing', async () => {
    renderPanel({ dashboard: clone((d) => { d.clusterOverview.cpuUsagePercent = 0; d.clusterOverview.memoryUsagePercent = 0; delete d.nodeUtilization; }) });
    await screen.findByRole('region', { name: 'CPU' });
    const used = capacityRow('CPU', 'Used');
    expect(used).toHaveTextContent(`Used—${USED_NOT_REPORTED}`);
    expect(used.querySelector('dd')).toHaveAttribute('title', USED_NOT_REPORTED);
  });

  it("splits the Workload autoscaler's workloads into the glossary's buckets, in order, and links to its suggestions", async () => {
    renderPanel();
    const card = await screen.findByRole('region', { name: `${WORKLOAD_AUTOSCALER} summary` });
    await waitFor(() => expect(card.querySelectorAll('[data-bucket]').length).toBeGreaterThan(0));
    const buckets = [...card.querySelectorAll('[data-bucket]')].map((b) => `${(b as HTMLElement).dataset.bucket}=${b.querySelector('dd')!.textContent}`);
    expect(buckets).toEqual(['Over-provisioned=2', 'Needs more resources=3', 'Right-sized=1', 'Left alone=1', 'Skipped=2']);
    expect(card).toHaveTextContent('9 workloads analysed · 3 need more resources');
    // api-staging frees 75m and 6Mi, alertmanager 20m; one replica each. Capacity, not dollars.
    const freed = card.querySelector('[data-freed]')!;
    expect(freed).toHaveTextContent('Could free 0.10 vCPU and 6Mi');
    expect(freed).toHaveAttribute('title', FREED_TOOLTIP);
    expect(card).not.toHaveTextContent('$');
    const target = resolved(within(card).getByRole('link', { name: REVIEW_SUGGESTIONS }).getAttribute('href')!);
    expect([target.tab, target.sub, target.corrected]).toEqual(['automation', 'workload-autoscaling', null]);
  });

  it('uses the status words when the Workload autoscaler is off here, and offers Retry when it fails', async () => {
    renderPanel({ engine: { ...demoVerticalStatus(PROD), engine: { ...demoVerticalStatus(PROD)!.engine, enabled: false }, workloads: [] } });
    const card = await screen.findByRole('region', { name: `${WORKLOAD_AUTOSCALER} summary` });
    await waitFor(() => expect(card).toHaveTextContent('Off in this environment'));
    expect(card).toHaveTextContent("The Workload autoscaler hasn't been turned on for this XamOps environment.");
    expect(within(card).queryByRole('link', { name: REVIEW_SUGGESTIONS })).toBeNull();
  });

  it('says what each automation feature does and is doing now', async () => {
    renderPanel();
    const automation = await screen.findByRole('region', { name: AUTOMATION_TITLE });
    const feature = (name: string) => automation.querySelector(`[data-feature="${name}"]`) as HTMLElement;
    await waitFor(() => expect(feature(WORKLOAD_AUTOSCALER)).toHaveTextContent('On: suggestions only'));
    expect(feature(WORKLOAD_AUTOSCALER)).toHaveTextContent('9 workloads analysed · 2 over-provisioned · 3 need more resources');
    expect(within(feature(WORKLOAD_AUTOSCALER)).getByRole('link', { name: "Why can't I apply?" })).toBeInTheDocument();
    expect(feature(NODE_AUTOSCALER)).toHaveTextContent('Running: analysis only');
    expect(feature(NODE_AUTOSCALER)).toHaveTextContent('Karpenter detected');
    expect(feature('HPA')).toHaveTextContent('3 HPAs found');
    expect(feature('HPA')).toHaveTextContent('The Workload autoscaler leaves CPU or memory alone on 1 workload because an HPA scales on it.');
    expect(feature('VPA')).toHaveTextContent('Not reported by the automation agent');
  });

  it('lists what needs attention, XamOps facts first, each linking to the section that holds it', async () => {
    renderPanel();
    const attention = await screen.findByRole('region', { name: ATTENTION_TITLE });
    await waitFor(() => expect(attention.querySelector('[data-attention="throttled"]')).not.toBeNull());
    const items = [...attention.querySelectorAll('li[data-attention]')];
    expect(items[0]).toHaveTextContent('1 workload is held back by the CPU limit');
    expect(items[0]).toHaveTextContent('production/api-server is CPU throttled.');
    expect(items.length).toBeLessThanOrEqual(6);
    for (const item of items) {
      const target = resolved(item.querySelector('a')!.getAttribute('href')!);
      expect(target.corrected).toBeNull();
      // Nothing links back to this page: events live on Overview › Events now.
      expect(`${target.tab}/${target.sub}`).not.toBe('overview/summary');
    }
    const backOff = items.find((i) => i.textContent?.includes('BackOff'))!;
    expect(backOff.querySelector('[data-severity="high"]')).toHaveTextContent('High');
    expect(within(backOff as HTMLElement).getByRole('link')).toHaveTextContent('Overview › Events');
    expect(within(attention).getByRole('link', { name: VIEW_ALL_ALERTS })).toHaveAttribute('href', ALERTS_HREF);
  });

  it('keeps the dashboard cards when capabilities fail', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderPanel({ caps: new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }) });
    const sources = await screen.findByRole('region', { name: 'Data sources' });
    await waitFor(() => expect(sources).toHaveTextContent("Couldn't check data sources."));
    expect(region(NODES_TITLE)).toHaveTextContent(/^Nodes6/);
    expect(region(NODE_AUTOSCALER)).toHaveTextContent('Unknown');
  });

  it('keeps namespace utilisation, the top pods and the data sources below', async () => {
    renderPanel();
    const namespaces = await screen.findByRole('region', { name: 'Namespace utilisation' });
    expect(within(namespaces).getByRole('table', { name: 'Namespace utilisation' })).toBeInTheDocument();
    expect(region('Top CPU pods')).toHaveTextContent('prometheus-0');
    expect(region('Top CPU pods')).toHaveTextContent('Usage is reported for the top 20 pods only.');
    const sources = region('Data sources');
    await waitFor(() => expect(sources.querySelector('[data-source-type="k8s-resources"]')).toHaveTextContent('Kubernetes resources'));
    expect(sources.querySelector('[data-source-type="kubescape-compliance"]')).toHaveTextContent('received, not shown');
  });
});
