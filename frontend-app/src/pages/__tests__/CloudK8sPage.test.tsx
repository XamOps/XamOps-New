import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * CloudK8sPage is the landing RESOLVER (FEAT-002, design §3.3): it opens /cloudk8s directly on a
 * cluster dashboard, keeps "All clusters" (?view=all) on demand, and never spins forever.
 *
 * The account context, auth, the per-account cluster hook and the fleet's child components are all
 * mocked so the test drives the resolver's inputs directly: auth readiness, the account list, the
 * selected account's provider, and the cluster-list state.
 */

const { authState, accountState, clustersState, readLastClusterMock } = vi.hoisted(() => ({
  authState: { authReady: true, userProfile: { email: 'jane@corp.example' } as { email: string } | null },
  accountState: {
    accounts: [] as Array<{ id: string; name: string; provider: string }>,
    selectedAccountIds: [] as string[],
    isLoading: false,
  },
  clustersState: {
    status: 'ready' as 'loading' | 'ready' | 'error',
    clusters: [] as EKSCluster[],
    errorKind: null as string | null,
    isRefreshing: false,
    reload: vi.fn(),
    rescan: vi.fn(),
    lastAccountId: null as string | null,
  },
  readLastClusterMock: vi.fn(() => null as { name: string; region: string } | null),
}));

vi.mock('../../contexts/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AuthContext')>();
  return { ...actual, useAuth: () => ({ authReady: authState.authReady, userProfile: authState.userProfile }) };
});
vi.mock('../../contexts/AccountContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../contexts/AccountContext')>();
  return {
    ...actual,
    useAccounts: () => {
      const selected = accountState.selectedAccountIds[0] ?? null;
      const acc = accountState.accounts.find((a) => a.id === selected);
      return {
        accounts: accountState.accounts,
        selectedAccountIds: accountState.selectedAccountIds,
        isLoading: accountState.isLoading,
        selectedAccountDisplay: {
          name: acc?.name ?? 'Select Accounts',
          id: acc?.id ?? (accountState.accounts.length === 0 && !accountState.isLoading ? 'No accounts connected' : 'No account selected'),
          provider: acc?.provider,
        },
      };
    },
  };
});
vi.mock('../../hooks/useK8sCacheIdentity', () => ({ useK8sCacheIdentity: () => {}, default: () => {} }));
vi.mock('../../hooks/useK8sClusters', () => ({
  useK8sClusters: (accountId: string | null) => {
    clustersState.lastAccountId = accountId;
    return {
      status: clustersState.status,
      clusters: clustersState.clusters,
      complete: null,
      failedRegions: [],
      errorKind: clustersState.errorKind,
      isRefreshing: clustersState.isRefreshing,
      reload: clustersState.reload,
      rescan: clustersState.rescan,
    };
  },
}));
vi.mock('../../utils/k8sLastCluster', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/k8sLastCluster')>();
  return { ...actual, readLastCluster: readLastClusterMock };
});
vi.mock('../../components/CloudK8s/CloudK8sHeader', () => ({ default: () => <div data-testid="fleet-header">Fleet header</div> }));
vi.mock('../../components/CloudK8s/CloudK8sClusterTable', () => ({
  default: ({ clusters, isLoading, error, onRetry }: { clusters: Array<{ name: string }>; isLoading: boolean; error?: string | null; onRetry?: () => void }) =>
    error ? (
      <div data-testid="fleet-error">
        Couldn't load your clusters
        <button type="button" onClick={onRetry}>Retry</button>
      </div>
    ) : (
      <div data-testid="fleet-table" data-loading={String(isLoading)} data-count={clusters.length}>
        Fleet table
      </div>
    ),
}));

import CloudK8sPage, { OPENING_TEXT, ACCOUNT_UNAVAILABLE_TITLE, CONNECT_ACCOUNT_TITLE, SELF_HEAL_MS, pickLandingCluster, type EKSCluster } from '../CloudK8sPage';
import { TopbarProvider } from '../../contexts/TopbarContext';

