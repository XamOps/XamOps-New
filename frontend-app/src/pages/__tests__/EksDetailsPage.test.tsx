import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, useEksDashboard, apiGet, apiFetch, flags } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  useEksDashboard: vi.fn(),
  apiGet: vi.fn(),
  // The Automation sections this page mounts for real (OverviewPanel, WorkloadsPage, NodeAutoscalerPage,
  // HpaPage, VpaPage, ActivityPage via AutomationSection) read endpoint 1 and the Phase-2 feeds through
  // apiClient.fetch and the named apiFetch, at RELATIVE urls ("/api/eks/automation/vertical/…"). jsdom's
  // native fetch rejects a relative url with "Failed to parse URL", which vitest reports as an unhandled
  // error and fails the whole job even though every test passes. (These reads only started firing once the
  // STATUS_TONES fix stopped OverviewPanel's AccessStatus from crashing into an error boundary.) Stub both
  // the default.fetch and the named apiFetch with a benign untyped 404 — exactly the "backend without this
  // read" answer the pages already handle gracefully — so no relative-url fetch ever reaches jsdom. This
  // page asserts only section headings and URLs, never automation data, so the fixture body is irrelevant.
  apiFetch: vi.fn(),
  flags: { podsThrows: false, guideRendered: false },
}));

/** A backend without the automation reads: an untyped 404, which every Automation page reads as not-deployed. */
const automationNotDeployed = (): Response =>
  ({ status: 404, ok: false, text: async () => '' }) as unknown as Response;

vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../hooks/useEksDashboard', () => ({ useEksDashboard, default: useEksDashboard }));
vi.mock('../../hooks/useK8sCacheIdentity', () => ({ useK8sCacheIdentity: () => {}, default: () => {} }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, apiFetch, default: { ...actual.default, get: apiGet, fetch: apiFetch } };
});
vi.mock('../../contexts/AccountContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AccountContext')>();
  return { ...actual, useAccounts: () => ({ selectedAccountIds: ['123456789012'] }) };
});

