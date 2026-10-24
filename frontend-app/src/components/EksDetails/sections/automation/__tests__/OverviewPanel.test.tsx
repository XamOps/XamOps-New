import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../utils/apiClient', () => {
  const fetch = vi.fn();
  // k8sAutomationApi (the Node autoscaler reads FEAT-003 uses) calls the NAMED `apiFetch`, while the
  // vertical/activity/usage reads call the default `fetch` — route both to the same mock so every
  // read in the Overview is served by one `mockedFetch` implementation.
  return { default: { fetch }, apiFetch: (url: string, init?: RequestInit) => fetch(url, init) };
});

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

type Answer = { status: number; body: unknown } | ((url: URL) => { status: number; body: unknown });

/** A backend without a Phase-2 endpoint: the dispatcher's untyped 404. */
const NOT_DEPLOYED = { status: 404, body: null };

/**
 * Serves endpoint 1 and the Phase-2 reads (FEAT-004/005/007): by default a backend without them (an
 * untyped 404, Phase 1's view); a write, or any other request, is a test failure.
 */
/** A node fleet with no interrupted nodes: the default the Node autoscaler reads expect. */
const NODE_FLEET_EMPTY = {
  status: 200,
  body: { totalNodes: 3, onDemandNodes: 2, spotNodes: 1, idleNodes: [], interruptedNodes: [], collectedAt: iso(4 * 60_000) },
};

function serve(
  status = 200,
  body: unknown = ENDPOINT_1,
  { feed = NOT_DEPLOYED, summary = NOT_DEPLOYED, usage = NOT_DEPLOYED, fleet = NODE_FLEET_EMPTY }: { feed?: Answer; summary?: Answer; usage?: Answer; fleet?: Answer } = {},
) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') !== 'GET') throw new Error(`unexpected write ${url}`);
    const u = new URL(String(url), 'http://test');
    const pick = (a: Answer) => (typeof a === 'function' ? a(u) : a);
    if (u.pathname.startsWith('/api/eks/automation/vertical/')) return json(status, body);
    if (/\/api\/eks\/automation\/activity\/[^/]+\/summary$/.test(u.pathname)) {
      const a = pick(summary);
      return json(a.status, a.body);
    }
    if (u.pathname.startsWith('/api/eks/automation/activity/')) {
      const a = pick(feed);
      return json(a.status, a.body);
    }
    if (u.pathname.startsWith('/api/eks/automation/usage/')) {
      const a = pick(usage);
      return json(a.status, a.body);
    }
    // The Node autoscaler reads (FEAT-003 surfaces the node-fleet's interruptedNodes). The fleet
    // answer is configurable; the dashboard and rebalance reads just answer so nothing throws.
    if (/\/api\/eks\/autoscaling\/[^/]+\/node-fleet$/.test(u.pathname)) {
      const a = pick(fleet);
      return json(a.status, a.body);
    }
    if (/\/api\/eks\/autoscaling\/[^/]+\/rebalance-status$/.test(u.pathname)) return json(200, { status: 'idle' });
    if (/\/api\/eks\/autoscaling\/[^/]+$/.test(u.pathname)) return json(404, null);
    throw new Error(`unexpected request ${url}`);
  });
}

const endpointOneGets = () =>
  mockedFetch.mock.calls.filter(([url, init]) => String(url).startsWith('/api/eks/automation/vertical/') && (init?.method ?? 'GET') === 'GET');
const getsTo = (pattern: RegExp) => mockedFetch.mock.calls.map(([url]) => new URL(String(url), 'http://test')).filter((u) => pattern.test(u.pathname));

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

/** A capability row in the DataTable, found by its visible Capability label (no more data-row). */
const rowOf = (table: HTMLElement, id: string) => {
  const label = TODAY_ROWS.find((r) => r.id === id)?.label;
  if (!label) throw new Error(`unknown row id ${id}`);
  const row = [...table.querySelectorAll<HTMLElement>('tbody tr[role="button"]')].find((tr) => tr.textContent?.includes(label));
  if (!row) throw new Error(`no row ${id}`);
  return row;
};