const AWS = { id: '123456789012', name: 'Prod', provider: 'AWS' };
const GCP = { id: '210987654321', name: 'GcpProj', provider: 'GCP' };
const AZURE = { id: '345678901234', name: 'AzSub', provider: 'Azure' };

function LocationProbe() {
  const location = useLocation();
  return (
    <output data-testid="location" data-state={JSON.stringify(location.state ?? null)}>
      {`${location.pathname}${location.search}`}
    </output>
  );
}

/** A stub for every provider/cluster route the resolver can redirect to. */
function renderAt(url: string) {
  return render(
    <TopbarProvider>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/cloudk8s" element={<CloudK8sPage />} />
          <Route path="/eks-details" element={<div>EKS details page</div>} />
          <Route path="/gcp-cloudk8s" element={<div>GCP CloudK8s page</div>} />
          <Route path="/azure-cloudk8s" element={<div>Azure CloudK8s page</div>} />
          <Route path="/account-manager" element={<div>Account manager</div>} />
          <Route path="/add-account" element={<div>Add account</div>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </TopbarProvider>,
  );
}

const cluster = (over: Partial<EKSCluster> = {}): EKSCluster => ({
  name: 'prod-eks', region: 'us-east-1', version: '1.30', connected: true, ...over,
});

const currentUrl = () => new URL(screen.getByTestId('location').textContent ?? '', 'http://test');
const landedOn = (path: string) => expect(currentUrl().pathname).toBe(path);

beforeEach(() => {
  authState.authReady = true;
  authState.userProfile = { email: 'jane@corp.example' };
  accountState.accounts = [AWS];
  accountState.selectedAccountIds = [AWS.id];
  accountState.isLoading = false;
  clustersState.status = 'ready';
  clustersState.clusters = [];
  clustersState.errorKind = null;
  clustersState.isRefreshing = false;
  clustersState.reload = vi.fn();
  clustersState.rescan = vi.fn();
  clustersState.lastAccountId = null;
  readLastClusterMock.mockReset().mockReturnValue(null);
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('pickLandingCluster (pure)', () => {
  const a = cluster({ name: 'a', region: 'us-east-1' });
  const b = cluster({ name: 'b', region: 'eu-west-1', containerInsights: { status: 'CONFIGURED' } });
  const c = cluster({ name: 'c', region: 'us-east-1' });

  it('returns null for an empty list', () => {
    expect(pickLandingCluster([], null)).toBeNull();
    expect(pickLandingCluster([], { name: 'a', region: 'us-east-1' })).toBeNull();
  });

  it('prefers the last cluster when it is still in the list by name+region', () => {
    expect(pickLandingCluster([a, b, c], { name: 'c', region: 'us-east-1' })).toBe(c);
    // A stale memory (name or region no longer present) falls through.
    expect(pickLandingCluster([a, b, c], { name: 'c', region: 'eu-west-1' })).not.toBe(c);
  });

  it('returns the sole cluster when there is exactly one', () => {
    expect(pickLandingCluster([a], null)).toBe(a);
  });

  it('with no memory, prefers the first CONFIGURED, else the first in list order (never re-sorted)', () => {
    expect(pickLandingCluster([a, b, c], null)).toBe(b);
    expect(pickLandingCluster([a, c], null)).toBe(a);
  });
});

describe('resolver redirect', () => {
  it('redirects (replace) to the last-remembered cluster when it is in the list', async () => {
    clustersState.clusters = [cluster({ name: 'a' }), cluster({ name: 'b', region: 'eu-west-1' })];
    readLastClusterMock.mockReturnValue({ name: 'b', region: 'eu-west-1' });
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/eks-details'));
    expect(Object.fromEntries(currentUrl().searchParams)).toMatchObject({ name: 'b', region: 'eu-west-1', accountIds: AWS.id });
  });

  it('redirects to the sole cluster when there is just one', async () => {
    clustersState.clusters = [cluster({ name: 'only', version: '1.31' })];
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/eks-details'));
    expect(currentUrl().searchParams.get('name')).toBe('only');
    expect(currentUrl().searchParams.get('version')).toBe('1.31');
  });

  it('with no memory, redirects to the first CONFIGURED else the first in list order', async () => {
    clustersState.clusters = [cluster({ name: 'first' }), cluster({ name: 'configured', containerInsights: { status: 'CONFIGURED' } })];
    renderAt('/cloudk8s');
    await waitFor(() => expect(currentUrl().searchParams.get('name')).toBe('configured'));
  });

  it('a stale memory (cluster gone) falls back and still resolves', async () => {
    clustersState.clusters = [cluster({ name: 'alive' })];
    readLastClusterMock.mockReturnValue({ name: 'ghost', region: 'us-east-1' });
    renderAt('/cloudk8s');
    await waitFor(() => expect(currentUrl().searchParams.get('name')).toBe('alive'));
  });

  it('redirects by replace (no extra history entry)', async () => {
    clustersState.clusters = [cluster({ name: 'only' })];
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/eks-details'));
    // Going back should NOT return to /cloudk8s (it was replaced).
    act(() => { window.history.back(); });
    await Promise.resolve();
    expect(currentUrl().pathname).not.toBe('/cloudk8s');
  });
});

describe('provider routing (selectedAccountDisplay.provider)', () => {
  it('a GCP account redirects to /gcp-cloudk8s and never fetches AWS clusters', async () => {
    accountState.accounts = [GCP];
    accountState.selectedAccountIds = [GCP.id];
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/gcp-cloudk8s'));
    expect(clustersState.lastAccountId).toBeNull();
  });

  it('an Azure account redirects to /azure-cloudk8s', async () => {
    accountState.accounts = [AZURE];
    accountState.selectedAccountIds = [AZURE.id];
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/azure-cloudk8s'));
  });

  it('an AWS account resolves on this page', async () => {
    clustersState.clusters = [cluster({ name: 'only' })];
    renderAt('/cloudk8s');
    await waitFor(() => landedOn('/eks-details'));
    expect(clustersState.lastAccountId).toBe(AWS.id);
  });
});

describe('fleet view is on-demand only', () => {
  it('?view=all never redirects, even with a remembered cluster', async () => {
    clustersState.clusters = [cluster({ name: 'only' })];
    readLastClusterMock.mockReturnValue({ name: 'only', region: 'us-east-1' });
    renderAt('/cloudk8s?view=all');
    await waitFor(() => expect(screen.getByTestId('fleet-table')).toBeInTheDocument());
    landedOn('/cloudk8s');
    expect(currentUrl().searchParams.get('view')).toBe('all');
  });

  it('a forwarded notice renders the fleet with the notice and never redirects', async () => {
    clustersState.clusters = [cluster({ name: 'only' })];
    readLastClusterMock.mockReturnValue({ name: 'only', region: 'us-east-1' });
    // Mirror the real flow: another page bounces here with the notice in navigation state.
    render(
      <TopbarProvider>
        <MemoryRouter initialEntries={['/from']}>
          <Routes>
            <Route path="/from" element={<Navigate to="/cloudk8s" replace state={{ notice: 'That cluster link is invalid.' }} />} />
            <Route path="/cloudk8s" element={<CloudK8sPage />} />
            <Route path="/eks-details" element={<div>EKS details page</div>} />
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </TopbarProvider>,
    );
    expect(await screen.findByText('That cluster link is invalid.')).toBeInTheDocument();
    landedOn('/cloudk8s');
    expect(screen.getByTestId('fleet-table')).toBeInTheDocument();
  });
});

describe('honest loading and terminal states (no perpetual spinner)', () => {
  it('shows "Opening your dashboard" while auth is not ready, with no cluster fetch', () => {
    authState.authReady = false;
    renderAt('/cloudk8s');
    expect(screen.getByText(OPENING_TEXT)).toBeInTheDocument();
    expect(screen.queryByTestId('fleet-table')).toBeNull();
    expect(clustersState.lastAccountId).toBeNull();
    landedOn('/cloudk8s');
  });

  it('shows "Opening your dashboard" while the account list is loading', () => {
    accountState.isLoading = true;
    renderAt('/cloudk8s');
    expect(screen.getByText(OPENING_TEXT)).toBeInTheDocument();
  });

  it('a selection-from-storage window (loading, empty list) waits, no fetch, then a GCP account redirects', async () => {
    // Selection present but the account list hasn't arrived yet: loading, no fetch.
    accountState.isLoading = true;
    accountState.accounts = [];
    accountState.selectedAccountIds = [GCP.id];
    const { rerender } = renderAt('/cloudk8s');
    expect(screen.getByText(OPENING_TEXT)).toBeInTheDocument();
    expect(clustersState.lastAccountId).toBeNull();

    // The account list arrives and names a GCP account.
    accountState.isLoading = false;
    accountState.accounts = [GCP];
    rerender(
      <TopbarProvider>
        <MemoryRouter initialEntries={['/cloudk8s']}>
          <Routes>
            <Route path="/cloudk8s" element={<CloudK8sPage />} />
            <Route path="/gcp-cloudk8s" element={<div>GCP CloudK8s page</div>} />
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </TopbarProvider>,
    );
    await waitFor(() => landedOn('/gcp-cloudk8s'));
  });

  it('an absent selected id reaches the terminal "that account isn\'t available" after the self-heal window', async () => {
    vi.useFakeTimers();
    accountState.accounts = [AWS];
    accountState.selectedAccountIds = ['999999999999']; // not in the list
    renderAt('/cloudk8s');
    expect(screen.getByText(OPENING_TEXT)).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(SELF_HEAL_MS + 10); });
    expect(screen.getByText(ACCOUNT_UNAVAILABLE_TITLE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Manage accounts' })).toHaveAttribute('href', '/account-manager');
    expect(screen.getByRole('link', { name: 'Add an account' })).toHaveAttribute('href', '/add-account');
  });

  it('no account at all reaches the terminal "connect an account" state', async () => {
    accountState.accounts = [];
    accountState.selectedAccountIds = [];
    renderAt('/cloudk8s');
    expect(await screen.findByText(CONNECT_ACCOUNT_TITLE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add an account' })).toHaveAttribute('href', '/add-account');
  });

  it('does not read a cluster list while auth is not ready (no anonymous-keyed read)', () => {
    authState.authReady = false;
    accountState.accounts = [AWS];
    accountState.selectedAccountIds = [AWS.id];
    renderAt('/cloudk8s');
    expect(readLastClusterMock).not.toHaveBeenCalled();
    expect(clustersState.lastAccountId).toBeNull();
  });
});

describe('fleet view states', () => {
  it('a cluster-list error shows an error (no redirect) with a Retry', async () => {
    clustersState.status = 'error';
    clustersState.errorKind = 'server';
    clustersState.clusters = [];
    renderAt('/cloudk8s?view=all');
    expect(await screen.findByText("Couldn't load your clusters")).toBeInTheDocument();
    landedOn('/cloudk8s');
  });

  it('loading the cluster list in the fleet view shows the table in its loading state, no redirect', async () => {
    clustersState.status = 'loading';
    renderAt('/cloudk8s?view=all');
    const table = await screen.findByTestId('fleet-table');
    expect(table).toHaveAttribute('data-loading', 'true');
    landedOn('/cloudk8s');
  });

  it('an empty list in the fleet view renders the table, not a redirect', async () => {
    clustersState.status = 'ready';
    clustersState.clusters = [];
    renderAt('/cloudk8s?view=all');
    const table = await screen.findByTestId('fleet-table');
    expect(table).toHaveAttribute('data-count', '0');
    landedOn('/cloudk8s');
  });

  it('never mounts the COMING SOON integrations tiles or a roadmap line', async () => {
    clustersState.status = 'ready';
    clustersState.clusters = [cluster({ name: 'only' })];
    renderAt('/cloudk8s?view=all');
    await screen.findByTestId('fleet-table');
    expect(screen.queryByText(/coming soon/i)).toBeNull();
    expect(screen.queryByText(/integration pending/i)).toBeNull();
    expect(screen.queryByText(/roadmap/i)).toBeNull();
  });
});
