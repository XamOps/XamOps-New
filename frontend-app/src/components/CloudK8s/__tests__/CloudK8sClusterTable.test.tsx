import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { EKSCluster } from '../../../pages/CloudK8sPage';
import CloudK8sClusterTable, { CONTAINER_INSIGHTS_TOOLTIP } from '../CloudK8sClusterTable';

const cluster = (name: string, over: Partial<EKSCluster> = {}): EKSCluster => ({
  name,
  region: 'us-east-1',
  version: '1.29',
  connected: false,
  ...over,
});

/** The table links to /add-account and /account-manager in its empty state, so a router is needed. */
function renderTable(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('fleet table: the Container Insights column (demo-cut review S3)', () => {
  it('names the check it shows, and reads each state from the backend detail', () => {
    renderTable(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={() => {}}
        clusters={[
          cluster('configured', { connected: true, containerInsights: { status: 'CONFIGURED' } }),
          cluster('not-configured', { containerInsights: { status: 'NOT_CONFIGURED' } }),
          cluster('denied', { containerInsights: { status: 'UNKNOWN', reason: 'MISSING_AWS_PERMISSION', action: 'logs:DescribeLogGroups' } }),
          // A list cached before the detail existed: its old `connected` meant something else.
          cluster('cached', { connected: true }),
        ]}
      />,
    );
    const header = screen.getByRole('columnheader', { name: 'Container Insights' });
    expect(header).toHaveAttribute('title', CONTAINER_INSIGHTS_TOOLTIP);
    expect(screen.queryByText('Connected')).toBeNull();
    // Columns: Cluster(0) Region(1) Status(2) Version(3) Container Insights(4) Action(5).
    const row = (name: string) => document.querySelector<HTMLElement>(`[data-cluster-name="${name}"]`)!;
    const cell = (name: string) => within(row(name)).getAllByRole('cell')[4];
    expect(cell('configured')).toHaveTextContent('Configured');
    expect(cell('not-configured')).toHaveTextContent('Not configured');
    expect(cell('denied')).toHaveTextContent('Unknown');
    expect(cell('denied').querySelector('[title]')).toHaveAttribute('title', "XamOps couldn't check: the role is denied logs:DescribeLogGroups.");
    expect(cell('cached')).toHaveTextContent('Unknown');
  });
});

describe('fleet table: the Status column (EKS lifecycle)', () => {
  it('ACTIVE reads "Active", a missing status reads an em-dash unknown', () => {
    renderTable(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={() => {}}
        clusters={[
          cluster('active', { status: 'ACTIVE' }),
          cluster('creating', { status: 'CREATING' }),
          cluster('failed', { status: 'FAILED' }),
          cluster('nostatus'),
        ]}
      />,
    );
    const statusCell = (name: string) => within(document.querySelector<HTMLElement>(`[data-cluster-name="${name}"]`)!).getAllByRole('cell')[2];
    expect(statusCell('active')).toHaveTextContent('Active');
    expect(statusCell('creating')).toHaveTextContent('creating');
    expect(statusCell('failed')).toHaveTextContent('Failed');
    expect(statusCell('nostatus')).toHaveTextContent('—');
  });
});

describe('fleet table: v3 chrome (no coloured pills, no coins, no extra columns)', () => {
  it('has no blue pill / rounded-full tints and no Nodes/Cost/Agent columns', () => {
    const { container } = renderTable(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={() => {}}
        clusters={[cluster('prod', { status: 'ACTIVE' })]}
      />,
    );
    expect(container.querySelector('.bg-blue-50')).toBeNull();
    expect(container.querySelector('.rounded-full')).toBeNull();
    expect(container.querySelector('.bg-indigo-600')).toBeNull();
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).not.toContain('Nodes');
    expect(headers).not.toContain('Cost');
    expect(headers).not.toContain('Agent');
  });

  it('Details navigates with the exact cluster via onViewDetails, with an aria-label', () => {
    const onViewDetails = vi.fn();
    renderTable(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={onViewDetails}
        clusters={[cluster('prod', { status: 'ACTIVE' })]}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Details for prod' }));
    expect(onViewDetails).toHaveBeenCalledWith(expect.objectContaining({ name: 'prod', region: 'us-east-1', version: '1.29' }));
  });
});

describe('fleet table: state matrix', () => {
  it('loading shows a skeleton + aria-busy and NEVER a count', () => {
    renderTable(<CloudK8sClusterTable isLoading onViewDetails={() => {}} clusters={[]} />);
    expect(screen.getByTestId('fleet-loading')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading clusters…')).toBeInTheDocument();
    // The count is NEVER rendered while loading.
    expect(screen.queryByText(/\d+ clusters?/)).toBeNull();
    expect(screen.queryByTestId('fleet-table')).toBeNull();
  });

  it('isRefreshing keeps the rows and shows no skeleton', () => {
    renderTable(
      <CloudK8sClusterTable
        isLoading
        isRefreshing
        onViewDetails={() => {}}
        clusters={[cluster('prod', { status: 'ACTIVE' })]}
      />,
    );
    expect(screen.queryByTestId('fleet-loading')).toBeNull();
    expect(screen.getByTestId('fleet-table')).toBeInTheDocument();
    expect(document.querySelector('[data-cluster-name="prod"]')).not.toBeNull();
  });

  it('after 8s of loading it shows the "Scanning regions" line', () => {
    vi.useFakeTimers();
    try {
      renderTable(<CloudK8sClusterTable isLoading onViewDetails={() => {}} clusters={[]} />);
      expect(screen.queryByText(/Scanning regions/)).toBeNull();
      act(() => { vi.advanceTimersByTime(8000); });
      expect(screen.getByText('Scanning regions for clusters. This can take a little while.')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('an error shows role="alert" + Retry (reload), no count and no table', () => {
    const onRetry = vi.fn();
    renderTable(
      <CloudK8sClusterTable isLoading={false} error="server" onRetry={onRetry} onViewDetails={() => {}} clusters={[]} />,
    );
    expect(screen.getByTestId('fleet-error')).toHaveAttribute('role', 'alert');
    expect(screen.queryByText(/0 clusters/)).toBeNull();
    expect(screen.queryByTestId('fleet-table')).toBeNull();
    fireEvent.click(screen.getByTestId('fleet-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('each error kind has its own honest copy', () => {
    const { rerender } = renderTable(
      <CloudK8sClusterTable isLoading={false} error="unauthorized" onRetry={() => {}} onViewDetails={() => {}} clusters={[]} />,
    );
    expect(screen.getByText("You're not signed in")).toBeInTheDocument();
    rerender(<MemoryRouter><CloudK8sClusterTable isLoading={false} error="network" onRetry={() => {}} onViewDetails={() => {}} clusters={[]} /></MemoryRouter>);
    expect(screen.getByText("Couldn't reach XamOps")).toBeInTheDocument();
    rerender(<MemoryRouter><CloudK8sClusterTable isLoading={false} error="incomplete" onRetry={() => {}} onViewDetails={() => {}} clusters={[]} /></MemoryRouter>);
    expect(screen.getByText('The scan finished with gaps')).toBeInTheDocument();
  });

  it('ready with clusters shows the table with "n clusters" and per-row testids', () => {
    renderTable(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={() => {}}
        clusters={[cluster('a', { status: 'ACTIVE' }), cluster('b', { status: 'ACTIVE' })]}
      />,
    );
    expect(screen.getByText('2 clusters')).toBeInTheDocument();
    const rows = screen.getAllByTestId('fleet-row');
    expect(rows).toHaveLength(2);
    expect(rows[0].getAttribute('data-cluster-name')).toBe('a');
  });

  it('ready with zero clusters shows the honest empty state, not a coming-soon tile', () => {
    const onRescan = vi.fn();
    renderTable(<CloudK8sClusterTable isLoading={false} onViewDetails={() => {}} onRescan={onRescan} clusters={[]} />);
    const empty = screen.getByTestId('fleet-empty');
    expect(empty).not.toHaveAttribute('role', 'alert');
    expect(within(empty).getByText('No clusters found in this account')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Re-scan' }));
    expect(onRescan).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('link', { name: 'Add an account' })).toHaveAttribute('href', '/add-account');
    expect(screen.getByRole('link', { name: 'Manage accounts' })).toHaveAttribute('href', '/account-manager');
  });
});

describe('fleet table: no roadmap / coming-soon copy in any state', () => {
  it.each([
    ['loading', <CloudK8sClusterTable key="l" isLoading onViewDetails={() => {}} clusters={[]} />],
    ['error', <CloudK8sClusterTable key="e" isLoading={false} error="server" onRetry={() => {}} onViewDetails={() => {}} clusters={[]} />],
    ['empty', <CloudK8sClusterTable key="m" isLoading={false} onRescan={() => {}} onViewDetails={() => {}} clusters={[]} />],
    ['ready', <CloudK8sClusterTable key="r" isLoading={false} onViewDetails={() => {}} clusters={[cluster('a', { status: 'ACTIVE' })]} />],
  ])('%s state has no coming-soon / integration-pending / roadmap copy', (_name, ui) => {
    renderTable(ui as React.ReactElement);
    expect(screen.queryByText(/coming soon/i)).toBeNull();
    expect(screen.queryByText(/integration pending/i)).toBeNull();
    expect(screen.queryByText(/roadmap/i)).toBeNull();
  });
});
