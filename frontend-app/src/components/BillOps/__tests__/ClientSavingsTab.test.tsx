import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ClientSavingsDashboardData } from '../../../types/clientSavings';

const hookState = vi.hoisted(() => ({
  data: null as unknown,
  accountId: null as number | null,
  setAccountId: null as unknown as ReturnType<typeof import('vitest').vi.fn>,
}));

vi.mock('../../../hooks/useClientSavings', () => ({
  useClientSavings: () => ({
    data: hookState.data,
    isLoading: false,
    error: null,
    preset: '30d',
    customStart: '',
    customEnd: '',
    accountId: hookState.accountId,
    setPreset: vi.fn(),
    setCustomRange: vi.fn(),
    setAccountId: hookState.setAccountId,
    load: vi.fn(),
  }),
}));

// The charts are not under test, and ResponsiveContainer needs a layout jsdom doesn't have.
vi.mock('recharts', () => {
  const Stub = () => null;
  return {
    ResponsiveContainer: ({ children }: { children?: unknown }) => <div>{children as never}</div>,
    BarChart: Stub, Bar: Stub, LineChart: Stub, Line: Stub, XAxis: Stub, YAxis: Stub,
    CartesianGrid: Stub, Tooltip: Stub, Legend: Stub,
  };
});

import ClientSavingsTab from '../ClientSavingsTab';

const base: ClientSavingsDashboardData = {
  realizedSpotAws: 0,
  realizedSpotGcp: 0,
  realizedEbsAws: 50,
  potentialRightsizingAws: 0,
  potentialRightsizingGcp: 0,
  potentialRightsizingAzure: 0,
  potentialReservationsAws: 0,
  potentialReservationsGcp: 0,
  potentialReservationsAzure: 0,
  realizedTotal: 50,
  potentialTotal: 0,
  grandTotal: 50,
  timeSeries: [{ date: '2026-10-01', awsSpot: 0, gcpSpot: 0, awsEbs: 50 }],
  startDate: '2026-09-02',
  endDate: '2026-10-01',
  clientName: 'Example Client',
  accountCount: 2,
  cacheRefreshedAt: null,
};

const accounts = [
  { id: 129, name: 'xammer-local', provider: 'AWS', providerAccountId: '605134457560' },
  { id: 138, name: 'xammer-gcp', provider: 'GCP', providerAccountId: 'xamops' },
  { id: 135, name: 'GSA Electronics', provider: 'AWS', providerAccountId: null },
];

const withPotential: ClientSavingsDashboardData = {
  ...base,
  realizedEbsAws: 0,
  realizedTotal: 0,
  timeSeries: [],
  potentialRightsizingAws: 0.55,
  potentialOtherAws: 92.55,
  potentialOtherGcp: 58.24,
  potentialOtherAzure: 13.14,
  potentialTotal: 164.48,
  cacheRefreshedAt: '2026-09-02',
  accounts: [
    { accountId: 138, accountName: 'xammer-gcp', provider: 'GCP', providerAccountId: 'xamops',
      rightsizing: 0, reservations: 0, other: 58.24, potentialTotal: 58.24, realizedTotal: 0, snapshotDate: '2026-09-20' },
    { accountId: 129, accountName: 'xammer-local', provider: 'AWS', providerAccountId: '605134457560',
      rightsizing: 0.55, reservations: 0, other: 92.55, potentialTotal: 93.10, realizedTotal: 0, snapshotDate: '2026-09-02' },
    { accountId: 135, accountName: 'GSA Electronics', provider: 'AWS', providerAccountId: null,
      rightsizing: 0, reservations: 0, other: 0, potentialTotal: 0, realizedTotal: 0, snapshotDate: null },
  ],
};

