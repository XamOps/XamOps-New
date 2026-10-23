import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getVerticalStatus } = vi.hoisted(() => ({ getCapabilities: vi.fn(), getVerticalStatus: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../api/verticalRecommendationsApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/verticalRecommendationsApi')>();
  return { ...actual, getVerticalStatus };
});

import { K8sApiError } from '../../../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { EKS_SECTIONS, LEGACY_TAB_MAP, resolveSectionRoute, useEksSectionRoute } from '../../../../../hooks/useEksSectionRoute';
import { clearVerticalCacheForTests } from '../../../../../hooks/useVerticalRecommendations';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { demoCapabilities, demoVerticalStatus } from '../../../../../demo/k8sAutomationMockData';
import { demoEksDashboard, demoEksEmptyShellDashboard } from '../../../../../demo/xamopsMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import { CAPACITY_TERMS } from '../../../../CloudK8s/shared/CapacityDonut';
import { DASH } from '../../../../../utils/k8sQuantities';
import { NOT_MONITORED_TITLE } from '../../../../CloudK8s/shared/NotMonitoredPanel';
import SummaryPanel, {
  ALERTS_HREF, ATTENTION_TITLE, AUTOMATION_TITLE, CLUSTER_TITLE, COST_TITLE, NODES_TITLE, NODE_AUTOSCALER,
  PODS_TITLE, REVIEW_SUGGESTIONS, USED_NOT_REPORTED, VIEW_ALL_ALERTS, WORKLOAD_AUTOSCALER,
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

  it('rolls out the v3 system: PageTemplate, one hero MetricStrip row, and the kept sections', async () => {
    renderPanel();
    // FEAT-002: the page is a PageTemplate (one [data-k8s-redesigned-main] region) with a single
    // hero MetricStrip row. The duplicate Cluster / Nodes / Pods summary cards are GONE — the sticky
    // ClusterStatusStrip owns those vitals — so those regions no longer exist on this page.
    await screen.findByRole('table', { name: 'Namespace utilisation' });
    expect(document.querySelector('[data-k8s-redesigned-main]')).not.toBeNull();
    expect(document.querySelectorAll('[data-metric-strip-row]')).toHaveLength(1);
    expect(screen.queryByRole('region', { name: CLUSTER_TITLE })).toBeNull();
    expect(screen.queryByRole('region', { name: COST_TITLE })).toBeNull();
    // The kept sections stay labelled regions.
    for (const name of ['CPU', 'Memory', `${WORKLOAD_AUTOSCALER} summary`, AUTOMATION_TITLE, ATTENTION_TITLE]) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument();
    }
    // No time-series charts and no list-price dollars until their sources exist.
    expect(document.body).not.toHaveTextContent(/over time/i);
    expect(document.body).not.toHaveTextContent(/list price/i);
    expect(document.body).not.toHaveTextContent(/score/i);
    // The old 52px hero number is gone (the deprecated HERO_NUMBER was replaced by the type scale).
    const panel = document.querySelector('[data-panel="overview-summary"]')!;
    const heroes = [...panel.querySelectorAll('[class]')].filter((el) => (el.getAttribute('class') ?? '').includes('text-[52px]'));
    expect(heroes).toHaveLength(0);
  });

  it('leads the hero MetricStrip with the node, pod and namespace totals', async () => {
    renderPanel();
    await screen.findByRole('table', { name: 'Namespace utilisation' });
    // FEAT-002: the Nodes / Pods counts now live in the hero MetricStrip (and the sticky
    // ClusterStatusStrip), not in a duplicate summary card. 6 nodes, 18 pods, 8 namespaces.
    expect(document.querySelector('[data-kpi="nodes"]')).toHaveTextContent('6');
    expect(document.querySelector('[data-kpi="pods"]')).toHaveTextContent('18');
    expect(document.querySelector('[data-kpi="namespaces"]')).toHaveTextContent('8');
    // The removed summary cards' detail is no longer on this page (it is owned elsewhere).
    expect(screen.queryByRole('region', { name: NODES_TITLE })).toBeNull();
    expect(screen.queryByRole('region', { name: PODS_TITLE })).toBeNull();
  });

  it('calls a pod no node can fit out in Needs attention, not twice with the scheduler event', async () => {
    renderPanel({
      dashboard: clone((d) => {
        const pod = d.k8sPods.find((p: any) => p.name === 'data-pipeline-pending-1');
        pod.unschedulable = true;
        pod.containers[0].cpuRequest = '32';
        pod.containers[0].memoryRequest = '64Gi';
      }),
    });
    const attention = await screen.findByRole('region', { name: ATTENTION_TITLE });
    const item = attention.querySelector('[data-attention="unschedulable"]')!;
    expect(item).toHaveTextContent("1 pod can't be scheduled");
    expect(item).toHaveTextContent('no node is that large.');
    // The pending pods stay reachable through the attention item's deep link.
    const target = resolved(item.querySelector('a')!.getAttribute('href')!);
    expect([target.tab, target.sub, target.corrected]).toEqual(['workloads', 'pods', null]);
    expect(target.params.get('phase')).toBe('Pending');
    // The scheduler event that says the same thing isn't listed twice.
    expect(attention).not.toHaveTextContent('FailedScheduling');
  });

  it('shows cost as the cost source reports it in the hero strip, in whole dollars', async () => {
    renderPanel();
    // FEAT-002: cost is the hero MetricStrip cell; the sub-line folds "Today $X · from {source}".
    await screen.findByRole('table', { name: 'Namespace utilisation' });
    const strip = document.querySelector('[data-metric-strip-row]')!;
    expect(strip.querySelector('[data-hero-cost]')).toHaveTextContent('$1,480/mo');
    expect(strip).toHaveTextContent('Today $49');
    expect(strip).toHaveTextContent('from XamOps cost exporter (Prometheus)');
  });

  // BUG A: the Summary hero Monthly cost must show the SAME real cluster cost the Automation ›
  // Overview hero shows (from the Workload-autoscaler cost roll-up), even when the agent-dashboard
  // cost source reports nothing. The demo roll-up prices the cluster at $47.52/mo → $48.
  it('shows the roll-up cluster monthly cost when the dashboard has no cost source (matches Automation)', async () => {
    renderPanel({ dashboard: clone((d) => { delete d.agentCostMetrics; delete d.openCost; delete d.costMetrics; }) });
    const strip = await waitFor(() => {
      const s = document.querySelector('[data-metric-strip-row]');
      expect(s?.querySelector('[data-hero-cost="rollup"]')).not.toBeNull();
      return s!;
    });
    expect(strip.querySelector('[data-hero-cost="rollup"]')).toHaveTextContent('$48/mo');
    expect(strip).toHaveTextContent('from the Workload autoscaler cost roll-up');
    expect(strip).not.toHaveTextContent('$0');
  });

  it('never shows $0 for a cluster without a cost source: falls back to freed capacity', async () => {
    // FEAT-002: with no priced cost source ANYWHERE (neither the agent dashboard nor the roll-up)
    // the hero strip shows the honest freed-capacity figure (the demo engine is over-provisioned),
    // never a fabricated $0.
    renderPanel({
      dashboard: clone((d) => { delete d.agentCostMetrics; delete d.openCost; delete d.costMetrics; }),
      // No roll-up cluster cost either, so there is genuinely no priced source anywhere.
      engine: { ...demoVerticalStatus(PROD), cost: { currency: 'USD', hoursPerMonth: 730, monthlySavings: null, monthlySavingsUnavailableReason: 'UNKNOWN', clusterMonthlyCost: null, pricedWorkloads: 0, unpricedWorkloads: 3 } },
    });
    const strip = await waitFor(() => {
      const s = document.querySelector('[data-metric-strip-row]');
      expect(s?.querySelector('[data-hero-freed]')).not.toBeNull();
      return s!;
    });
    expect(strip.querySelector('[data-hero-freed]')).toHaveTextContent(/Could free/);
    expect(strip).toHaveTextContent('no priced cost source for this cluster');
    expect(strip).not.toHaveTextContent('$0');
    expect(strip.querySelector('[data-hero-cost]')).toBeNull();
  });

  it('shows a plain dash when there is neither a cost source nor any freed capacity', async () => {
    // No cost source AND the engine read returns no workloads → no freed figure, so the hero is a
    // plain "$—" with the honest reason, still never a fabricated $0.
    renderPanel({
      dashboard: clone((d) => { delete d.agentCostMetrics; delete d.openCost; delete d.costMetrics; }),
      // No workloads AND no roll-up cluster cost, so there is neither a priceable figure nor freed
      // capacity: the hero is the honest "$—".
      engine: { ...demoVerticalStatus(PROD), workloads: [], cost: { currency: 'USD', hoursPerMonth: 730, monthlySavings: null, monthlySavingsUnavailableReason: 'UNKNOWN', clusterMonthlyCost: null, pricedWorkloads: 0, unpricedWorkloads: 0 } },
    });
    const strip = await waitFor(() => {
      const s = document.querySelector('[data-metric-strip-row]');
      expect(s?.querySelector('[data-hero-dash]')).not.toBeNull();
      return s!;
    });
    expect(strip.querySelector('[data-hero-dash]')).toHaveTextContent('$—');
    expect(strip).toHaveTextContent('No cost source reported for this cluster.');
    expect(strip).not.toHaveTextContent('$0');
  });

  it('draws CPU from node allocatable, pod requests and Prometheus as a CapacityBar', async () => {
    renderPanel();
    const cpu = await screen.findByRole('region', { name: 'CPU' });
    const bar = cpu.querySelector('[data-capacity-bar="CPU"]')!;
    // The CapacityBar shows Requested / Used / Allocatable: 25.52 allocatable, 2143m requested,
    // 12.48 used; each term carries the glossary definition as its tooltip.
    expect(bar).toHaveTextContent('Requested2.14 vCPU');
    expect(bar).toHaveTextContent('Used12.48 vCPU');
    expect(bar).toHaveTextContent('Allocatable25.52 vCPU');
    const requestedTerm = [...bar.querySelectorAll('span')].find((el) => el.textContent === 'Requested')!;
    expect(requestedTerm).toHaveAttribute('title', CAPACITY_TERMS.requested);
    const memory = screen.getByRole('region', { name: 'Memory' });
    expect(memory.querySelector('[data-capacity-bar="Memory"]')).toHaveTextContent('Used40 GiB');
  });

  it('shows used as — with the missing source, never 0, when Prometheus reported nothing', async () => {
    renderPanel({ dashboard: clone((d) => { d.clusterOverview.cpuUsagePercent = 0; d.clusterOverview.memoryUsagePercent = 0; delete d.nodeUtilization; }) });
    const cpu = await screen.findByRole('region', { name: 'CPU' });
    const bar = cpu.querySelector('[data-capacity-bar="CPU"]')!;
    const usedTerm = [...bar.querySelectorAll('span')].find((el) => el.textContent === 'Used')!;
    // The Used figure sits next to its term; when Prometheus reported nothing it is "—" with the
    // missing-source tooltip, never a 0.
    const usedValue = usedTerm.nextElementSibling!;
    expect(usedValue).toHaveTextContent(DASH);
    expect(usedValue).toHaveAttribute('title', USED_NOT_REPORTED);
  });

  it("splits the Workload autoscaler's workloads into the glossary's buckets, in order, and links to its suggestions", async () => {
    renderPanel();
    const card = await screen.findByRole('region', { name: `${WORKLOAD_AUTOSCALER} summary` });
    await waitFor(() => expect(card.querySelectorAll('[data-bucket]').length).toBeGreaterThan(0));
    const buckets = [...card.querySelectorAll('[data-bucket]')].map((b) => `${(b as HTMLElement).dataset.bucket}=${b.querySelector('dd')!.textContent}`);
    expect(buckets).toEqual(['Over-provisioned=2', 'Needs more resources=3', 'Right-sized=1', 'Left alone=1', 'Skipped=2']);
    expect(card).toHaveTextContent('9 workloads analysed · 3 need more resources');
    // FEAT-002: the demo now carries a cost roll-up ($6.84/mo saved, 2 of 3 priced), so the money
    // figure leads via Delta and the freed-capacity figure is no longer the headline.
    const savings = card.querySelector('[data-money-savings]')!;
    expect(savings).toHaveTextContent('$7/mo'); // formatMoney renders whole dollars above $1 (6.84 → $7)
    expect(savings).toHaveTextContent('saved');
    expect(card.querySelector('[data-priced-line]')).toHaveTextContent('2 of 3 priced');
    expect(card.querySelector('[data-freed]')).toBeNull();
    const target = resolved(within(card).getByRole('link', { name: REVIEW_SUGGESTIONS }).getAttribute('href')!);
    expect([target.tab, target.sub, target.corrected]).toEqual(['automation', 'workload-autoscaler', null]);
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
    expect(feature('VPA')).toHaveTextContent('Not reported by the node automation agent');
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

  it("lists the status strip's attention signals, such as a node that isn't ready", async () => {
    renderPanel({ dashboard: clone((d) => { d.k8sNodes[0].conditions[0].status = 'False'; }) });
    const attention = await screen.findByRole('region', { name: ATTENTION_TITLE });
    const item = attention.querySelector('[data-attention="signal-nodes-ready"]')!;
    expect(item).toHaveTextContent('Nodes ready: 5 / 6');
    expect(item).toHaveTextContent('1 node not ready');
    const target = resolved(item.querySelector('a')!.getAttribute('href')!);
    expect([target.tab, target.sub, target.corrected]).toEqual(['nodes', 'nodes', null]);
  });

  it('keeps the dashboard cards when capabilities fail', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderPanel({ caps: new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }) });
    const sources = await screen.findByRole('region', { name: 'Data sources' });
    await waitFor(() => expect(sources).toHaveTextContent("Couldn't check data sources."));
    expect(document.querySelector('[data-kpi="nodes"]')).toHaveTextContent('6');
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

  describe('v3 re-skin (FEAT-003)', () => {
    it('opens with the Now banner naming the cluster once the engine loads, with no fabricated money', async () => {
      renderPanel();
      // The engine read (endpoint 1) resolves asynchronously; once it does the sentence names the
      // cluster. Before it resolves the banner shows the honest "couldn't read" clause.
      const banner = await waitFor(() => {
        const el = document.querySelector('[data-now-banner]');
        if (!el || !el.textContent?.includes(PROD)) throw new Error('banner not resolved');
        return el;
      });
      expect(banner.textContent).toContain(PROD);
      // Savings need BE-4 (not built), so a dollar figure never appears in the banner.
      expect(banner.textContent).not.toMatch(/\$\d|\/mo/);
    });
  });
});
