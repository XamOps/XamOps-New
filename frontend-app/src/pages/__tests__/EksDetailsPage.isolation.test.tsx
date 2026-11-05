/**
 * FEAT-004 — per-cluster data isolation on switch, proven through the real cluster page.
 *
 * Unlike EksDetailsPage.test.tsx (which mocks useEksDashboard), this file runs the REAL
 * useEksDashboard and useEksClusterCost hooks against a deferred apiClient.get mock, so the
 * reset-on-key-change + request-token stale-drop behaviour is exercised end to end. A probe section
 * echoes the dashboard, cost and proof values the two hooks expose, plus the selected workload and
 * container filters, so A's fixtures can be asserted absent after a switch to B.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, apiGet, apiFetch } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  apiGet: vi.fn(),
  apiFetch: vi.fn(),
}));

/** A backend without the automation reads: an untyped 404, read as not-deployed. */
const automationNotDeployed = (): Response =>
  ({ status: 404, ok: false, text: async () => '' }) as unknown as Response;

vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../hooks/useK8sCacheIdentity', () => ({ useK8sCacheIdentity: () => {}, default: () => {} }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, apiFetch, default: { ...actual.default, get: apiGet, fetch: apiFetch } };
});

const { accountState } = vi.hoisted(() => ({
  accountState: {
    selectedAccountIds: ['123456789012'] as string[],
    accounts: [{ id: '123456789012', name: 'Prod', provider: 'AWS' }] as Array<{ id: string; name: string; provider: string }>,
    isLoading: false,
  },
}));
vi.mock('../../contexts/AccountContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AccountContext')>();
  return {
    ...actual,
    useAccounts: () => {
      const selected = accountState.selectedAccountIds[0] ?? null;
      const acc = accountState.accounts.find((a) => a.id === selected);
      return {
        selectedAccountIds: accountState.selectedAccountIds,
        accounts: accountState.accounts,
        isLoading: accountState.isLoading,
        selectedAccountDisplay: { name: acc?.name ?? 'Select Accounts', id: acc?.id ?? '', provider: acc?.provider },
      };
    },
  };
});
vi.mock('../../contexts/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AuthContext')>();
  return { ...actual, useAuth: () => ({ userProfile: { email: 'jane@corp.example' }, authReady: true, isLoading: false }) };
});

// Heavy header/bar/strip are not what this file asserts; keep them cheap.
vi.mock('../../components/CloudK8s/CloudK8sHeader', () => ({ default: () => <div>Fleet header</div> }));
vi.mock('../../components/EksDetails/ClusterHeader', () => ({
  ClusterHeader: ({ clusterName }: { clusterName: string }) => <div data-testid="cluster-header">{clusterName}</div>,
}));
vi.mock('../../components/EksDetails/AutomationControlBar', () => ({ AutomationControlBar: () => <div>control bar</div> }));
vi.mock('../../components/EksDetails/ClusterStatusStrip', () => ({
  ClusterStatusStrip: ({ dashboard }: { dashboard: any }) => (
    <div data-testid="status-strip">{dashboard?.clusterName ?? 'no-cluster'}</div>
  ),
}));

// Every primary section renders the same probe: dashboard values from eksData, cost + proof values
// from the REAL useEksClusterCost, and the per-cluster workload/filter selections.
vi.mock('../../components/EksDetails/sections/OverviewSection', () => ({ default: makeProbe() }));
vi.mock('../../components/EksDetails/sections/CostSection', () => ({ default: makeProbe() }));
vi.mock('../../components/EksDetails/sections/WorkloadsSection', () => ({ default: makeProbe() }));
vi.mock('../../components/EksDetails/sections/NodesSection', () => ({ default: makeProbe() }));
vi.mock('../../components/EksDetails/sections/AutomationSection', () => ({ default: makeProbe() }));
vi.mock('../../components/EksDetails/sections/SecuritySection', () => ({ default: makeProbe() }));

import React from 'react';
import type { EksSectionProps } from '../../components/EksDetails/sections/sectionTypes';
import { EksDashboardCacheProvider } from '../../contexts/EksDashboardCacheContext';
import { ThemeProvider } from '../../contexts/ThemeContext';
import { useEksClusterCost } from '../../hooks/useEksClusterCost';
import EksDetailsPage from '../EksDetailsPage';

