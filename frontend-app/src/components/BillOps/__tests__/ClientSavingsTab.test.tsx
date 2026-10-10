import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { ClientSavingsDashboardData } from '../../../types/clientSavings';

const hookState = vi.hoisted(() => ({ data: null as unknown }));

vi.mock('../../../hooks/useClientSavings', () => ({
  useClientSavings: () => ({
    data: hookState.data,
    isLoading: false,
    error: null,
    preset: '30d',
    customStart: '',
    customEnd: '',
    setPreset: vi.fn(),
    setCustomRange: vi.fn(),
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

describe('ClientSavingsTab legacy spot savings', () => {
  beforeEach(() => {
    hookState.data = null;
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

    // The totals are the verified figures alone: Total, Realized, the AWS row and the Total row.
    expect(screen.getAllByText('$50')).toHaveLength(4);
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
