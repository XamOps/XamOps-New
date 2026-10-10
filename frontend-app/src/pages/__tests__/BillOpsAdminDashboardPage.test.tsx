import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('chart.js/auto', () => ({ default: class { destroy() {} } }));

const dashboard = vi.hoisted(() => ({
  data: null,
  loading: false,
  isRefreshing: false,
  isRefreshingTopSpending: false,
  error: null,
  refreshDashboard: () => {},
  refreshTopSpending: () => {},
}));
vi.mock('../../hooks/useBillOpsAdminDashboard', () => ({ useBillOpsAdminDashboard: () => dashboard }));

const topbar = vi.hoisted(() => ({ setActions: () => {}, clearActions: () => {} }));
vi.mock('../../contexts/TopbarContext', () => ({ useTopbar: () => topbar }));

// Stable references: the page lists both in effect and memo dependencies.
const currency = vi.hoisted(() => ({ loadRates: () => {}, convert: (amount: number) => amount }));
vi.mock('../../hooks/useCurrency', () => ({ useCurrency: () => currency }));

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import BillOpsAdminDashboardPage from '../BillOpsAdminDashboardPage';

const SAVINGS_URL = '/api/xamops/autospotting-admin/customer-savings';

const savingsRows = [
  // Today's shape: no verified savings, legacy reported apart.
  {
    clientId: 1, clientName: 'Example Client', awsSavings: 0, gcpSavings: 0, totalSavings: 0,
    legacyUnverifiedAws: 1000.5, legacyUnverifiedGcp: 500.25, legacyUnverifiedTotal: 1500.75,
  },
  // Verified and legacy together: Total Saved is the verified figure alone.
  {
    clientId: 2, clientName: 'Sample Client', awsSavings: 10, gcpSavings: 5, totalSavings: 15,
    legacyUnverifiedAws: 100, legacyUnverifiedGcp: 0, legacyUnverifiedTotal: 100,
  },
];

const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/billops-admin-dashboard']}>
      <BillOpsAdminDashboardPage />
    </MemoryRouter>,
  );
}

/** The AutoSpotting card's table, once its rows have loaded. */
async function savingsTable() {
  return (await screen.findByText('Example Client')).closest('table') as HTMLElement;
}

const cellsOf = (table: HTMLElement, clientName: string) =>
  within(within(table).getByText(clientName).closest('tr') as HTMLElement)
    .getAllByRole('cell')
    .map((td) => td.textContent);

describe('BillOpsAdminDashboardPage AutoSpotting card', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockImplementation(async (url: string) => {
      if (url === '/api/xamops/billops-admin/clients') return ok([]);
      if (url === SAVINGS_URL) return ok(savingsRows);
      return { ok: false, status: 404, json: async () => null };
    });
    // jsdom has no canvas; the charts are not under test.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reads customer savings from the routable admin path, never /api/admin/', async () => {
    renderPage();
    await savingsTable();

    const urls = apiFetch.mock.calls.map((call) => call[0] as string);
    expect(urls).toContain(SAVINGS_URL);
    expect(urls.filter((url) => url.startsWith('/api/admin/'))).toEqual([]);
  });

  it('shows the legacy total only in its own column, never in AWS, GCP or Total Saved', async () => {
    renderPage();
    const table = await savingsTable();

    const headers = within(table).getAllByRole('columnheader').map((th) => th.textContent);
    expect(headers).toEqual(['Customer', 'AWS', 'GCP', 'Total Saved', 'Legacy (unverified)']);

    expect(cellsOf(table, 'Example Client')).toEqual(['Example Client', '—', '—', '$0.00', '$1,500.75']);
    expect(cellsOf(table, 'Sample Client')).toEqual(['Sample Client', '$10.00', '$5.00', '$15.00', '$100.00']);

    // Each legacy total appears once; the per-provider legacy split is not shown or added anywhere.
    expect(screen.getAllByText('$1,500.75')).toHaveLength(1);
    expect(screen.queryByText('$1,000.50')).not.toBeInTheDocument();
    expect(screen.queryByText('$500.25')).not.toBeInTheDocument();
    expect(screen.queryByText('$115.00')).not.toBeInTheDocument();
  });
});