/** Open a capability row's DetailDrawer and return the dialog element. */
const openDrawer = async (table: HTMLElement, id: string) => {
  const user = userEvent.setup();
  await user.click(rowOf(table, id));
  return screen.getByRole('dialog');
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

/**
 * A MetricStrip cell in the Overview summary, found by its LABEL caption (replaces the KpiCard).
 * Cells are tagged `data-metric-cell` ('hero' for the dominant lead, 'strip' for its peers — owner
 * review #3), so selecting by that attribute is stable whether or not the lead is the hero; the
 * lead cell no longer carries `min-w-0` (it is `shrink-0`), so the old class-based selector missed it.
 */
const kpi = (label: string) => {
  const strip = document.querySelector<HTMLElement>('[data-overview-kpis]');
  if (!strip) throw new Error('no metric strip');
  // Each cell is a flex column whose first child is the LABEL caption.
  const cell = [...strip.querySelectorAll<HTMLElement>('[data-metric-cell]')].find(
    (c) => c.firstElementChild?.textContent === label,
  );
  if (!cell) throw new Error(`no KPI ${label}`);
  return cell;
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
    // DataTable rows: the Capability is the first cell of each activatable row, in display order.
    const labels = [...table.querySelectorAll('tbody tr[role="button"]')].map((tr) => tr.querySelector('td')?.textContent);
    expect(labels).toEqual(TODAY_ROWS.map((r) => r.label));
  });

  it('read-write fixture: each row\'s readiness reason resolves in its drawer; the table marks every gated row', async () => {
    const table = await renderPanel(fixture('demo-aws-001'));

    // Migration: the full gate / reason copy moved off the table row into the row's DetailDrawer.
    // The table row still shows the analysis text and a gated lock + status word (never colour alone).
    expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent('Recommendations');
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent('Look-only plan: what could be emptied or drained');
    // Every blocked cell is marked by a lock as well as its words (Iconify draws it after mount).
    await waitFor(() => expect(table.querySelectorAll('[data-gated-lock]')).toHaveLength(TODAY_ROWS.length));
    // The reasons are NOT in the table itself anymore.
    expect(within(table).queryByText(/Also needs/)).toBeNull();
    expect(within(table).queryByText(CONTRACT_BLOCK_MESSAGE)).toBeNull();

    // Open each row's drawer and read its Apply section's reason.
    const reasonInDrawer = async (id: string, text: string) => {
      const dialog = await openDrawer(table, id);
      expect(dialog).toHaveTextContent(text);
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    };
    // NIT 8: the workload row reads can('vertical.apply')'s primary message, the contract block.
    await reasonInDrawer('workload-rightsizing', CONTRACT_BLOCK_MESSAGE);
    await reasonInDrawer('node-consolidation', DEMO_READINESS_MESSAGES['evictor-live']);
    await reasonInDrawer('node-optimizer', DEMO_READINESS_MESSAGES['node-optimizer-actions']);
    await reasonInDrawer('rebalancing', DEMO_READINESS_MESSAGES.rebalancer);
    await reasonInDrawer('hpa-manager', DEMO_READINESS_MESSAGES['hpa-apply']);
    await reasonInDrawer('scale-out', DEMO_READINESS_MESSAGES['scale-out']);
    await reasonInDrawer('node-templates', DEMO_READINESS_MESSAGES['node-templates']);
    await reasonInDrawer('vpa-baseline', DEMO_READINESS_MESSAGES['vpa-apply']);
    // No readiness key: only the demo session holds it.
    await reasonInDrawer('spot-interruption', DEMO_TEXT);

    expect(accessValue('Connection')).toHaveTextContent('Read-write');
    expect(accessValue('Your permission')).toHaveTextContent('You can change automation');
    expect(accessValue('XamOps agent')).toHaveTextContent('Connected');
    expect(accessValue('XamOps agent')).toHaveTextContent('data 4 min old');

    const overlays = [...document.querySelectorAll('[data-overlay]')].map((el) => el.getAttribute('data-overlay'));
    expect(overlays).toEqual(['demo']);
  });

  it('read-only fixture: the read-only banner, and the connection named in each gated drawer', async () => {
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

    const drawerHas = async (id: string, assert: (dialog: HTMLElement) => void) => {
      const dialog = await openDrawer(table, id);
      assert(dialog);
      await user.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    };
    // Readiness ranks above the connection, so it stays primary and the connection is listed after it.
    await drawerHas('node-consolidation', (d) => {
      expect(d).toHaveTextContent(DEMO_READINESS_MESSAGES['evictor-live']);
      expect(d).toHaveTextContent('Also needs: read-write access');
    });
    await drawerHas('workload-rightsizing', (d) => {
      expect(d).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
      expect(d).toHaveTextContent('Also needs: read-write access');
    });
    // Where nothing ranks higher, the connection is the reason.
    await drawerHas('spot-interruption', (d) => {
      expect(d).toHaveTextContent(READ_ONLY_TEXT);
      expect(d).not.toHaveTextContent(DEMO_TEXT);
    });
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

  it('reads each source once: endpoint 1, the activity log and its summary, the usage feed and the node fleet', async () => {
    await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { eksData: DASHBOARD });
    await screen.findByText(/XamOps is watching 3 workloads/);
    // The five Phase-1/2 reads plus the three Node autoscaler reads the spot-interruption row shares
    // (FEAT-003): the agent dashboard, the node fleet (interruptedNodes) and the rebalance status.
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledTimes(8));
    expect(getCapabilities).toHaveBeenCalledTimes(1);
    expect(endpointOneGets()).toHaveLength(1);
    expect(String(endpointOneGets()[0][0])).toBe('/api/eks/automation/vertical/prod-eks-cluster?accountId=demo-aws-001&region=us-east-1');
    const feed = getsTo(/\/activity\/prod-eks-cluster$/);
    expect(feed).toHaveLength(1);
    expect(feed[0].searchParams.get('limit')).toBe('200');
    // The last 7 days, to the minute.
    expect(Date.now() - Date.parse(feed[0].searchParams.get('from')!)).toBeGreaterThanOrEqual(7 * 24 * 3600_000);
    expect(Date.now() - Date.parse(feed[0].searchParams.get('from')!)).toBeLessThan(7 * 24 * 3600_000 + 120_000);
    expect(getsTo(/\/activity\/prod-eks-cluster\/summary$/).map((u) => u.searchParams.get('days'))).toEqual(['7']);
    const usage = getsTo(/\/usage\/prod-eks-cluster$/).map((u) => `${u.searchParams.get('scope')}:${u.searchParams.get('resource')}:${u.searchParams.get('window')}`).sort();
    expect(usage).toEqual(['cluster:cpu:72h', 'namespace:cpu:72h']);
    // The spot-interruption row reads the node fleet once.
    expect(getsTo(/\/api\/eks\/autoscaling\/prod-eks-cluster\/node-fleet$/)).toHaveLength(1);
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

    // Regression for the live UAT break: when the read carries the cost roll-up, the cluster's real
    // monthly cost must render on the Overview even though every saving is a true $0 (no
    // recommendation yet). Without the roll-up (ENDPOINT_1 above) no cost card shows.
    it('shows the cluster Monthly cost KPI from clusterMonthlyCost, independent of savings', async () => {
      serve(200, {
        ...ENDPOINT_1,
        cost: { currency: 'USD', hoursPerMonth: 730, monthlySavings: 0.0, monthlySavingsUnavailableReason: null, clusterMonthlyCost: 23.670027275035324, pricedWorkloads: 8, unpricedWorkloads: 1 },
      });
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-overview-kpis]')).not.toBeNull());
      const monthly = kpi('Monthly cost');
      // formatMoney renders whole dollars above $1: 23.67 → $24.
      expect(monthly).toHaveTextContent('$24');
      expect(monthly.querySelector('[data-priced-line]')).toHaveTextContent('8 of 9 priced');
      // The cost figure carries NO Trend chip: previousClusterMonthlyCost is absent here (no
      // prior-period source yet), so prior is null and a trend is never fabricated.
      expect(monthly.querySelector('[data-trend]')).toBeNull();
    });

    // FEAT-002 migration: the 52px KpiHeroStrip/KpiCard hero is gone (no 7th font size survives the
    // gate), the summary is a single MetricStrip, and no duplicate cluster-vitals strip renders.
    it('migrates the hero to a MetricStrip: no 52px node, no KpiCard, no cluster-vitals duplicate', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-overview-kpis]')).not.toBeNull());
      // No 7th font size, no legacy KpiCard chrome.
      expect(document.querySelector('.text-\\[52px\\]')).toBeNull();
      expect(document.querySelector('[data-kpi-tone]')).toBeNull();
      expect(document.querySelector('[data-kpi-hero]')).toBeNull();
      // The summary is one MetricStrip hero (not a hero + strip pair).
      expect(document.querySelectorAll('[data-overview-kpis] [data-surface-tier="hero"]')).toHaveLength(1);
      // No cluster-vitals (Health / Nodes ready / Pods running) duplicate — ClusterStatusStrip owns
      // those and is not rendered inside OverviewPanel.
      const strip = document.querySelector<HTMLElement>('[data-overview-kpis]')!;
      for (const vital of ['Health', 'Nodes ready', 'Pods running', 'Node automation']) {
        expect(strip.textContent).not.toContain(vital);
      }
    });

    it('wraps the Overview in the redesigned PageTemplate, full-width, with the Access rail in the aside', async () => {
      await renderPanel(fixture('demo-aws-001'));
      const main = document.querySelector<HTMLElement>('[data-k8s-redesigned-main]');
      expect(main).not.toBeNull();
      // Full-width ('table' → max-w-full, the width the Pods tab uses): the Overview fills the
      // available content area instead of a left-hugging 935px prose column that left a large
      // right-side gap (owner one-sided review).
      expect(main).toHaveAttribute('data-page-width', 'table');
      expect(main?.className).toContain('max-w-full');
      expect(main?.className).not.toContain('max-w-[935px]');
      // AccessBlock moved into the right rail (aside), not the main column flow.
      const aside = document.querySelector<HTMLElement>('[data-page-aside]');
      expect(aside).not.toBeNull();
      expect(within(aside!).getByRole('heading', { name: 'Access' })).toBeInTheDocument();
    });

    it('opens a DetailDrawer with Capability / Apply / How-this-is-calculated when a Today row is activated', async () => {
      const user = userEvent.setup();
      const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs: { hpa: '/h' } });
      await user.click(rowOf(table, 'hpa-manager'));
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByRole('heading', { name: 'HPA manager' })).toBeInTheDocument();
      expect(dialog).toHaveTextContent('Capability');
      expect(dialog).toHaveTextContent('Apply');
      expect(dialog).toHaveTextContent('How this is calculated');
      // The HPA-apply gate copy resolves inside the drawer, not the row.
      expect(dialog).toHaveTextContent(DEMO_READINESS_MESSAGES['hpa-apply']);
      // Esc closes and returns focus to the row.
      await user.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });

    // Owner debox review: the How-it-works region is a LIGHT HORIZONTAL STRIP of StatusDots, not a
    // tall stack of bordered rows with an inner filled pill. The five steps lay out in ONE flex-wrap
    // row, each step's status is a StatusDot (data-tone + its word, no rounded-full chip tint), and
    // no filled status pill survives in the region.
    it('renders How it works as a horizontal StatusDot strip, with no filled status pill', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-how-step="decide"]')).toHaveTextContent('2 suggestions'));
      const region = document.querySelector('[data-how-step="measure"]')!.closest('ol')!;
      // One flex-wrap strip (side-by-side, wrapping), not a vertical flex-col stack of boxes.
      expect(region.className).toContain('flex-wrap');
      expect(region.className).not.toContain('flex-col');
      // All five steps sit in the one container.
      expect(region.querySelectorAll('[data-how-step]')).toHaveLength(5);
      // Each step's status is a StatusDot (dot + word): a data-tone element with a mandatory
      // aria-hidden svg and non-empty text, and NOT a filled chip pill (no rounded-full/chip tint).
      for (const chip of region.querySelectorAll('[data-how-chip]')) {
        const dot = chip.querySelector('[data-tone]')!;
        expect(dot.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
        expect((dot.textContent ?? '').trim().length).toBeGreaterThan(0);
        expect((dot.getAttribute('class') ?? '')).not.toMatch(/rounded-full/);
      }
      // No filled status pill (a tinted rounded-full chip) remains anywhere in the strip.
      expect(region.querySelector('.rounded-full')).toBeNull();
    });

    it('lights How it works from this cluster: suggestions waiting, the confirmed change and the wait', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(document.querySelector('[data-how-step="decide"]')).toHaveTextContent('2 suggestions'));
      expect(document.querySelector('[data-how-step="measure"]')).toHaveTextContent('Up to date');
      expect(document.querySelector('[data-how-step="apply"]')).toHaveTextContent('Waiting for you');
      expect(document.querySelector('[data-how-step="verify"]')).toHaveTextContent('confirmed');
      expect(document.querySelector('[data-how-step="watch"]')).toHaveTextContent(/Until /);
    });

    // Owner debox review: the Access card's routine status is a StatusDot (dot + word), never a
    // filled tinted pill. Each value is a data-tone element with an aria-hidden svg and its word,
    // and carries no rounded-full chip tint.
    it('renders Access statuses as StatusDots, with no filled pill', async () => {
      await renderPanel(fixture('demo-aws-001'));
      for (const term of ['Connection', 'Your permission', 'XamOps agent']) {
        const value = accessValue(term);
        const dot = value.querySelector('[data-tone]')!;
        expect(dot, term).not.toBeNull();
        expect(dot.querySelector('svg[aria-hidden="true"]'), term).not.toBeNull();
        expect(value.querySelector('.rounded-full'), term).toBeNull();
      }
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
    it('points the Node consolidation row at the look-only Consolidation page', async () => {
      const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { hrefs: { consolidation: '/c' } });
      const row = rowOf(table, 'node-consolidation');
      expect(row).toHaveTextContent('Look-only plan: what could be emptied or drained');
      expect(within(row).getByRole('link', { name: 'Automation › Consolidation' })).toHaveAttribute('href', '/c');
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

    it('shows the honest empty states: no agent pods, and no usage chart from a backend without the usage feed', async () => {
      await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(card('CPU by namespace: requested, used and suggested')).toHaveTextContent("The XamOps agent hasn't reported this cluster's pods"));
      // An untyped 404 from the usage feed: the card isn't rendered (automation-v2 design §7).
      await waitFor(() => expect(getsTo(/\/usage\//).length).toBeGreaterThan(0));
      await waitFor(() => expect(screen.queryByRole('heading', { name: 'CPU and memory over time' })).toBeNull());
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

// ─── Phase 2 (FEAT-006): the activity log, its summary and the usage feed ────

const MI = 1024 * 1024;
const IDLE_AT = ENDPOINT_1.workloads![2].lastAction!.at!;
const sizes = (cpuRequestMillis: number, memRequestMi: number) => ({ cpuRequestMillis, cpuLimitMillis: null, memRequestBytes: memRequestMi * MI, memLimitBytes: null });

/** The activity log's week: idle-batch-listener's in-place change (the one endpoint 1 reports), a test run, a put-back and an HPA change. */
const FEED = {
  engine: { enabled: true },
  nextCursor: null,
  entries: [
    {
      id: 7, at: IDLE_AT, feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' }, dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: 'APPLIED', restartFree: true,
      change: { containers: [{ container: 'sleeper', before: sizes(100, 128), beforeBasis: 'OBSERVED', after: sizes(20, 128), requested: sizes(20, 128) }] },
      proof: { restartFree: true, reverted: null, pods: ['a', 'b', 'c'].map((p) => ({ pod: `idle-batch-listener-${p}`, changed: true, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: null })) },
      detail: null, reference: 'r-7',
    },
    {
      id: 6, at: iso(30 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' }, dryRun: true, outcome: 'TEST_RUN', result: 'TEST_RUN', terminal: 'DRY_RUN', restartFree: false,
      change: { containers: [{ container: 'api', before: null, beforeBasis: null, after: null, requested: sizes(25, 256) }] }, proof: null, detail: null, reference: null,
    },
    {
      id: 5, at: iso(60 * 60_000), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'prod-sim', kind: 'Deployment', name: 'throttled-encoder' }, dryRun: false, outcome: 'NOT_CHANGED', result: 'PUT_BACK', terminal: 'DEFERRED', restartFree: true,
      change: null, proof: null, detail: 'Deferred. Revert: REVERTED.', reference: null,
    },
    {
      id: 4, at: iso(2 * 24 * 3600_000), feature: 'HPA', origin: 'MANUAL', who: 'A user', action: 'HPA_CHANGE',
      target: { namespace: 'team-b', kind: 'HorizontalPodAutoscaler', name: 'php-apache' }, dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: null, restartFree: false,
      change: { hpa: { before: { minReplicas: 1, maxReplicas: 5, targetUtilizationPct: 50 }, after: { minReplicas: 1, maxReplicas: 8, targetUtilizationPct: 50 } } }, proof: null, detail: null, reference: null,
    },
  ],
};

const istDay = (msAgo: number) => new Date(Date.now() - msAgo + 5.5 * 3600_000).toISOString().slice(0, 10);
/** The summary lists only the last two days: XamOps began recording every kind of change yesterday. */
const SUMMARY = {
  engine: { enabled: true }, timezone: 'Asia/Kolkata', from: istDay(6 * 24 * 3600_000), to: istDay(0),
  firstRecordedAt: iso(9 * 24 * 3600_000), completeSince: iso(30 * 3600_000), truncated: false,
  days: [
    { date: istDay(24 * 3600_000), applied: 1, putBack: 0, refused: 1, testRuns: 0, failed: 0, byFeature: {} },
    { date: istDay(0), applied: 2, putBack: 1, refused: 0, testRuns: 2, failed: 0, byFeature: {} },
  ],
};

const U_END = Math.floor(Date.now() / 60_000) * 60;
const U_TIMES = Array.from({ length: 145 }, (_, i) => U_END - 72 * 3600 + i * 1800);
const isoSec = (sec: number) => new Date(sec * 1000).toISOString().replace('.000Z', 'Z');

/** scope=cluster, 72 h at 30 min: 1.5 vCPU used (one point unmeasured), 2 requested, 3.86 allocatable, 4 provisioned. */
function clusterUsage(resource = 'cpu', firstIndex = 0) {
  const pts = (v: (i: number) => number | null) => U_TIMES.map((t, i) => [t, i < firstIndex ? null : v(i)]);
  const k = resource === 'cpu' ? 1 : 1024 ** 3;
  return {
    schemaVersion: 1, scope: 'cluster', resource, unit: resource === 'cpu' ? 'cores' : 'bytes', workloadRef: null, window: '72h', stepSeconds: 1800,
    start: isoSec(U_TIMES[0]), end: isoSec(U_TIMES[144]), source: { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' },
    series: [
      { key: 'used', kind: 'used', label: 'Used', points: pts((i) => (i === 100 ? null : 1.5 * k)), query: 'sum(rate(...))' },
      { key: 'request', kind: 'request', label: 'Requested', points: pts(() => 2 * k), query: 'sum(requests)' },
      { key: 'allocatable', kind: 'allocatable', label: 'Allocatable', points: pts(() => 3.86 * k), query: 'sum(allocatable)' },
      { key: 'capacity', kind: 'capacity', label: 'Provisioned', points: pts(() => 4 * k), query: 'sum(capacity)' },
    ],
    namespaces: null,
    coverage: { firstSampleAt: isoSec(U_TIMES[firstIndex]), covered: (145 - firstIndex - 1) / 145 },
    unavailable: null,
  };
}

const namespaceUsage = {
  schemaVersion: 1, scope: 'namespace', resource: 'cpu', unit: 'cores', workloadRef: null, window: '72h', stepSeconds: 1800,
  start: isoSec(U_TIMES[0]), end: isoSec(U_TIMES[144]), source: { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' },
  series: [],
  namespaces: [{ namespace: 'team-a', requestedNow: 2, usedAvg: 0.08, series: [] }],
  coverage: { firstSampleAt: isoSec(U_TIMES[0]), covered: 1 },
  unavailable: null,
};

/** scope=cluster or scope=namespace, by the query. */
const usageAnswer = (cluster = clusterUsage()) => (u: URL) =>
  ({ status: 200, body: u.searchParams.get('scope') === 'namespace' ? namespaceUsage : u.searchParams.get('resource') === 'memory' ? clusterUsage('memory') : cluster });

describe('Automation › Overview: the activity log, its summary and the usage feed (FEAT-006)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    mockedFetch.mockReset();
    serve(200, ENDPOINT_1, { feed: { status: 200, body: FEED }, summary: { status: 200, body: SUMMARY }, usage: usageAnswer() });
  });
  afterEach(() => vi.restoreAllMocks());

  it('places every entry of the last 7 days on the timeline, one lane per workload or HPA, with its source dot', async () => {
    await renderPanel(fixture('demo-aws-001'));
    const timeline = await waitFor(() => {
      const el = card('What automation did, last 7 days');
      expect(el.querySelectorAll('[data-lane]')).toHaveLength(4);
      return el;
    });
    expect(timeline).toHaveTextContent('Every change XamOps recorded in the last 7 days, one lane per workload or HPA');
    expect(timeline.querySelector('[data-chart-source]')).toHaveTextContent('Activity log (FEAT-007, GET /api/eks/automation/activity/{cluster}?from=…&limit=200)');
    expect([...timeline.querySelectorAll('[data-lane]')].map((l) => l.getAttribute('data-lane'))).toEqual(['idle-batch-listener', 'overprovisioned-api', 'throttled-encoder', 'php-apache']);
    expect(timeline.querySelector('[data-lane="php-apache"] [data-feature-dot="hpa"]')).not.toBeNull();
    // A test run is a hollow dot: nothing was sent.
    expect(timeline.querySelector('[data-lane="overprovisioned-api"] circle[fill="none"]')).not.toBeNull();
    const rows = [...timeline.querySelectorAll('tbody tr')].map((tr) => tr.textContent);
    expect(rows).toHaveLength(4);
    expect(rows[1]).toContain('Test run');
    expect(rows[2]).toContain('Deferred, put back');
  });

  it("shows Saving now from the change endpoint 1 says is in place, sized by the log's before and after", async () => {
    await renderPanel(fixture('demo-aws-001'));
    const saving = await waitFor(() => kpi('Saving now'));
    // (100m − 20m) × the 3 pods it resized; memory didn't move.
    expect(saving).toHaveTextContent('0.24 vCPU');
    expect(saving).toHaveTextContent('idle-batch-listener, in place on 3 pods');
    expect(saving).not.toHaveTextContent('memory freed');
    expect(document.querySelector('[data-overview-kpis]')).not.toHaveTextContent('Applied live');
  });

  it("leaves Saving now at — when the log doesn't hold the size before, and says why", async () => {
    const legacy = { ...FEED, entries: [{ ...FEED.entries[0], change: { containers: [{ ...FEED.entries[0].change!.containers![0], beforeBasis: 'REQUESTED' }] } }] };
    serve(200, ENDPOINT_1, { feed: { status: 200, body: legacy }, summary: { status: 200, body: SUMMARY }, usage: usageAnswer() });
    await renderPanel(fixture('demo-aws-001'));
    const saving = await waitFor(() => kpi('Saving now'));
    expect(saving).toHaveTextContent('—');
    expect(saving).toHaveTextContent("1 change is in place, but its earlier size isn't recorded.");
  });

  it('counts the changes of the last 7 days per IST day from the summary, since the day it began recording', async () => {
    await renderPanel(fixture('demo-aws-001'));
    const changes = await waitFor(() => {
      const el = kpi('Changes, last 7 days');
      expect(el).toHaveTextContent('Applied: 3');
      return el;
    });
    expect(changes).toHaveTextContent('7');
    expect(changes).toHaveTextContent('Deferred or put back: 1');
    expect(changes).toHaveTextContent('Refused: 1');
    expect(changes).toHaveTextContent('Test runs: 2');
    expect(changes).toHaveTextContent('2 of the 7 were test runs: nothing was sent.');
    expect(changes).toHaveTextContent(/counted per IST day since .+, when XamOps began recording every kind of change/);
  });

  it('stacks the cluster\'s CPU over 3 days with the provisioned line, a gap where nothing was measured, and a memory switch', async () => {
    const user = userEvent.setup();
    await renderPanel(fixture('demo-aws-001'));
    const chart = await waitFor(() => {
      const el = card('CPU and memory over time');
      expect(el.querySelectorAll('tbody tr')).toHaveLength(145);
      return el;
    });
    const rows = [...chart.querySelectorAll('tbody tr')];
    expect([...rows[0].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['1.50 vCPU', '2.00 vCPU', '3.86 vCPU', '4.00 vCPU']);
    expect([...rows[100].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['—', '2.00 vCPU', '3.86 vCPU', '4.00 vCPU']);
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('Prometheus monitoring/prometheus-server:80');
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('step 30 min, 3 days.');
    expect(chart).toHaveTextContent('Provisioned (line)');
    await user.click(within(chart).getByRole('button', { name: 'Memory' }));
    await waitFor(() => expect(getsTo(/\/usage\//).some((u) => u.searchParams.get('scope') === 'cluster' && u.searchParams.get('resource') === 'memory')).toBe(true));
    await waitFor(() => expect([...card('CPU and memory over time').querySelectorAll('tbody tr')][0]).toHaveTextContent('1.50 GiB'));
  });

  it('says how much history Prometheus keeps when it is shorter than the window', async () => {
    serve(200, ENDPOINT_1, { feed: { status: 200, body: FEED }, summary: { status: 200, body: SUMMARY }, usage: usageAnswer(clusterUsage('cpu', 49)) });
    await renderPanel(fixture('demo-aws-001'));
    await waitFor(() => expect(card('CPU and memory over time').querySelector('[data-chart-partial]')).toHaveTextContent(/Prometheus keeps 2 days here\. No data before .+\./));
  });

  it('names an unavailable source in words, and offers Retry when the read failed', async () => {
    const user = userEvent.setup();
    serve(200, ENDPOINT_1, {
      feed: { status: 200, body: FEED },
      summary: { status: 200, body: SUMMARY },
      usage: (u) => (u.searchParams.get('scope') === 'cluster'
        ? { status: 200, body: { ...clusterUsage(), series: [], coverage: null, unavailable: { code: 'CLUSTER_ACCESS_NOT_CONFIGURED', message: 'No access entry.' } } }
        : { status: 200, body: namespaceUsage }),
    });
    await renderPanel(fixture('demo-aws-001'));
    await waitFor(() => expect(card('CPU and memory over time').querySelector('[data-empty="unavailable"]')).toHaveTextContent("XamOps can't sign in to this cluster, so it can't read Prometheus there."));

    serve(200, ENDPOINT_1, { usage: { status: 502, body: { message: 'Bad gateway' } } });
    await user.click(within(card('CPU and memory over time')).getByRole('button', { name: 'Memory' }));
    const failed = await waitFor(() => {
      const el = card('CPU and memory over time').querySelector<HTMLElement>('[data-empty="error"]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(failed).toHaveTextContent('Bad gateway');
    serve(200, ENDPOINT_1, { usage: usageAnswer() });
    await user.click(within(failed).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(card('CPU and memory over time').querySelectorAll('tbody tr')).toHaveLength(145));
  });

  it("uses Prometheus's 3-day average for each namespace's use, and names it", async () => {
    await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', { eksData: DASHBOARD });
    const chart = card('CPU by namespace: requested, used and suggested');
    await waitFor(() => {
      const teamA = within(chart).getByRole('rowheader', { name: 'team-a', hidden: true }).closest('tr')!;
      // 0.08 cores on average (the agent's reading was 40m).
      expect([...teamA.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['2.00 vCPU', '0.08 vCPU', '0.05 vCPU']);
    });
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent("the mean of each namespace's use over 3 days");
  });

  // FEAT-003: the spot-interruption row surfaces the real node-fleet interruptedNodes, never a
  // fabricated value — a count + data age when present, an honest em-dash/None when absent or failed.
  describe('spot-interruption row surfaces real interruptedNodes (FEAT-003 / §3 AC3.4)', () => {
    const spotCell = (table: HTMLElement) => {
      const cell = rowOf(table, 'spot-interruption').querySelector<HTMLElement>('[data-analysis="spot-interruption"]');
      if (!cell) throw new Error('no spot-interruption analysis cell');
      return cell;
    };

    it('shows the interrupted node count and data age when nodes are interrupted', async () => {
      serve(200, ENDPOINT_1, {
        fleet: { status: 200, body: { totalNodes: 4, onDemandNodes: 2, spotNodes: 2, idleNodes: [], interruptedNodes: ['ip-10-0-1-5', 'ip-10-0-2-9'], collectedAt: iso(3 * 60_000) } },
      });
      const table = await renderPanel(fixture('demo-aws-001'));
      const cell = spotCell(table);
      await waitFor(() => expect(cell).toHaveTextContent('2 nodes interrupted'));
      expect(cell).toHaveTextContent('data 3 min old');
      // The dot carries the attention (warn) tone, never colour alone.
      expect(cell.querySelector('[data-tone="warn"]')).not.toBeNull();
    });

    it('reads "None interrupted" when the fleet is read and empty', async () => {
      const table = await renderPanel(fixture('demo-aws-001'));
      const cell = spotCell(table);
      await waitFor(() => expect(cell).toHaveTextContent('None interrupted'));
      expect(cell.querySelector('[data-tone="good"]')).not.toBeNull();
    });

    it('shows an honest em-dash (sr-only None) when the fleet read fails', async () => {
      serve(200, ENDPOINT_1, { fleet: { status: 502, body: { message: 'Bad gateway' } } });
      const table = await renderPanel(fixture('demo-aws-001'));
      const cell = spotCell(table);
      // No fabricated state: the em-dash idiom, no status dot at all.
      await waitFor(() => expect(within(cell).getByText('None', { selector: '.sr-only' })).toBeInTheDocument());
      expect(cell.querySelector('[data-tone]')).toBeNull();
    });

    it('records the last-drain outcome as an honest em-dash in the drawer (backend follow-up)', async () => {
      const table = await renderPanel(fixture('demo-aws-001'));
      await waitFor(() => expect(spotCell(table)).toHaveTextContent('None interrupted'));
      const dialog = await openDrawer(table, 'spot-interruption');
      expect(dialog).toHaveTextContent('Last drain');
    });
  });
});
