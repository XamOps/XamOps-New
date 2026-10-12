import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, useEksDashboard, apiGet, flags } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  useEksDashboard: vi.fn(),
  apiGet: vi.fn(),
  flags: { podsThrows: false, guideRendered: false },
}));

vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../hooks/useEksDashboard', () => ({ useEksDashboard, default: useEksDashboard }));
vi.mock('../../hooks/useK8sCacheIdentity', () => ({ useK8sCacheIdentity: () => {}, default: () => {} }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet } };
});
vi.mock('../../contexts/AccountContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AccountContext')>();
  return { ...actual, useAccounts: () => ({ selectedAccountIds: ['123456789012'] }) };
});

// Today's tabs, hosted unchanged by the sections (P2): each renders a marker.
vi.mock('../../components/EksDetails/tabs/EksOverviewTab', () => ({ default: () => <div>Overview tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksCostTab', () => ({ default: () => <div>Cost tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksPerformanceTab', () => ({ default: () => <div>Performance tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksSecurityTab', () => ({ default: () => <div>Security tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksWorkloadsTab', () => ({ default: () => <div>Workloads tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksPodsTab', () => ({
  default: () => {
    if (flags.podsThrows) throw new Error('pods tab failed');
    return <div>Pods tab content</div>;
  },
}));
vi.mock('../../components/EksDetails/tabs/EksContainersTab', () => ({ default: () => <div>Containers tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksNodesTab', () => ({ default: () => <div>Nodes tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksResourcesTab', () => ({ default: () => <div>Resources tab content</div> }));
vi.mock('../../components/EksDetails/tabs/EksKarpenterTab', () => ({ default: () => <div>Karpenter tab content</div> }));
vi.mock('../../components/EksDetails/EksSummaryCards', () => ({
  default: ({ clusterVersion }: { clusterVersion: string | null }) => (
    <div data-testid="summary-cards" data-version={clusterVersion ?? 'none'}>Summary cards</div>
  ),
}));
vi.mock('../../components/EksDetails/EksDiagnosticsPanel', () => ({ default: () => <div>Diagnostics panel</div> }));
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
import { K8sApiError } from '../../api/k8sAutomationApi';
import { CONTRACT_BLOCK_MESSAGE } from '../../api/verticalRightsizerAdapter';
import { DEMO_MODE_KEY } from '../../demo/demoConfig';
import { demoCapabilities } from '../../demo/k8sAutomationMockData';
import { SYNC_TIMEOUT_TEXT, VERSION_NOT_REPORTED } from '../../components/EksDetails/ClusterHeader';
import type { CapabilitiesDto } from '../../types/k8sAutomation';

const ACCOUNT = '123456789012';
const BASE = `/eks-details?name=prod-eks-cluster&region=us-east-1&accountIds=${ACCOUNT}`;
const AUTOMATION_HEADING = 'What automation can do on this cluster today';

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

function renderAt(url: string) {
  return render(
    <TopbarProvider>
      <MemoryRouter initialEntries={[url]}>
        <TopbarOutlet />
        <Routes>
          <Route path="/eks-details" element={<EksDetailsPage />} />
          <Route path="/cloudk8s" element={<CloudK8sPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </TopbarProvider>,
  );
}

const currentUrl = () => new URL(screen.getByTestId('location').textContent ?? '', 'http://test');
const find = (text: string | RegExp) => screen.findByText(text, undefined, { timeout: 4000 });
const primaryTabs = () => screen.getByRole('tablist', { name: 'Cluster sections' });
const topbar = () => screen.getByTestId('topbar');

beforeEach(() => {
  getCapabilities.mockReset().mockResolvedValue(capabilities());
  useEksDashboard.mockReset().mockReturnValue(dashboardState());
  apiGet.mockReset().mockResolvedValue([]);
  flags.podsThrows = false;
  flags.guideRendered = false;
});

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('legacy ?tab= links (§5.2)', () => {
  it.each([
    ['overview', 'overview', 'summary', 'Overview', null, 'Overview tab content'],
    ['performance', 'nodes', 'utilization', 'Nodes', 'Utilization', 'Performance tab content'],
    ['workloads', 'workloads', 'workloads', 'Workloads', 'Workloads', 'Workloads tab content'],
    ['pods', 'workloads', 'pods', 'Workloads', 'Pods', 'Pods tab content'],
    ['containers', 'workloads', 'containers', 'Workloads', 'Containers', 'Containers tab content'],
    ['nodes', 'nodes', 'nodes', 'Nodes', 'Nodes', 'Nodes tab content'],
    ['resources', 'workloads', 'resources', 'Workloads', 'Resources', 'Resources tab content'],
    ['security', 'security', 'vulnerabilities', 'Security', null, 'Security tab content'],
    ['cost', 'cost', 'cluster', 'Cost', null, 'Cost tab content'],
    ['karpenter', 'nodes', 'karpenter', 'Nodes', 'Karpenter', 'Karpenter tab content'],
    ['autoscaling', 'automation', 'overview', 'Automation', null, AUTOMATION_HEADING],
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

    expect(within(primaryTabs()).getByRole('tab', { selected: true })).toHaveTextContent(primaryLabel);
    if (subLabel) {
      const subTabs = screen.getByRole('tablist', { name: `${primaryLabel} views` });
      expect(within(subTabs).getByRole('tab', { selected: true })).toHaveTextContent(subLabel);
    }
  });

  it('opens Overview › Summary for a plain link without rewriting it', async () => {
    renderAt(BASE);
    expect(await find('Overview tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.has('tab')).toBe(false);
  });

  it('switches sections and subtabs through the URL', async () => {
    const user = userEvent.setup();
    renderAt(BASE);
    await find('Overview tab content');

    await user.click(within(primaryTabs()).getByRole('tab', { name: 'Workloads' }));
    expect(await find('Workloads tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.get('tab')).toBe('workloads');
    expect(currentUrl().searchParams.get('sub')).toBe('workloads');

    await user.click(within(screen.getByRole('tablist', { name: 'Workloads views' })).getByRole('tab', { name: 'Pods' }));
    expect(await find('Pods tab content')).toBeInTheDocument();
    expect(currentUrl().searchParams.get('sub')).toBe('pods');
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
    ['no version anywhere', BASE, undefined, VERSION_NOT_REPORTED, 'none'],
    ["only the backend's echo of a default", BASE, '1.28', VERSION_NOT_REPORTED, 'none'],
    ["the link's describeCluster version", `${BASE}&version=1.36`, undefined, 'v1.36', '1.36'],
    ["the agent's gitVersion over the link's", `${BASE}&version=1.36`, 'v1.36.4-eks-a737599', 'v1.36.4', 'v1.36.4-eks-a737599'],
  ])('shows the version from %s', async (_label, url, reported, shown, cardVersion) => {
    useEksDashboard.mockReturnValue(dashboardState({
      dashboard: { clusterName: 'prod-eks-cluster', clusterOverview: reported ? { version: reported } : undefined },
    }));
    renderAt(url);
    expect(await within(topbar()).findByText(shown)).toBeInTheDocument();
    expect(topbar()).not.toHaveTextContent('1.33');
    expect(screen.getByTestId('summary-cards')).toHaveAttribute('data-version', cardVersion);
  });

  it('shows the XamOps agent and automation agent clocks separately', async () => {
    renderAt(BASE);
    const clocks = screen.getByRole('group', { name: 'Data freshness' });
    await waitFor(() => expect(clocks).toHaveTextContent('data 4 min ago'));
    expect(clocks).toHaveTextContent('XamOps agent');
    expect(clocks).toHaveTextContent('Automation agent');
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
    await find(AUTOMATION_HEADING);

    await waitFor(() => expect(document.querySelector('[data-overlay="capabilities-error"]')).not.toBeNull());
    const banner = document.querySelector<HTMLElement>('[data-overlay="capabilities-error"]')!;
    expect(banner).toHaveTextContent("Couldn't check automation access.");
    expect(within(banner).getByRole('button', { name: 'Retry' })).toBeInTheDocument();

    const table = screen.getByRole('table', { name: AUTOMATION_HEADING });
    expect(within(table).queryByText('Available')).toBeNull();
    expect(table.querySelector('[data-row="spot-interruption"]')).toHaveTextContent("Couldn't check automation access.");
    expect(table.querySelector('[data-row="workload-rightsizing"]')).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    await waitFor(() => expect(table.querySelectorAll('[data-gated-lock]').length).toBe(9));

    expect(screen.getByRole('group', { name: 'Data freshness' })).toHaveTextContent('unknown');
  });

  it('allows what nothing blocks when capabilities load (read-write, permitted, no demo)', async () => {
    renderAt(`${BASE}&tab=automation`);
    const table = await screen.findByRole('table', { name: AUTOMATION_HEADING }, { timeout: 4000 });
    await waitFor(() => expect(table.querySelector('[data-row="spot-interruption"]')).toHaveTextContent('Available'));
    expect(document.querySelector('[data-overlay]')).toBeNull();
  });

  it('shows the dashboard error with Retry, and no sections', async () => {
    const user = userEvent.setup();
    const fetchDashboard = vi.fn();
    useEksDashboard.mockReturnValue(dashboardState({ dashboard: null, error: 'HTTP 500: upstream failed', fetchDashboard }));
    renderAt(BASE);
    expect(await find("Couldn't load this cluster")).toBeInTheDocument();
    expect(screen.getByText('HTTP 500: upstream failed')).toBeInTheDocument();
    expect(screen.queryByRole('tablist', { name: 'Cluster sections' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(fetchDashboard).toHaveBeenCalledWith(false);
  });

  it('shows a skeleton while the dashboard first loads', () => {
    useEksDashboard.mockReturnValue(dashboardState({ dashboard: null, loading: true }));
    renderAt(BASE);
    expect(screen.getByText('Loading cluster…')).toBeInTheDocument();
    expect(screen.queryByRole('tablist', { name: 'Cluster sections' })).toBeNull();
  });

  it('keeps a failing section to itself, and Try again recovers it', async () => {
    const user = userEvent.setup();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    flags.podsThrows = true;
    renderAt(`${BASE}&tab=workloads&sub=pods`);

    expect(await find("This section couldn't be shown.")).toBeInTheDocument();
    expect(primaryTabs()).toBeInTheDocument();
    expect(screen.getByText('Summary cards')).toBeInTheDocument();

    flags.podsThrows = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await find('Pods tab content')).toBeInTheDocument();
  });
});