describe('ClientSavingsTab legacy spot savings', () => {
  beforeEach(() => {
    hookState.data = null;
    hookState.accountId = null;
    hookState.setAccountId = vi.fn();
  });

  it('shows legacy amounts only in their own row, beside the label, and never in a total', () => {
    hookState.data = { ...base, legacySpotUnverifiedAws: 1234, legacySpotUnverifiedGcp: 567 };

    render(<ClientSavingsTab clientId={7} />);

    const row = screen.getByTestId('legacy-spot-row');
    expect(within(row).getByText('Legacy (unverified)')).toBeInTheDocument();
    expect(within(row).getByText('$1,234')).toBeInTheDocument();
    expect(within(row).getByText('$567')).toBeInTheDocument();

    // Each legacy amount appears once, in that row, and nothing adds it into a figure.
    expect(screen.getAllByText('$1,234')).toHaveLength(1);
    expect(screen.getAllByText('$567')).toHaveLength(1);
    expect(screen.queryByText(/1,801/)).not.toBeInTheDocument();
    expect(screen.queryByText(/1,851/)).not.toBeInTheDocument();
    expect(screen.queryByText(/1,284/)).not.toBeInTheDocument();

    // The realized figures are the verified ones alone: the Realized card, the AWS row and the Total row.
    expect(screen.getAllByText('$50')).toHaveLength(3);
  });

  it('renders no legacy row when there is no legacy amount, or when the field is absent', () => {
    hookState.data = { ...base, legacySpotUnverifiedAws: 0, legacySpotUnverifiedGcp: 0 };
    const { unmount } = render(<ClientSavingsTab clientId={7} />);
    expect(screen.queryByTestId('legacy-spot-row')).not.toBeInTheDocument();
    expect(screen.queryByText('Legacy (unverified)')).not.toBeInTheDocument();
    unmount();

    // A response cached before the field existed.
    hookState.data = { ...base };
    render(<ClientSavingsTab clientId={7} />);
    expect(screen.queryByTestId('legacy-spot-row')).not.toBeInTheDocument();
  });
});

describe('ClientSavingsTab shadow and open-month spot figures (phase 17)', () => {
  beforeEach(() => {
    hookState.data = null;
  });

  it('shows a shadow month only under "Not invoiced" and never beside Billable or Realized', () => {
    hookState.data = { ...base, shadowNotInvoicedAws: '123.46', shadowNotInvoicedGcp: '0.00' };
    render(<ClientSavingsTab clientId={7} />);
    const row = screen.getByTestId('not-invoiced-row');
    expect(within(row).getByText('Not invoiced')).toBeInTheDocument();
    expect(within(row).getByText('$123.46')).toBeInTheDocument();
    expect(within(row).queryByText(/billable/i)).not.toBeInTheDocument();
    expect(within(row).queryByText(/realized/i)).not.toBeInTheDocument();
    // The shadow amount is in no total and no KPI.
    expect(screen.getAllByText('$123.46')).toHaveLength(1);
    expect(screen.getAllByText('$50')).toHaveLength(3);
  });

  it('shows the open month only under "Accruing (not final)"', () => {
    hookState.data = { ...base, accruingSpotAws: '9.72' };
    render(<ClientSavingsTab clientId={7} />);
    const row = screen.getByTestId('accruing-row');
    expect(within(row).getByText('Accruing (not final)')).toBeInTheDocument();
    expect(within(row).getByText('$9.72')).toBeInTheDocument();
    expect(within(row).queryByText(/billable|realized/i)).not.toBeInTheDocument();
    expect(screen.getAllByText('$9.72')).toHaveLength(1);
    expect(screen.queryByTestId('not-invoiced-row')).not.toBeInTheDocument();
  });

  it('adds a realized (invoiceable) figure to the totals and names its months', () => {
    hookState.data = { ...base, realizedSpotAws: '244.48', realizedTotal: 294.48, grandTotal: 294.48, realizedSpotMonths: ['2026-08'] };
    render(<ClientSavingsTab clientId={7} />);
    expect(screen.getByTestId('realized-months')).toHaveTextContent('2026-08');
    expect(screen.queryByTestId('not-invoiced-row')).not.toBeInTheDocument();
    expect(screen.queryByTestId('accruing-row')).not.toBeInTheDocument();
  });

  it('labels the daily series as an accrual before statement rounding', () => {
    hookState.data = { ...base, spotSeriesLabel: 'Daily accrual, before statement rounding' };
    render(<ClientSavingsTab clientId={7} />);
    expect(screen.getByText('Daily accrual, before statement rounding')).toBeInTheDocument();
  });
});