/** A probe section: echoes dashboard, cost, proof, workload and filter state for assertions. */
function makeProbe() {
  return function Probe({ clusterName, accountId, region, eksData }: EksSectionProps) {
    const { cost, workloads } = useEksClusterCost({ clusterName, accountId, region });
    const [selectedWorkload, setSelectedWorkload] = React.useState<string>('');
    return (
      <div data-testid="probe">
        <div data-testid="dash">{eksData.dashboard ? (eksData.dashboard as any).clusterName : 'none'}</div>
        <div data-testid="cost">{cost ? (cost as any).tag : 'none'}</div>
        <div data-testid="workloads">{workloads.map((w: any) => w.tag).join(',') || 'none'}</div>
        <div data-testid="filter">{eksData.containerNamespaceFilter || 'none'}</div>
        <div data-testid="selected-workload">{selectedWorkload || 'none'}</div>
        <button type="button" onClick={() => setSelectedWorkload('from-A')}>select workload</button>
        <button type="button" onClick={() => eksData.setContainerNamespaceFilter('from-A')}>set filter</button>
      </div>
    );
  };
}

const ACCOUNT = '123456789012';
const linkFor = (name: string, extra = '') =>
  `/eks-details?name=${name}&region=us-east-1&accountIds=${ACCOUNT}${extra}`;

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Routes a GET by URL: dashboard reads vs vertical cost reads. */
function routeGet(dashboard: (url: string) => any, cost: (url: string) => any) {
  apiGet.mockImplementation((url: string) => {
    if (url.includes('/dashboard')) return Promise.resolve(dashboard(url));
    if (url.includes('/automation/vertical/')) return Promise.resolve(cost(url));
    return Promise.resolve([]);
  });
}

/**
 * A button that navigates to cluster B using inPageState, to prove the key still remounts. It keeps
 * the current tab/sub (as the real ClusterSwitcher does) so the "tab/sub preserved" case is exercised.
 */
