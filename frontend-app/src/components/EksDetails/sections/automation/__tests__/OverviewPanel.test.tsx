import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../utils/apiClient';
import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { CONTRACT_BLOCK_MESSAGE } from '../../../../../api/verticalRecommendationsAdapter';
import { DEMO_READINESS_MESSAGES, demoCapabilities } from '../../../../../demo/k8sAutomationMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../types/verticalRightsizer';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { OVERLAY_TEXT } from '../../../../CloudK8s/shared/AccessBanner';
import { NOT_TURNED_ON_TEXT, OverviewPanel, TODAY_ROWS, WORKLOAD_AUTOSCALER_LINK, type OverviewHrefs } from '../OverviewPanel';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const READ_ONLY_TEXT = 'Read-only connection. Automation changes need read-write access.';
const DEMO_TEXT = "Demo workspace. Changes aren't saved.";

const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
const res = (cpuRequestM: number | null, memRequestMi: number | null) => ({ cpuRequestM, memRequestMi, cpuLimitM: null, memLimitMi: null });

function wire(name: string, namespace: string, over: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire {
  return {
    ref: { namespace, kind: 'Deployment', name },
    policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: false,
    skipReason: null,
    findings: [],
    containers: [],
    lastAction: null,
    autoApplyEligible: false,
    provenance: { percentile: 95, windowHours: 24, windowStart: null, windowEnd: null, sampleCoverage: 0.94, usageSource: 'prometheus-proxy', policySource: 'DEFAULTS' },
    horizontal: null,
    ...over,
  };
}

/** Three workloads like xamops-test-1's: one to shrink, one throttled, one applied 17 minutes ago. */
const ENDPOINT_1: VerticalEngineResponseWire = {
  engine: {
    enabled: true,
    ready: true,
    clusterAccess: 'CONFIGURED',
    executionPath: 'DIRECT',
    executionPathDetail: { direct: { available: true, reason: null }, agent: null },
    metricsSource: { reachable: true, name: 'prometheus-proxy' },
    lastCycle: { at: iso(5 * 60_000), evaluated: 3, error: null },
  },
  gateReasons: [],
  writeGatesChecked: true,
  workloads: [
    wire('overprovisioned-api', 'team-a', {
      containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024), recommended: res(25, 256), reasons: [], upstreamVpa: null }],
    }),
    wire('throttled-encoder', 'prod-sim', {
      findings: ['THROTTLED'],
      containers: [{ name: 'encoder', qosClass: 'Burstable', current: res(10, 64), recommended: res(63, 64), reasons: ['DEMAND_CENSORED_BY_LIMIT'], upstreamVpa: null }],
    }),
    wire('idle-batch-listener', 'dev-sim', {
      replicas: 3,
      containers: [{ name: 'sleeper', qosClass: 'Burstable', current: res(25, 128), recommended: null, reasons: ['COOLDOWN'], upstreamVpa: null }],
      lastAction: { at: iso(17 * 60_000), dryRun: false, outcome: 'APPLIED' },
    }),
  ],
};

const DASHBOARD = {
  dashboard: {
    k8sPods: [
      { name: 'api-1', namespace: 'team-a', phase: 'Running', containers: [{ name: 'api', cpuRequest: '1000m', memoryRequest: '1Gi' }] },
      { name: 'api-2', namespace: 'team-a', phase: 'Running', containers: [{ name: 'api', cpuRequest: '1000m', memoryRequest: '1Gi' }] },
    ],
    namespaceUtilization: [{ namespace: 'team-a', cpuUsageMillicores: 40, memoryUsageMB: 300 }],
    k8sHpas: [{ name: 'php-apache', namespace: 'team-b', minReplicas: 1, maxReplicas: 5, currentReplicas: 5 }],
  },
} as unknown as UseEksDashboardReturn;

const json = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => (body === null ? '' : JSON.stringify(body)) }) as unknown as Response;

/** Serves endpoint 1; anything else is a test failure. */
function serve(status = 200, body: unknown = ENDPOINT_1) {
  mockedFetch.mockImplementation(async (url: string) => {
    if (!String(url).startsWith('/api/eks/automation/vertical/')) throw new Error(`unexpected request ${url}`);
    return json(status, body);
  });
}

const endpointOneGets = () =>
  mockedFetch.mock.calls.filter(([url, init]) => String(url).startsWith('/api/eks/automation/vertical/') && (init?.method ?? 'GET') === 'GET');