describe('ClientSavingsTab potential savings and account filter', () => {
  beforeEach(() => {
    hookState.data = null;
    hookState.accountId = null;
    hookState.setAccountId = vi.fn();
  });

  it('totals potential savings across all three columns and keeps realized out of it', () => {
    hookState.data = withPotential;
    render(<ClientSavingsTab clientId={1} accounts={accounts} />);

    // 0.55 + 92.55 + 58.24 + 13.14 = 164.48
    const total = screen.getByText('Total potential').parentElement!;
    expect(within(total).getByText('$164')).toBeInTheDocument();
    const other = screen.getByText('Other optimizations').parentElement!;
    expect(within(other).getByText('$164')).toBeInTheDocument(); // 92.55 + 58.24 + 13.14 = 163.93
    // Cents are kept under $10, so a $0.55 saving doesn't read as $1.
    const rightsizing = screen.getAllByText('Rightsizing')[0].parentElement!;
    expect(within(rightsizing).getByText('$0.55')).toBeInTheDocument();
    // No combined "realized + estimated" figure any more.
    expect(screen.queryByText('Total Savings')).not.toBeInTheDocument();
    expect(screen.getByText(/as of 2026-09-02/)).toBeInTheDocument();
  });

  it('offers every account in the filter, disables those without an account ID, and filters on change', async () => {
    hookState.data = withPotential;
    const user = userEvent.setup();
    render(<ClientSavingsTab clientId={1} accounts={accounts} />);

    const select = screen.getByLabelText('Account:') as HTMLSelectElement;
    const labels = Array.from(select.options).map(o => o.textContent);
    expect(labels).toEqual([
      'All accounts (2)',
      'GSA Electronics · AWS (no account ID)',
      'xammer-gcp · GCP',
      'xammer-local · AWS',
    ]);
    expect(Array.from(select.options).find(o => o.value === '135')!.disabled).toBe(true);

    await user.selectOptions(select, '129');
    expect(hookState.setAccountId).toHaveBeenCalledWith(129);
    await user.selectOptions(select, '');
    expect(hookState.setAccountId).toHaveBeenLastCalledWith(null);
  });

  it('lists accounts by potential in the all-accounts view, and a row click filters to it', async () => {
    hookState.data = withPotential;
    const user = userEvent.setup();
    render(<ClientSavingsTab clientId={1} accounts={accounts} />);

    const table = screen.getByTestId('account-savings-table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map(r => within(r).getAllByRole('cell')[0].textContent)).toEqual([
      'AWSxammer-local', 'GCPxammer-gcp', 'AWSGSA Electronics',
    ]);
    const firstCells = within(rows[0]).getAllByRole('cell').map(c => c.textContent);
    expect(firstCells).toEqual(['AWSxammer-local', '$0.55', '—', '$93', '$93', '2026-09-02']);

    await user.click(rows[1]);
    expect(hookState.setAccountId).toHaveBeenCalledWith(138);
    // An account without an ID has nothing to filter to.
    await user.click(rows[2]);
    expect(hookState.setAccountId).toHaveBeenCalledTimes(1);
  });

  it('hides the per-account table once filtered to one account', () => {
    hookState.accountId = 129;
    hookState.data = { ...withPotential, selectedAccountId: 129, accounts: [withPotential.accounts![1]] };
    render(<ClientSavingsTab clientId={1} accounts={accounts} />);

    expect(screen.queryByTestId('account-savings-table')).not.toBeInTheDocument();
    expect((screen.getByLabelText('Account:') as HTMLSelectElement).value).toBe('129');
  });

  it('hides the filter when no account list is passed', () => {
    hookState.data = base;
    render(<ClientSavingsTab clientId={7} />);
    expect(screen.queryByLabelText('Account:')).not.toBeInTheDocument();
  });
});