function Switcher() {
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <button
      type="button"
      onClick={() => {
        const params = new URLSearchParams(location.search);
        const tab = params.get('tab');
        const sub = params.get('sub');
        const extra = tab && sub ? `&tab=${tab}&sub=${sub}` : '';
        navigate(linkFor('cluster-b', extra), { replace: true, state: { pageKey: location.key } });
      }}
    >
      go to B in-page
    </button>
  );
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderAt(url: string) {
  return render(
    <ThemeProvider>
    <EksDashboardCacheProvider>
      <MemoryRouter initialEntries={[url]}>
        <Switcher />
        <Routes>
          <Route path="/eks-details" element={<EksDetailsPage />} />
          <Route path="/cloudk8s" element={<div>Fleet</div>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </EksDashboardCacheProvider>
    </ThemeProvider>,
  );
}

const currentUrl = () => new URL(screen.getByTestId('location').textContent ?? '', 'http://test');

beforeEach(() => {
  getCapabilities.mockReset().mockResolvedValue({ accountId: ACCOUNT, mode: 'read-only', readiness: {}, permissions: {} } as any);
  apiGet.mockReset().mockResolvedValue([]);
  apiFetch.mockReset().mockResolvedValue(automationNotDeployed());
  accountState.selectedAccountIds = [ACCOUNT];
  accountState.accounts = [{ id: ACCOUNT, name: 'Prod', provider: 'AWS' }];
  accountState.isLoading = false;
});

afterEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('EksDetailsPage per-cluster data isolation (FEAT-004)', () => {
  it('after A -> B shows only B in header/status/overview/cost; no A text anywhere', async () => {
    const user = userEvent.setup();
    routeGet(
      (url) => ({ clusterName: url.includes('cluster-a') ? 'A-dash' : 'B-dash', lastSyncedAt: null }),
      (url) => ({ cost: { tag: url.includes('cluster-a') ? 'A-cost' : 'B-cost' }, workloads: [{ tag: url.includes('cluster-a') ? 'A-wl' : 'B-wl' }], engine: {} }),
    );
    renderAt(linkFor('cluster-a'));

    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('A-dash'));
    await waitFor(() => expect(screen.getByTestId('cost')).toHaveTextContent('A-cost'));
    expect(screen.getByTestId('cluster-header')).toHaveTextContent('cluster-a');

    await user.click(screen.getByRole('button', { name: 'go to B in-page' }));

    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));
    await waitFor(() => expect(screen.getByTestId('cost')).toHaveTextContent('B-cost'));
    expect(screen.getByTestId('status-strip')).toHaveTextContent('B-dash');
    expect(screen.getByTestId('workloads')).toHaveTextContent('B-wl');
    expect(screen.getByTestId('cluster-header')).toHaveTextContent('cluster-b');

    // No trace of A survives anywhere on the page.
    expect(screen.queryByText(/A-dash|A-cost|A-wl/)).toBeNull();
  });

  it('drops a late A dashboard AND cost response resolved after B', async () => {
    const user = userEvent.setup();
    const dashA = deferred<any>();
    const costA = deferred<any>();
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/dashboard')) {
        return url.includes('cluster-a') ? dashA.promise : Promise.resolve({ clusterName: 'B-dash', lastSyncedAt: null });
      }
      if (url.includes('/automation/vertical/')) {
        return url.includes('cluster-a') ? costA.promise : Promise.resolve({ cost: { tag: 'B-cost' }, workloads: [], engine: {} });
      }
      return Promise.resolve([]);
    });
    renderAt(linkFor('cluster-a'));
    await waitFor(() => expect(apiGet.mock.calls.some((c) => String(c[0]).includes('cluster-a') && String(c[0]).includes('/dashboard'))).toBe(true));

    await user.click(screen.getByRole('button', { name: 'go to B in-page' }));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));
    await waitFor(() => expect(screen.getByTestId('cost')).toHaveTextContent('B-cost'));

    // A's responses land late; both must be dropped.
    dashA.resolve({ clusterName: 'A-dash', lastSyncedAt: null });
    costA.resolve({ cost: { tag: 'A-cost' }, workloads: [{ tag: 'A-wl' }], engine: {} });
    await Promise.resolve();
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));
    expect(screen.getByTestId('cost')).toHaveTextContent('B-cost');
    expect(screen.queryByText(/A-dash|A-cost|A-wl/)).toBeNull();
  });

  it('an in-flight A then B stays B with no flash of A', async () => {
    const user = userEvent.setup();
    const dashA = deferred<any>();
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/dashboard')) {
        return url.includes('cluster-a') ? dashA.promise : Promise.resolve({ clusterName: 'B-dash', lastSyncedAt: null });
      }
      if (url.includes('/automation/vertical/')) {
        return Promise.resolve({ cost: { tag: url.includes('cluster-a') ? 'A-cost' : 'B-cost' }, workloads: [], engine: {} });
      }
      return Promise.resolve([]);
    });
    renderAt(linkFor('cluster-a'));
    // A is still in flight (never shown). Switch to B.
    await user.click(screen.getByRole('button', { name: 'go to B in-page' }));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));

    // Now A finally resolves — it is a stale token and must not flash onto the page.
    dashA.resolve({ clusterName: 'A-dash', lastSyncedAt: null });
    await Promise.resolve();
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));
    expect(screen.queryByText('A-dash')).toBeNull();
  });

  it('keeps ?tab=workloads&sub=pods while the selected workload and container filter reset on switch', async () => {
    const user = userEvent.setup();
    routeGet(
      (url) => ({ clusterName: url.includes('cluster-a') ? 'A-dash' : 'B-dash', lastSyncedAt: null }),
      (url) => ({ cost: { tag: url.includes('cluster-a') ? 'A-cost' : 'B-cost' }, workloads: [], engine: {} }),
    );
    renderAt(linkFor('cluster-a', '&tab=workloads&sub=pods'));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('A-dash'));

    // Pick a workload and set a container namespace filter on A.
    await user.click(screen.getByRole('button', { name: 'select workload' }));
    await user.click(screen.getByRole('button', { name: 'set filter' }));
    expect(screen.getByTestId('selected-workload')).toHaveTextContent('from-A');
    expect(screen.getByTestId('filter')).toHaveTextContent('from-A');

    await user.click(screen.getByRole('button', { name: 'go to B in-page' }));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));

    // The tab/sub are preserved on the URL; the per-cluster selections reset.
    expect(currentUrl().searchParams.get('tab')).toBe('workloads');
    expect(currentUrl().searchParams.get('sub')).toBe('pods');
    expect(screen.getByTestId('selected-workload')).toHaveTextContent('none');
    expect(screen.getByTestId('filter')).toHaveTextContent('none');
  });

  it('remounts the page key on a cluster change even when navigated with inPageState', async () => {
    const user = userEvent.setup();
    routeGet(
      (url) => ({ clusterName: url.includes('cluster-a') ? 'A-dash' : 'B-dash', lastSyncedAt: null }),
      () => ({ cost: null, workloads: [], engine: {} }),
    );
    renderAt(linkFor('cluster-a'));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('A-dash'));

    // Set a per-cluster selection, then navigate WITH inPageState (pageKey carried).
    await user.click(screen.getByRole('button', { name: 'select workload' }));
    expect(screen.getByTestId('selected-workload')).toHaveTextContent('from-A');

    await user.click(screen.getByRole('button', { name: 'go to B in-page' }));
    await waitFor(() => expect(screen.getByTestId('dash')).toHaveTextContent('B-dash'));
    // A remount dropped the local selection state — proof the key changed despite inPageState.
    expect(screen.getByTestId('selected-workload')).toHaveTextContent('none');
  });
});