function fixture(accountId: 'demo-aws-001' | 'demo-aws-002', edit?: (dto: CapabilitiesDto) => void): CapabilitiesDto {
  const dto = demoCapabilities(accountId)!;
  edit?.(dto);
  return dto;
}

async function renderPanel(
  dto: CapabilitiesDto,
  cluster = 'prod-eks-cluster',
  { hrefs, eksData }: { hrefs?: OverviewHrefs; eksData?: UseEksDashboardReturn } = {},
) {
  getCapabilities.mockResolvedValue(dto);
  render(
    <MemoryRouter initialEntries={[`/eks-details?name=${cluster}&tab=automation&sub=overview`]}>
      <K8sCapabilitiesProvider accountId={dto.accountId} demo>
        <OverviewPanel clusterName={cluster} accountId={dto.accountId} region="us-east-1" eksData={eksData} hrefs={hrefs} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  // The Access block replaces "Checking automation access…" once capabilities load.
  await screen.findAllByText(/^(Read-write|Read-only)$/);
  return screen.getByRole('table', { name: 'What automation can do on this cluster today' });
}

const rowOf = (table: HTMLElement, id: string) => {
  const row = table.querySelector<HTMLElement>(`[data-row="${id}"]`);
  if (!row) throw new Error(`no row ${id}`);
  return row;
};

/** The Access block's value for one term. */
const accessValue = (term: string) => {
  const dt = [...document.querySelectorAll('dt')].find((el) => el.textContent === term);
  if (!dt?.nextElementSibling) throw new Error(`no access row ${term}`);
  return dt.nextElementSibling as HTMLElement;
};

const card = (title: string) => {
  const heading = screen.getByRole('heading', { name: title });
  return heading.closest('section') as HTMLElement;
};

const kpi = (label: string) => {
  const el = [...document.querySelectorAll<HTMLElement>('[data-kpi-tone]')].find((k) => k.textContent?.includes(label.toUpperCase()) || k.textContent?.includes(label));
  if (!el) throw new Error(`no KPI ${label}`);
  return el;
};

describe('Automation › Overview (§9.1)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    mockedFetch.mockReset();
    serve();
  });
  afterEach(() => vi.restoreAllMocks());

  it('lists every §9.1 capability in order', async () => {
    const table = await renderPanel(fixture('demo-aws-001'));
    const labels = [...table.querySelectorAll('tbody th')].map((th) => th.textContent);
    expect(labels).toEqual(TODAY_ROWS.map((r) => r.label));
  });

  it('read-write fixture: each row carries its readiness reason, and nothing else blocks it', async () => {
    const table = await renderPanel(fixture('demo-aws-001'));

    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).toHaveTextContent('Recommendations');
    // NIT 8: the workload row reads can('vertical.apply')'s primary message, the contract block.
    expect(workload).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);

    expect(rowOf(table, 'node-consolidation')).toHaveTextContent(DEMO_READINESS_MESSAGES['evictor-live']);
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent('Analysis not yet reliable');
    expect(rowOf(table, 'node-optimizer')).toHaveTextContent(DEMO_READINESS_MESSAGES['node-optimizer-actions']);
    expect(rowOf(table, 'rebalancing')).toHaveTextContent(DEMO_READINESS_MESSAGES.rebalancer);
    expect(rowOf(table, 'hpa-manager')).toHaveTextContent(DEMO_READINESS_MESSAGES['hpa-apply']);
    expect(rowOf(table, 'scale-out')).toHaveTextContent(DEMO_READINESS_MESSAGES['scale-out']);
    expect(rowOf(table, 'node-templates')).toHaveTextContent(DEMO_READINESS_MESSAGES['node-templates']);
    expect(rowOf(table, 'vpa-baseline')).toHaveTextContent(DEMO_READINESS_MESSAGES['vpa-apply']);
    // No readiness key: only the demo session holds it.
    expect(rowOf(table, 'spot-interruption')).toHaveTextContent(DEMO_TEXT);

    expect(within(table).queryByText(/Also needs/)).toBeNull();
    expect(within(table).queryByText(READ_ONLY_TEXT)).toBeNull();
    // Every blocked cell is marked by a lock as well as its words (Iconify draws it after mount).
    await waitFor(() => expect(table.querySelectorAll('[data-gated-lock]')).toHaveLength(TODAY_ROWS.length));

    expect(accessValue('Connection')).toHaveTextContent('Read-write');
    expect(accessValue('Your permission')).toHaveTextContent('You can change automation');
    expect(accessValue('XamOps agent')).toHaveTextContent('Connected');
    expect(accessValue('XamOps agent')).toHaveTextContent('data 4 min old');

    const overlays = [...document.querySelectorAll('[data-overlay]')].map((el) => el.getAttribute('data-overlay'));
    expect(overlays).toEqual(['demo']);
  });

  it('read-only fixture: the read-only banner, and the connection named wherever it blocks', async () => {
    const user = userEvent.setup();
    const table = await renderPanel(fixture('demo-aws-002'));

    expect(screen.getByText(OVERLAY_TEXT['read-only'])).toBeInTheDocument();

    const connection = accessValue('Connection');
    expect(connection).toHaveTextContent('Read-only');
    expect(connection).toHaveTextContent(READ_ONLY_TEXT);
    const toggle = within(connection).getByRole('button', { name: 'Get read-write access' });
    const explainer = document.getElementById(toggle.getAttribute('aria-controls')!)!;
    // Collapsed for real: no display class that would override the hidden attribute.
    expect(explainer).toHaveAttribute('hidden');
    expect(explainer).toHaveClass('hidden');
    expect(explainer).not.toHaveClass('block');
    await user.click(toggle);
    expect(explainer).not.toHaveAttribute('hidden');
    expect(explainer).toHaveClass('block');
    expect(within(connection).getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/tickets');

    // Readiness ranks above the connection, so it stays primary and the connection is listed after it.
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent(DEMO_READINESS_MESSAGES['evictor-live']);
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent('Also needs: read-write access');
    expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent('Also needs: read-write access');
    // Where nothing ranks higher, the connection is the reason.
    expect(rowOf(table, 'spot-interruption')).toHaveTextContent(READ_ONLY_TEXT);
    expect(rowOf(table, 'spot-interruption')).not.toHaveTextContent(DEMO_TEXT);
  });

  it('reads "Not turned on in this environment" for workloads while the vertical engine is off (V7)', async () => {
    const table = await renderPanel(fixture('demo-aws-001', (dto) => { dto.verticalEngine.enabled = false; }));
    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).toHaveTextContent(NOT_TURNED_ON_TEXT);
    expect(workload).not.toHaveTextContent('Recommendations');
    expect(workload).not.toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
  });

  it('points the workloads row at the hosted Workload autoscaler, not the contract block (review S2)', async () => {
    const href = '/eks-details?name=prod-eks-cluster&tab=automation&sub=workload-autoscaler';
    const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs: { workloadAutoscaler: href } });
    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).not.toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    expect(workload).toHaveTextContent('Decided per workload in Automation › Workload autoscaler');
    expect(within(workload).getByRole('link', { name: WORKLOAD_AUTOSCALER_LINK })).toHaveAttribute('href', href);
    // The node rows keep their own capability.
    expect(rowOf(table, 'node-consolidation').querySelector('a')).toBeNull();
  });

  it.each([
    ['TAB_GRANT', 'View only', 'Ask your XamOps administrator to allow automation changes for your user.'],
    ['ROLE', "View only: your role can't change automation", null],
    ['IMPERSONATION', 'View only while signed in as another user', null],
  ])('names the %s permission state', async (detail, label, remedy) => {
    await renderPanel(fixture('demo-aws-001', (dto) => {
      dto.user = { canChangeAutomation: false, reasons: ['NO_USER_PERMISSION'], detail };
    }));
    const permission = accessValue('Your permission');
    expect(permission).toHaveTextContent(label);
    if (remedy) expect(permission).toHaveTextContent(remedy);
  });

  it('shows a cluster capabilities does not list as not monitored, with the setup path', async () => {
    await renderPanel(fixture('demo-aws-001'), 'staging-eks-cluster');
    const agent = accessValue('XamOps agent');
    expect(agent).toHaveTextContent('Not installed');
    expect(within(agent).getByRole('link', { name: 'Set up monitoring' })).toBeInTheDocument();
  });

  it('reads endpoint 1 exactly once (one GET), and makes no other request', async () => {
    await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { eksData: DASHBOARD });
    await screen.findByText(/XamOps is watching 3 workloads/);
    expect(getCapabilities).toHaveBeenCalledTimes(1);
    expect(mockedFetch).toHaveBeenCalledTimes(1);
    expect(endpointOneGets()).toHaveLength(1);
    expect(String(mockedFetch.mock.calls[0][0])).toBe('/api/eks/automation/vertical/prod-eks-cluster?accountId=demo-aws-001&region=us-east-1');
  });

  describe('v3 visuals from endpoint 1, the agent and capabilities (FEAT-002)', () => {
    it('adds the Now banner, the How-it-works flow and the feature marks', async () => {
      await renderPanel(fixture('demo-aws-001'));
      expect(document.querySelector('[data-now-banner]')).not.toBeNull();
      expect(screen.getByText('How the Workload autoscaler works')).toBeInTheDocument();
      // The feature accent reaches the page only through FeatureMark, paired with its name.
      for (const f of ['workload', 'hpa', 'vpa', 'node']) {
        expect(document.querySelectorAll(`[data-feature="${f}"]`).length, f).toBeGreaterThan(0);
      }
    });

    it('fills the Now sentence from endpoint 1, in freed capacity and never in dollars', async () => {
      await renderPanel(fixture('demo-aws-001'));
      const banner = await waitFor(() => {
        const el = document.querySelector('[data-now-banner]')!;
        expect(el.textContent).toContain('XamOps is watching 3 workloads on prod-eks-cluster.');
        return el;
      });
      expect(banner.textContent).toContain('1 can be resized now, freeing 1.95 vCPU and 1.5 GiB.');
      expect(banner.textContent).toContain('1 needs more CPU.');
      expect(banner.textContent).toContain('Nothing changes until you click Apply.');
      expect(banner.textContent).toMatch(/Last change: .+, idle-batch-listener: applied\./);
      expect(banner.textContent).not.toMatch(/\$/);
    });

    it('shows the KPIs from the fixture: applied live, can save, needs more, changes in 7 days', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-overview-kpis]')).not.toBeNull());
      // lastAction has no before and after, so a raise would count too: "Applied live", never "Saving now".
      const applied = kpi('Applied live');
      expect(applied).toHaveTextContent('1');
      expect(applied).toHaveTextContent('whose latest change was applied live');
      expect(applied).toHaveTextContent('Before and after: —, recorded once the activity feed is on');
      expect(document.querySelector('[data-overview-kpis]')).not.toHaveTextContent(/saving now/i);
      expect(kpi('Can save now')).toHaveTextContent('1.95 vCPU');
      expect(kpi('Can save now')).toHaveTextContent('1.5 GiB memory');
      expect(kpi('Needs more')).toHaveTextContent('+0.11 vCPU added if applied');
      const changes = kpi('Changes, last 7 days');
      expect(changes).toHaveTextContent('latest change per workload');
      expect(changes).toHaveTextContent('Applied: 1');
    });

    it('lights How it works from this cluster: suggestions waiting, the confirmed change and the wait', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-how-step="decide"]')).toHaveTextContent('2 suggestions'));
      expect(document.querySelector('[data-how-step="measure"]')).toHaveTextContent('Up to date');
      expect(document.querySelector('[data-how-step="apply"]')).toHaveTextContent('Waiting for you');
      expect(document.querySelector('[data-how-step="verify"]')).toHaveTextContent('confirmed');
      expect(document.querySelector('[data-how-step="watch"]')).toHaveTextContent(/Until /);
    });

    it('links How it works to Policies in place', async () => {
      const policies = '/eks-details?name=prod-eks-cluster&tab=automation&sub=workload-autoscaler-policies';
      await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs: { policies } });
      expect(screen.getByRole('link', { name: 'Exact rules: Workload autoscaler › Policies' })).toHaveAttribute('href', policies);
    });

    it('places each workload\'s latest change on the 7-day timeline, saying it shows only the latest', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-lanes-timeline]')).not.toBeNull());
      const timeline = card('What automation did, last 7 days');
      expect(timeline).toHaveTextContent('Shows only the latest change XamOps recorded for each workload.');
      expect(timeline.querySelectorAll('[data-lane]')).toHaveLength(1);
      expect(within(timeline).getByRole('rowheader', { name: 'idle-batch-listener', hidden: true })).toBeInTheDocument();
    });

    it('builds the feature cards from real sources, each with its next step', async () => {
      const hrefs = { workloadAutoscaler: '/w', hpa: '/h', vpa: '/v', nodeAutoscaler: '/n' };
      await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs, eksData: DASHBOARD });
      const cards = () => Object.fromEntries([...document.querySelectorAll<HTMLElement>('[data-feature-card]')].map((c) => [c.dataset.featureCard, c]));
      await waitFor(() => expect(cards().workload).toHaveTextContent('On: you apply suggestions'));
      expect(cards().hpa).toHaveTextContent('1 HPA, at their maximum');
      expect(cards().vpa).toHaveTextContent('No VPAs found');
      expect(within(cards().workload).getByRole('link', { name: 'Open Workloads' })).toHaveAttribute('href', '/w');
      expect(within(cards().node).getByRole('link', { name: 'Open Node autoscaler' })).toHaveAttribute('href', '/n');
    });

    it('points the capability table\'s HPA and VPA rows at the pages that show them, not "Not shown yet"', async () => {
      const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs: { hpa: '/h', vpa: '/v' } });
      const hpa = rowOf(table, 'hpa-manager');
      expect(hpa).toHaveTextContent("Each HPA's limits, flags and conflicts");
      expect(within(hpa).getByRole('link', { name: 'Automation › HPA' })).toHaveAttribute('href', '/h');
      const vpa = rowOf(table, 'vpa-baseline');
      expect(vpa).toHaveTextContent("Each Kubernetes VPA's target, next to XamOps's suggestion");
      expect(within(vpa).getByRole('link', { name: 'Automation › VPA' })).toHaveAttribute('href', '/v');
      expect(table).not.toHaveTextContent(/Not shown yet|display coming/);
    });

    it('compares requested, used and suggested by namespace, with a memory toggle', async () => {
      const user = userEvent.setup();
      await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { eksData: DASHBOARD });
      await waitFor(() => expect(screen.getByRole('heading', { name: 'CPU by namespace: requested, used and suggested' })).toBeInTheDocument());
      const chart = card('CPU by namespace: requested, used and suggested');
      const teamA = within(chart).getByRole('rowheader', { name: 'team-a', hidden: true }).closest('tr')!;
      // 2 × 1000m requested, 40m used, and 2 × 25m with the suggestion applied.
      expect([...teamA.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['2.00 vCPU', '0.04 vCPU', '0.05 vCPU']);
      await user.click(within(chart).getByRole('button', { name: 'Memory' }));
      expect(screen.getByRole('heading', { name: 'Memory by namespace: requested, used and suggested' })).toBeInTheDocument();
    });

    it('shows the honest empty states: no agent pods, and history waiting for the usage feed', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(card('CPU by namespace: requested, used and suggested')).toHaveTextContent("The XamOps agent hasn't reported this cluster's pods"));
      const overTime = card('CPU and memory over time');
      expect(overTime).toHaveTextContent('History arrives with the usage feed');
      expect(overTime.querySelector('svg.recharts-surface')).toBeNull();
    });

    it('when endpoint 1 fails: each endpoint-1 panel says so, and capabilities still render', async () => {
      const user = userEvent.setup();
      serve(500, { code: 'K8S_INTERNAL', message: 'Something went wrong on our side.' });
      const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { eksData: DASHBOARD });
      await waitFor(() => expect(document.querySelector('[data-read-failure]')).not.toBeNull());
      const banner = document.querySelector('[data-now-banner]')!;
      // No endpoint-1 answer, so a count is never invented.
      expect(banner.textContent).toContain("couldn't read its workloads");
      expect(banner.textContent).not.toMatch(/\d+ workloads|\$/);
      expect(document.querySelector('[data-read-failure]')).toHaveTextContent('Something went wrong on our side.');
      expect(document.querySelector('[data-overview-kpis]')).toBeNull();
      expect(card('What automation did, last 7 days')).toHaveTextContent("Couldn't read the Workload autoscaler, so there is nothing to place.");
      expect(card('CPU by namespace: requested, used and suggested')).toHaveTextContent("Couldn't read the Workload autoscaler, so the analysed namespaces aren't known.");
      expect(document.querySelector('[data-feature-card="workload"]')).toHaveTextContent('Not checked');
      // The agent's HPAs don't depend on endpoint 1.
      expect(document.querySelector('[data-feature-card="hpa"]')).toHaveTextContent('1 HPA, at their maximum');
      expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent('Recommendations');

      serve();
      await user.click(within(document.querySelector<HTMLElement>('[data-read-failure]')!).getByRole('button', { name: 'Retry' }));
      await screen.findByText(/XamOps is watching 3 workloads/);
      expect(endpointOneGets()).toHaveLength(2);
    });

    it("names an unreachable cluster on the Workload autoscaler card", async () => {
      serve(403, { code: 'K8S_CLUSTER_UNREACHABLE', message: "The cluster's API server didn't answer." });
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-feature-card="workload"]')).toHaveTextContent("Can't reach the cluster"));
      expect(document.querySelector('[data-read-failure]')).toHaveTextContent("XamOps can't reach this cluster's API server");
    });
  });
});
