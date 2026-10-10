import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';

const chartConfigs = vi.hoisted(() => [] as Array<{ type: string; data: { datasets: Array<{ label: string; data: unknown[] }> } }>);

vi.mock('chart.js/auto', () => ({
  default: class {
    constructor(_ctx: unknown, config: (typeof chartConfigs)[number]) {
      chartConfigs.push(config);
    }
    destroy() {}
  },
}));

const topbar = vi.hoisted(() => ({ setActions: () => {}, clearActions: () => {} }));
vi.mock('../../contexts/TopbarContext', () => ({ useTopbar: () => topbar }));

const cache = vi.hoisted(() => ({ getAdminData: () => null, setAdminData: () => {} }));
vi.mock('../../contexts/AutoSpottingCacheContext', () => ({ useAutoSpottingCache: () => cache }));

import AutoSpottingAdminDashboardPage from '../AutoSpottingAdminDashboardPage';

const LEGACY_TOTAL = 1500.75;

const responses: Record<string, unknown> = {
  '/api/xamops/autospotting-admin/total-savings': {
    totalSavings: 0,
    legacyUnverifiedTotal: LEGACY_TOTAL,
    activeSpotInstances: 2,
  },
  '/api/xamops/autospotting-admin/recent-activity?limit=50': [],
  '/api/xamops/autospotting-admin/historical-savings?days=7': [
    { date: '2026-10-05', savings: null, legacySavings: 10, instanceCount: 2 },
    { date: '2026-10-06', savings: null, legacySavings: 5, instanceCount: 1 },
  ],
  '/api/xamops/autospotting-admin/account-breakdown': [
    { accountId: '000000000001', accountName: 'Example Client', totalSavings: 0, legacyUnverifiedSavings: 7.5, instanceCount: 3 },
  ],
};

describe('AutoSpottingAdminDashboardPage', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  /** Per-test replacements for entries of `responses`. */
  let overrides: Record<string, unknown>;

  beforeEach(() => {
    chartConfigs.length = 0;
    overrides = {};
    fetchMock = vi.fn(async (url: string) => {
      const served = { ...responses, ...overrides };
      if (!(url in served)) return { ok: false, status: 404, json: async () => null };
      return { ok: true, status: 200, json: async () => served[url] };
    });
    vi.stubGlobal('fetch', fetchMock);
    // jsdom has no canvas; the page only needs a non-null context to build its charts.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as never);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('reads the routable admin paths, never /api/admin/', async () => {
    render(<AutoSpottingAdminDashboardPage />);
    await screen.findByTestId('legacy-total-card');

    const urls = fetchMock.mock.calls.map((call) => call[0] as string);
    expect(urls).toEqual(expect.arrayContaining(Object.keys(responses)));
    expect(urls.filter((url) => url.startsWith('/api/admin/'))).toEqual([]);
  });

  it('shows the legacy amount only beside "Legacy (unverified)" and keeps it out of the totals', async () => {
    render(<AutoSpottingAdminDashboardPage />);

    const card = await screen.findByTestId('legacy-total-card');
    expect(within(card).getByText('Legacy (unverified)')).toBeInTheDocument();
    expect(within(card).getByText('$1,500.75')).toBeInTheDocument();
    expect(screen.getAllByText('$1,500.75')).toHaveLength(1);

    // Total Savings is the verified figure alone.
    const totalCard = screen.getByText('Total Savings').closest('div.bg-white') as HTMLElement;
    expect(within(totalCard).getByText('$0.00')).toBeInTheDocument();
  });

  it('has no conversion-rate or "monthly = total / 12" tile', async () => {
    render(<AutoSpottingAdminDashboardPage />);
    await screen.findByTestId('legacy-total-card');

    expect(screen.queryByText('Conversion Rate')).not.toBeInTheDocument();
    expect(screen.queryByText('Monthly Savings')).not.toBeInTheDocument();
    expect(screen.queryByText(/85\.0%/)).not.toBeInTheDocument();
  });

  it('charts legacy days on their own labelled series and leaves the verified series empty, not $0', async () => {
    render(<AutoSpottingAdminDashboardPage />);
    await screen.findByTestId('legacy-total-card');

    const trend = chartConfigs.find((config) => config.type === 'line');
    expect(trend).toBeDefined();
    const byLabel = Object.fromEntries(trend!.data.datasets.map((ds) => [ds.label, ds.data]));
    expect(byLabel['Daily Savings']).toEqual([null, null]);
    expect(byLabel['Legacy (unverified)']).toEqual([10, 5]);

    // No verified savings by account: no doughnut, a plain message instead.
    expect(chartConfigs.find((config) => config.type === 'doughnut')).toBeUndefined();
    expect(screen.getByText('No verified savings yet')).toBeInTheDocument();
  });

  it('shows a dash for a recent event without hourly savings, and still renders the rest', async () => {
    const event = { accountId: '000000000001', accountName: 'Example Client', eventType: 'LAUNCH', onDemandPrice: 0, spotPrice: 0 };
    overrides['/api/xamops/autospotting-admin/recent-activity?limit=50'] = [
      // An ASG config save: the backend stores it with no hourly savings, instance or region.
      { ...event, id: 2, timestamp: '2026-10-06T10:00:00Z', instanceId: null, instanceType: null, spotInstanceType: null, hourlySavings: null, region: null },
      { ...event, id: 1, timestamp: '2026-10-05T10:00:00Z', instanceId: 'i-0abc', instanceType: 'm5.large', spotInstanceType: 'm5.large', hourlySavings: 0.0123, region: 'us-east-1' },
    ];
    render(<AutoSpottingAdminDashboardPage />);

    const table = (await screen.findByText('Hourly Savings')).closest('table') as HTMLElement;
    const column = within(table).getAllByRole('columnheader').findIndex((th) => th.textContent === 'Hourly Savings');
    const [configSave, conversion] = within(table).getAllByRole('row').slice(1);
    expect(within(configSave).getAllByRole('cell')[column]).toHaveTextContent(/^—$/);
    expect(within(conversion).getAllByRole('cell')[column]).toHaveTextContent('$0.0123/hr');
    expect(within(conversion).getByText('i-0abc')).toBeInTheDocument();
  });
});