// Today's tabs, hosted unchanged by the sections (P2): each renders a marker.
vi.mock('../../components/EksDetails/sections/overview/SummaryPanel', () => ({ default: () => <div>Overview tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksCostTab', () => ({ default: () => <div>Cost tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksPerformanceTab', () => ({ default: () => <div>Performance tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksSecurityTab', () => ({ default: () => <div>Security tab content</div> }));
vi.mock('../../components/EksDetails/sections/workloads/WorkloadsPanel', () => ({ default: () => <div>Workloads tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksPodsTab', () => ({
  default: () => {
    if (flags.podsThrows) throw new Error('pods tab failed');
    return <div>Pods tab content</div>;
  },
}));
vi.mock('../../components/EksDetails/tabs/EksContainersTab', () => ({ default: () => <div>Containers tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksNodesTab', () => ({ default: () => <div>Nodes tab content</div> }));
vi.mock('../../components/EksDetails/sections/workloads/ResourcesPanel', () => ({ default: () => <div>Resources tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksKarpenterTab', () => ({ default: () => <div>Karpenter tab content</div> }));
vi.mock('../../components/EksDetails/EksDiagnosticsPanel', () => ({ default: () => <div>Diagnostics panel</div> }));
vi.mock('../../components/EksDetails/tabs/EksAutomationTab', () => ({ default: () => <div>Workload autoscaler content</div> }));
vi.mock('../../components/EksDetails/EksIntegrationModal', () => ({
  default: ({ isOpen }: { isOpen: boolean }) => {
    flags.guideRendered = true;
    return isOpen ? <div role="dialog" aria-label="Integration guide" /> : null;
  },
}));
vi.mock('../../components/CloudK8s/CloudK8sHeader', () => ({ default: () => <div>Fleet header</div> }));
vi.mock('../../components/CloudK8s/CloudK8sClusterTable', () => ({
  default: ({ onViewDetails }: { onViewDetails: (c: { name: string; region: string; version: string; connected: boolean }) => void }) => (
    <button type="button" onClick={() => onViewDetails({ name: 'prod-eks-cluster', region: 'eu-west-1', version: '1.36', connected: true })}>
      Open prod-eks-cluster
    </button>
  ),
}));
vi.mock('../../components/CloudK8s/CloudK8sIntegrations', () => ({ default: () => <div>Fleet integrations</div> }));

import EksDetailsPage, { INVALID_LINK_NOTICE, parseClusterLink } from '../EksDetailsPage';
import CloudK8sPage from '../CloudK8sPage';
import { TopbarProvider, useTopbar } from '../../contexts/TopbarContext';
import { ThemeProvider } from '../../contexts/ThemeContext';
import { K8sApiError } from '../../api/k8sAutomationApi';
import { CONTRACT_BLOCK_MESSAGE } from '../../api/verticalRecommendationsAdapter';
import { DEMO_MODE_KEY } from '../../demo/demoConfig';
import { demoCapabilities } from '../../demo/k8sAutomationMockData';
import { SYNC_TIMEOUT_TEXT, VERSION_NOT_REPORTED } from '../../components/EksDetails/ClusterHeader';
import { CLUSTER_SECTIONS_LABEL, ClusterSectionsNav } from '../../components/CloudK8s/shared/ClusterSectionsNav';
import { DATA_UNAVAILABLE, STRIP_LABEL } from '../../components/EksDetails/ClusterStatusStrip';
import { EKS_SECTIONS } from '../../hooks/useEksSectionRoute';
import type { CapabilitiesDto } from '../../types/k8sAutomation';

const ACCOUNT = '123456789012';
const BASE = `/eks-details?name=prod-eks-cluster&region=us-east-1&accountIds=${ACCOUNT}`;
const AUTOMATION_HEADING = 'What automation can do on this cluster today';
/** A marker unique to the Overview's redesigned PageTemplate (the table caption duplicates the heading). */
const OVERVIEW_INTRO = /What XamOps can automate on this cluster/;

/** A capability row in the redesigned Automation DataTable, by its visible Capability label. */
const rowByLabel = (table: HTMLElement, label: string): HTMLElement => {
  const row = [...table.querySelectorAll<HTMLElement>('tbody tr[role="button"]')].find((tr) => tr.querySelector('td')?.textContent === label);
  if (!row) throw new Error(`no row ${label}`);
  return row;
};

/** The demo read-write fixture under a real account number, outside a demo session. */
function capabilities(edit?: (dto: CapabilitiesDto) => void): CapabilitiesDto {
  const dto = { ...demoCapabilities('demo-aws-001')!, accountId: ACCOUNT };
  edit?.(dto);
  return dto;
}

function dashboardState(over: Record<string, unknown> = {}) {
  return {
    dashboard: { clusterName: 'prod-eks-cluster' },
    loading: false,
    error: null,
    isSyncing: false,
    syncTimedOut: false,
    lastUpdateTimestamp: Date.now(),
    getTimeSince: () => '2m ago',
    syncInBackground: vi.fn(),
    fetchDashboard: vi.fn(),
    ...over,
  };
}

function TopbarOutlet() {
  const { title, actions } = useTopbar();
  return (
    <div data-testid="topbar">
      {title && <h1>{title}</h1>}
      {actions}
    </div>
  );
}

function LocationProbe() {
  const location = useLocation();
  return (
    <output data-testid="location" data-state={JSON.stringify(location.state ?? null)}>
      {`${location.pathname}${location.search}`}
    </output>
  );
}

/** `withNav` adds the sidebar's cluster sections, as the layout renders them beside the page. */
function renderAt(url: string, { withNav = false }: { withNav?: boolean } = {}) {
  return render(
    <ThemeProvider>
      <TopbarProvider>
        <MemoryRouter initialEntries={[url]}>
          <TopbarOutlet />
          {withNav && <ClusterSectionsNav dark={false} />}
          <Routes>
            <Route path="/eks-details" element={<EksDetailsPage />} />
            <Route path="/cloudk8s" element={<CloudK8sPage />} />
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </TopbarProvider>
    </ThemeProvider>,
  );
}

const currentUrl = () => new URL(screen.getByTestId('location').textContent ?? '', 'http://test');
const find = (text: string | RegExp) => screen.findByText(text, undefined, { timeout: 4000 });
/** The content area's heading, "Section, Subtab" to assistive tech (shown as "Section › Subtab"). */
const sectionHeading = (name: string) => screen.getByRole('heading', { level: 2, name });
const anySectionHeading = () => document.querySelector('[data-section-heading]');
const statusStrip = () => screen.getByRole('region', { name: STRIP_LABEL });
const topbar = () => screen.getByTestId('topbar');

beforeEach(() => {
  getCapabilities.mockReset().mockResolvedValue(capabilities());
  useEksDashboard.mockReset().mockReturnValue(dashboardState());
  apiGet.mockReset().mockResolvedValue([]);
  apiFetch.mockReset().mockResolvedValue(automationNotDeployed());
  flags.podsThrows = false;
  flags.guideRendered = false;
});

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('legacy ?tab= links (§5.2)', () => {
  it.each([
    ['overview', 'overview', 'summary', 'Overview', 'Summary', 'Overview tab content'],
    ['performance', 'nodes', 'utilization', 'Nodes', 'Utilization', 'Performance tab content'],
    ['workloads', 'workloads', 'workloads', 'Workloads', 'Workloads', 'Workloads tab content'],
    ['pods', 'workloads', 'pods', 'Workloads', 'Pods', 'Pods tab content'],
    ['containers', 'workloads', 'containers', 'Workloads', 'Containers', 'Containers tab content'],
    ['nodes', 'nodes', 'nodes', 'Nodes', 'Nodes', 'Nodes tab content'],
    ['resources', 'workloads', 'resources', 'Workloads', 'Resources', 'Resources tab content'],
    ['security', 'security', 'vulnerabilities', 'Security', 'Vulnerabilities', 'Security tab content'],
    ['cost', 'cost', 'cluster', 'Cost', 'Cluster', 'Cost tab content'],
    ['karpenter', 'nodes', 'karpenter', 'Nodes', 'Karpenter', 'Karpenter tab content'],
    ['autoscaling', 'automation', 'overview', 'Automation', 'Overview', OVERVIEW_INTRO],
    // The realtime branch's Automation tab on the old page.
    ['automation', 'automation', 'overview', 'Automation', 'Overview', OVERVIEW_INTRO],
  ])('?tab=%s lands on %s › %s with today\'s content', async (legacy, tab, sub, primaryLabel, subLabel, content) => {
    renderAt(`${BASE}&tab=${legacy}`);
    expect(await find(content)).toBeInTheDocument();

    await waitFor(() => expect(currentUrl().searchParams.get('tab')).toBe(tab));
    const params = currentUrl().searchParams;
    expect(params.get('sub')).toBe(sub);
    // Every other parameter is kept.
    expect(params.get('name')).toBe('prod-eks-cluster');
    expect(params.get('region')).toBe('us-east-1');
    expect(params.get('accountIds')).toBe(ACCOUNT);

    // The content area names what it shows; there is no tab strip (Addendum 1, J-6, J-7).
    expect(sectionHeading(`${primaryLabel}, ${subLabel}`)).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull();
  });

  it.each([
    ['workload-autoscaling', 'workload-autoscaler', 'Automation, Workload autoscaler, Workloads'],
    ['pod-autoscaling', 'hpa', 'Automation, HPA'],
  ])('?tab=automation&sub=%s lands on %s, named "%s"', async (alias, sub, heading) => {
    renderAt(`${BASE}&tab=automation&sub=${alias}`);
    await waitFor(() => expect(currentUrl().searchParams.get('sub')).toBe(sub));
    expect(currentUrl().searchParams.get('tab')).toBe('automation');
    expect(currentUrl().searchParams.get('accountIds')).toBe(ACCOUNT);
    expect(sectionHeading(heading)).toBeInTheDocument();
  });

  it('opens Overview › Summary for a plain link without rewriting it', async () => {
    renderAt(BASE);
    expect(await find('Overview tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.has('tab')).toBe(false);
    expect(sectionHeading('Overview, Summary')).toBeInTheDocument();
  });
});

describe('sidebar section navigation (Addendum 1)', () => {
  it.each(
    // A grouped subtab names its group too: "Automation, Workload autoscaler, Workloads".
    EKS_SECTIONS.flatMap((section) => section.subs.map((sub) => [section.id, sub.id, section.label, sub.group ? `${sub.group}, ${sub.label}` : sub.label] as const)),
  )('?tab=%s&sub=%s shows "%s, %s" with no tablist', async (tab, sub, sectionLabel, subLabel) => {
    renderAt(`${BASE}&tab=${tab}&sub=${sub}`, { withNav: true });
    expect(await screen.findByRole('heading', { level: 2, name: `${sectionLabel}, ${subLabel}` }, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).toBeNull();
    // The sidebar marks the same section and subtab as current.
    const nav = screen.getByRole('navigation', { name: CLUSTER_SECTIONS_LABEL });
    expect(nav.querySelector(`a[data-section="${tab}"]`)).toHaveAttribute('aria-current', 'page');
    expect(nav.querySelector(`a[data-sub="${sub}"]`)).toHaveAttribute('aria-current', 'page');
    // The URL was already right, so nothing rewrote it.
    expect(currentUrl().searchParams.get('tab')).toBe(tab);
    expect(currentUrl().searchParams.get('sub')).toBe(sub);
  });

  it('switches sections and subtabs through the sidebar links, without refetching', async () => {
    const user = userEvent.setup();
    renderAt(BASE, { withNav: true });
    await find('Overview tab content');
    const nav = screen.getByRole('navigation', { name: CLUSTER_SECTIONS_LABEL });

    await user.click(within(nav).getByRole('link', { name: 'Workloads' }));
    expect(await find('Workloads tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.get('tab')).toBe('workloads');
    expect(currentUrl().searchParams.get('sub')).toBe('workloads');

    await user.click(within(nav).getByRole('link', { name: 'Pods' }));
    expect(await find('Pods tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.get('sub')).toBe('pods');
    // Every cluster parameter is kept.
    expect(currentUrl().searchParams.get('name')).toBe('prod-eks-cluster');
    expect(currentUrl().searchParams.get('accountIds')).toBe(ACCOUNT);

    // The heading changed in a polite live region, so the switch is announced.
    const heading = sectionHeading('Workloads, Pods');
    expect(heading.closest('[aria-live="polite"]')).not.toBeNull();
    expect(heading.closest('[aria-live="polite"]')).toHaveAttribute('aria-atomic', 'true');

    // The page stayed mounted: one capabilities request, the dashboard hook always on the same link.
    expect(getCapabilities).toHaveBeenCalledTimes(1);
    for (const [args] of useEksDashboard.mock.calls) {
      expect(args).toEqual({ clusterName: 'prod-eks-cluster', accountId: ACCOUNT, region: 'us-east-1', version: null });
    }
  });
});

describe('link validation (§13)', () => {
  it.each([
    ['no name', `/eks-details?region=us-east-1&accountIds=${ACCOUNT}`],
    ['no accountIds', '/eks-details?name=prod-eks-cluster&region=us-east-1'],
    ['a name starting with a dash', `/eks-details?name=-prod&accountIds=${ACCOUNT}`],
    ['a name with a quote', `/eks-details?name=${encodeURIComponent('prod"x')}&accountIds=${ACCOUNT}`],
    ['a 101-character name', `/eks-details?name=${'a'.repeat(101)}&accountIds=${ACCOUNT}`],
    ['a 13-digit account', '/eks-details?name=prod-eks-cluster&accountIds=1234567890123'],
    ['two accounts', `/eks-details?name=prod-eks-cluster&accountIds=${ACCOUNT},210987654321`],
    ['a demo account outside demo mode', '/eks-details?name=prod-eks-cluster&accountIds=demo-aws-001'],
    ['a malformed region', `/eks-details?name=prod-eks-cluster&accountIds=${ACCOUNT}&region=useast1`],
  ])('sends %s to /cloudk8s with the notice, shown once', async (_label, url) => {
    const user = userEvent.setup();
    renderAt(url);

    const notice = await find(INVALID_LINK_NOTICE);
    expect(currentUrl().pathname).toBe('/cloudk8s');
    expect(notice.closest('[role="status"]')).not.toBeNull();
    // The history entry's notice is cleared, so Back or a reload doesn't show it again, and the
    // clearing keeps the page mounted, so the notice stays on screen.
    await waitFor(() => expect(JSON.parse(screen.getByTestId('location').dataset.state ?? 'null')).toMatchObject({ pageKey: expect.any(String) }));
    expect(JSON.parse(screen.getByTestId('location').dataset.state ?? 'null')).not.toHaveProperty('notice');
    expect(screen.getByText(INVALID_LINK_NOTICE)).toBeInTheDocument();

    expect(useEksDashboard).not.toHaveBeenCalled();
    expect(getCapabilities).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText(INVALID_LINK_NOTICE)).toBeNull();
  });

  it('accepts a demo account in a demo session', async () => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    renderAt('/eks-details?name=prod-eks-cluster&accountIds=demo-aws-001');
    expect(await find('Overview tab content')).toBeInTheDocument();
    expect(getCapabilities).toHaveBeenCalledWith('demo-aws-001', expect.anything());
  });

  it('defaults a missing region, never guesses a version, and accepts GovCloud regions', () => {
    expect(parseClusterLink(new URLSearchParams(`name=prod-eks-cluster&accountIds=${ACCOUNT}`), false)).toEqual({
      clusterName: 'prod-eks-cluster', accountId: ACCOUNT, region: 'us-east-1', version: null,
    });
    expect(parseClusterLink(new URLSearchParams(`name=p&accountIds=${ACCOUNT}&region=us-gov-west-1&version=1.31`), false))
      .toMatchObject({ region: 'us-gov-west-1', version: '1.31' });
    // A malformed version is dropped, not a reason to reject the link.
    expect(parseClusterLink(new URLSearchParams(`name=p&accountIds=${ACCOUNT}&version=latest`), false))
      .toMatchObject({ clusterName: 'p', version: null });
  });

  it('calls the dashboard hook with the link and no tab', async () => {
    renderAt(`${BASE}&tab=pods`);
    await find('Pods tab content');
    const args = useEksDashboard.mock.calls[0][0];
    expect(args).toEqual({ clusterName: 'prod-eks-cluster', accountId: ACCOUNT, region: 'us-east-1', version: null });
    expect(Object.keys(args)).not.toContain('initialTab');
  });

  it('opens a fleet row with the version EKS reports for it', async () => {
    const user = userEvent.setup();
    renderAt('/cloudk8s');
    await user.click(await find('Open prod-eks-cluster'));
    expect(currentUrl().pathname).toBe('/eks-details');
    expect(Object.fromEntries(currentUrl().searchParams)).toEqual({
      name: 'prod-eks-cluster', region: 'eu-west-1', accountIds: ACCOUNT, version: '1.36',
    });
    expect(await within(topbar()).findByText('v1.36')).toBeInTheDocument();
  });
});

describe('cluster page shell', () => {
  it('shows the diagnostics only with ?debug=true', async () => {
    const { unmount } = renderAt(BASE);
    await find('Overview tab content');
    expect(screen.queryByText('Diagnostics panel')).toBeNull();
    unmount();

    renderAt(`${BASE}&debug=true`);
    expect(await find('Diagnostics panel')).toBeInTheDocument();
  });

  it('puts the title, back link, version · region and the last sync in the topbar', async () => {
    renderAt(`${BASE}&version=1.36`);
    expect(await within(topbar()).findByRole('heading', { name: 'prod-eks-cluster' })).toBeInTheDocument();
    expect(within(topbar()).getByRole('link', { name: 'Back to clusters' })).toHaveAttribute('href', `/cloudk8s?accountIds=${ACCOUNT}`);
    expect(topbar()).toHaveTextContent('v1.36');
    expect(topbar()).toHaveTextContent('us-east-1');
    expect(topbar()).toHaveTextContent('Synced 2m ago');
  });

  it.each([
    ['no version anywhere', BASE, undefined, VERSION_NOT_REPORTED],
    ["only the backend's echo of a default", BASE, '1.28', VERSION_NOT_REPORTED],
    ["the link's describeCluster version", `${BASE}&version=1.36`, undefined, 'v1.36'],
    ["the agent's gitVersion over the link's", `${BASE}&version=1.36`, 'v1.36.4-eks-a737599', 'v1.36.4'],
  ])('shows the version from %s', async (_label, url, reported, shown) => {
    useEksDashboard.mockReturnValue(dashboardState({
      dashboard: { clusterName: 'prod-eks-cluster', clusterOverview: reported ? { version: reported } : undefined },
    }));
    renderAt(url);
    expect(await within(topbar()).findByText(shown)).toBeInTheDocument();
    expect(topbar()).not.toHaveTextContent('1.33');
  });

  it('shows the XamOps agent and node automation agent clocks separately', async () => {
    renderAt(BASE);
    const clocks = screen.getByRole('group', { name: 'Data freshness' });
    await waitFor(() => expect(clocks).toHaveTextContent('data 4 min ago'));
    expect(clocks).toHaveTextContent('XamOps agent');
    expect(clocks).toHaveTextContent('Node automation agent');
    expect(clocks).toHaveTextContent(/seen 4\d s ago/);
  });

  it('Sync calls the hook, and does nothing while a sync runs', async () => {
    const user = userEvent.setup();
    const syncInBackground = vi.fn();
    useEksDashboard.mockReturnValue(dashboardState({ syncInBackground }));
    const { unmount } = renderAt(BASE);
    await user.click(await within(topbar()).findByRole('button', { name: 'Sync data' }));
    expect(syncInBackground).toHaveBeenCalledTimes(1);
    unmount();

    const busy = vi.fn();
    useEksDashboard.mockReturnValue(dashboardState({ syncInBackground: busy, isSyncing: true }));
    renderAt(BASE);
    const button = await within(topbar()).findByRole('button', { name: 'Syncing…' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await user.click(button);
    expect(busy).not.toHaveBeenCalled();
  });

  it('says when a sync takes longer than expected', async () => {
    useEksDashboard.mockReturnValue(dashboardState({ syncTimedOut: true }));
    renderAt(BASE);
    expect(await find(SYNC_TIMEOUT_TEXT)).toBeInTheDocument();
  });

  it('loads the integration guide only when it is first opened', async () => {
    const user = userEvent.setup();
    renderAt(BASE);
    await find('Overview tab content');
    expect(flags.guideRendered).toBe(false);
    await user.click(within(topbar()).getByRole('button', { name: 'Guide' }));
    expect(await screen.findByRole('dialog', { name: 'Integration guide' }, { timeout: 4000 })).toBeInTheDocument();
  });

  it('a capabilities error shows the banner and blocks every apply', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getCapabilities.mockReset().mockRejectedValue(new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }));
    renderAt(`${BASE}&tab=automation`);
    // The table's name duplicates the visible heading (sr-only caption), so wait on the table role.
    await screen.findByRole('table', { name: AUTOMATION_HEADING }, { timeout: 4000 });

    await waitFor(() => expect(document.querySelector('[data-overlay="capabilities-error"]')).not.toBeNull());
    const banner = document.querySelector<HTMLElement>('[data-overlay="capabilities-error"]')!;
    expect(banner).toHaveTextContent("Couldn't check automation access.");
    expect(within(banner).getByRole('button', { name: 'Retry' })).toBeInTheDocument();

    const table = screen.getByRole('table', { name: AUTOMATION_HEADING });
    // v3 migration: each blocked row shows a lock + "Blocked"; the reason copy lives in its drawer.
    expect(within(table).queryByText('Available')).toBeNull();
    expect(rowByLabel(table, 'Spot interruption handling')).toHaveTextContent('Blocked');
    // The workloads row points at the hosted Workload autoscaler, which keeps its own gates (review S2).
    const workloadRow = rowByLabel(table, 'Workload rightsizing');
    expect(workloadRow).not.toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    expect(workloadRow).toHaveTextContent('Decided per workload in Automation › Workload autoscaler');
    await waitFor(() => expect(table.querySelectorAll('[data-gated-lock]').length).toBe(8));
    // The gate reason resolves inside the row's DetailDrawer, not the table.
    await userEvent.click(rowByLabel(table, 'Spot interruption handling'));
    expect(screen.getByRole('dialog')).toHaveTextContent("Couldn't check automation access.");
    await userEvent.keyboard('{Escape}');

    expect(screen.getByRole('group', { name: 'Data freshness' })).toHaveTextContent('unknown');
  });

  it('allows what nothing blocks when capabilities load (read-write, permitted, no demo)', async () => {
    renderAt(`${BASE}&tab=automation`);
    const table = await screen.findByRole('table', { name: AUTOMATION_HEADING }, { timeout: 4000 });
    await waitFor(() => expect(rowByLabel(table, 'Spot interruption handling')).toHaveTextContent('Available'));
    expect(document.querySelector('[data-overlay]')).toBeNull();
  });

  it('shows the dashboard error with Retry, and no sections', async () => {
    const user = userEvent.setup();
    const fetchDashboard = vi.fn();
    useEksDashboard.mockReturnValue(dashboardState({ dashboard: null, error: 'HTTP 500: upstream failed', fetchDashboard }));
    renderAt(BASE);
    expect(await find("Couldn't load this cluster")).toBeInTheDocument();
    expect(screen.getByText('HTTP 500: upstream failed')).toBeInTheDocument();
    expect(anySectionHeading()).toBeNull();
    // The status strip stays, and says the data is unavailable rather than disappearing.
    expect(statusStrip()).toHaveTextContent(DATA_UNAVAILABLE);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(fetchDashboard).toHaveBeenCalledWith(false);
  });

  it('shows a skeleton while the dashboard first loads', () => {
    useEksDashboard.mockReturnValue(dashboardState({ dashboard: null, loading: true }));
    renderAt(BASE);
    expect(screen.getByText('Loading cluster…')).toBeInTheDocument();
    expect(anySectionHeading()).toBeNull();
  });

  it('keeps a failing section to itself, and Try again recovers it', async () => {
    const user = userEvent.setup();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    flags.podsThrows = true;
    renderAt(`${BASE}&tab=workloads&sub=pods`);

    expect(await find("This section couldn't be shown.")).toBeInTheDocument();
    expect(sectionHeading('Workloads, Pods')).toBeInTheDocument();
    expect(statusStrip()).toBeInTheDocument();

    flags.podsThrows = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await find('Pods tab content')).toBeInTheDocument();
  });
});
