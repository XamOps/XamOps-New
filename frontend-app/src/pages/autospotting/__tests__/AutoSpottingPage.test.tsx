import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders, row } from './helpers';

vi.mock('../../../contexts/AccountContext', () => ({
  useAccounts: () => ({
    accounts: [{ id: '123456789012', dbId: 7, name: 'Prod', provider: 'AWS', status: 'CONNECTED' }],
    selectedAccountIds: ['123456789012'],
  }),
}));

import AutoSpottingPage from '../AutoSpottingPage';

afterEach(() => vi.unstubAllGlobals());

const config = (over: Record<string, unknown> = {}, provider: Record<string, unknown> = {}) => ({
  shellEnabled: true,
  dryRun: false,
  engagedSwitches: [],
  providers: {
    AWS: { enabled: true, conversionsEnabled: true, allowlistOnly: false, v2MutationsEnabled: true, beta: true, ...provider },
  },
  ...over,
});

const list = (items = [row()], extra: Record<string, unknown> = {}) => ({
  items,
  partial: false,
  failedLocations: [],
  asOf: '2026-11-17T00:00:00Z',
  ...extra,
});

const detailOf = (r = row(), over: Record<string, unknown> = {}) => ({
  group: r,
  configMode: null,
  driftStatus: null,
  lastErrorCode: null,
  transitions: [],
  currentOperation: null,
  ...over,
});

const mount = (
  groups: unknown,
  opts: { status?: number; cfg?: unknown; detail?: unknown; route?: string; summary?: unknown; groupSavings?: unknown } = {},
) => {
  const fx = installFetch([
    ...(opts.summary ? [{ match: /\/accounts\/7\/savings\/summary$/, responses: [{ body: opts.summary }] }] : []),
    ...(opts.groupSavings ? [{ match: /\/accounts\/7\/groups\/g-\d+\/savings$/, responses: [{ body: opts.groupSavings }] }] : []),
    { match: /\/v2\/config$/, responses: [{ body: opts.cfg ?? config() }] },
    { match: /\/accounts\/7\/groups$/, responses: [{ status: opts.status ?? 200, body: groups }] },
    { match: /\/accounts\/7\/groups\/g-\d+$/, responses: [{ body: opts.detail ?? detailOf() }] },
  ]);
  const view = renderWithProviders(<AutoSpottingPage />, opts.route);
  return { ...fx, ...view };
};

describe('AutoSpottingPage table and panel', () => {
  it('sorts rows by estimated savings, unpriced last', async () => {
    mount(
      list([
        row({ id: 'g-1', name: 'low-asg', groupKey: 'us-east-1/low-asg', monthlySavings: 100 }),
        row({ id: 'g-2', name: 'unpriced-asg', groupKey: 'us-east-1/unpriced-asg', monthlySavings: null, savingsUnknown: true }),
        row({ id: 'g-3', name: 'top-asg', groupKey: 'us-east-1/top-asg', monthlySavings: 900 }),
      ]),
    );
    await screen.findByText('top-asg');
    const names = screen.getAllByRole('row').slice(1).map(r => within(r).getAllByRole('button')[0].textContent);
    expect(names).toEqual(['top-asg', 'low-asg', 'unpriced-asg']);
    expect(screen.getAllByText('Not priced').length).toBeGreaterThan(0);
  });

  it('shows the three KPIs with their labels and "Not tracked" for the accruing figure', async () => {
    mount(list([row({ monthlySavings: 820 }), row({ id: 'g-2', name: 'b', groupKey: 'k2', monthlySavings: null, savingsUnknown: true })]));
    await screen.findByText('Est. monthly savings');
    expect(screen.getByText('Accruing MTD (not final)')).toBeInTheDocument();
    expect(screen.getByText('Not tracked')).toBeInTheDocument();
    expect(screen.getByText('Groups', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('1 group not priced')).toBeInTheDocument();
    expect(screen.getAllByText('Estimate').length).toBeGreaterThan(0);
  });

  it('reads the accruing KPI from the ledger summary, labelled "Accruing (not final)", never Billable or Realized', async () => {
    const { container } = mount(list([row({ monthlySavings: 820 })]), {
      summary: { month: '2026-11', accruingNotFinal: { amount: '9.72', currency: 'USD' }, label: 'Accruing (not final)' },
    });
    await screen.findByText('Est. monthly savings');
    const kpi = (await screen.findByText('Accruing MTD (not final)')).parentElement as HTMLElement;
    await waitFor(() => expect(within(kpi).getByText('Accruing (not final)')).toBeInTheDocument());
    expect(within(kpi).getByText(/9\.72/)).toBeInTheDocument();
    expect(screen.queryByText('Not tracked')).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/Billable|Realized/);
  });
  it('renders no "Billable" or "Realized" next to an open-month figure', async () => {
    const { container } = mount(list());
    await screen.findByText('web-asg');
    expect(container.textContent).not.toMatch(/Billable|Realized/);
  });

  it('opens the detail panel from a row', async () => {
    mount(list());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'web-asg' }));
    expect(await screen.findByRole('complementary', { name: 'web-asg details' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Config' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Audit' })).toBeInTheDocument();
  });

  it('shows estimate vs measured per month in the Savings tab, labelled Accruing or Not invoiced', async () => {
    mount(list([row({ monthlySavings: 820 })]), {
      groupSavings: [
        { month: '2026-10', status: 'CLOSED', amount: { amount: '291.60', currency: 'USD' }, label: 'Not invoiced' },
        { month: '2026-11', status: 'OPEN', amount: { amount: '9.72', currency: 'USD' }, label: 'Accruing (not final)' },
      ],
    });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'web-asg' }));
    await user.click(await screen.findByRole('tab', { name: 'Savings' }));
    const table = await screen.findByTestId('ledger-months');
    expect(within(table).getByText('2026-10')).toBeInTheDocument();
    expect(within(table).getByText('Not invoiced')).toBeInTheDocument();
    expect(within(table).getByText('Accruing (not final)')).toBeInTheDocument();
    expect(table.textContent).not.toMatch(/Billable|Realized/);
  });

  it('offers no action buttons inside table rows', async () => {
    mount(list());
    await screen.findByText('web-asg');
    const body = screen.getAllByRole('row')[1];
    expect(within(body).queryByRole('button', { name: /Enable|Revert/ })).toBeNull();
  });

  it('keeps the selected group and filters in the URL query', async () => {
    mount(list([row(), row({ id: 'g-2', name: 'api', groupKey: 'eu-west-1/api', location: 'eu-west-1' })]), {
      route: '/autospotting?region=eu-west-1',
    });
    await screen.findByRole('button', { name: 'api' });
    expect(screen.queryByRole('button', { name: 'web-asg' })).toBeNull();
  });
});

describe('detail panel actions by state', () => {
  const actionsFor = async (r: ReturnType<typeof row>, cfg?: unknown) => {
    mount(list([r]), { cfg, detail: detailOf(r) });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: r.name }));
    const panel = await screen.findByRole('complementary');
    return within(panel);
  };

  it('Eligible: Enable', async () => {
    const p = await actionsFor(row());
    expect(p.getByRole('button', { name: 'Enable' })).toBeEnabled();
  });

  it('Active: Change profile and Revert', async () => {
    const p = await actionsFor(row({ state: 'ACTIVE', managed: true }));
    expect(p.getByRole('button', { name: 'Change profile' })).toBeEnabled();
    expect(p.getByRole('button', { name: 'Revert' })).toBeEnabled();
    expect(p.queryByRole('button', { name: 'Enable' })).toBeNull();
  });

  it('Failed: Revert only', async () => {
    const p = await actionsFor(row({ state: 'FAILED', managed: true }));
    expect(p.getByRole('button', { name: 'Revert' })).toBeInTheDocument();
    expect(p.queryByRole('button', { name: 'Change profile' })).toBeNull();
  });

  it('Ineligible: no action, reason shown', async () => {
    const p = await actionsFor(
      row({
        enableAllowed: false,
        enableBlockedBy: 'SPOT_ALREADY',
        blockers: [{ id: 'b1', code: 'SPOT_ALREADY', text: 'The group already runs Spot.' }],
      }),
    );
    expect(p.queryByRole('button', { name: 'Enable' })).toBeNull();
    expect(p.getByText('The group already runs Spot.')).toBeInTheDocument();
  });

  it('disables Enable with the paused reason while conversionsEnabled is false', async () => {
    const p = await actionsFor(row(), config({}, { conversionsEnabled: false }));
    const enable = p.getByRole('button', { name: 'Enable' });
    expect(enable).toBeDisabled();
    expect(p.getByText(/New Spot conversions are paused/)).toBeInTheDocument();
  });

  it('names the engaged switch when one is engaged', async () => {
    const p = await actionsFor(row(), config({ engagedSwitches: ['GLOBAL'] }));
    expect(p.getByRole('button', { name: 'Enable' })).toBeDisabled();
    expect(p.getByText('AutoSpotting changes are paused for GLOBAL.')).toBeInTheDocument();
  });

  it('a legacy group offers only the Phase A Disable while v2 mutations are off', async () => {
    const p = await actionsFor(
      row({ state: 'LEGACY_UNCONFIRMED', managed: true }),
      config({}, { v2MutationsEnabled: false }),
    );
    expect(p.getByRole('button', { name: 'Disable' })).toBeEnabled();
    expect(p.queryByRole('button', { name: 'Adopt' })).toBeNull();
    expect(p.queryByRole('button', { name: 'Revert' })).toBeNull();
  });

  it('a legacy group offers Adopt and Revert once v2 mutations are on', async () => {
    const p = await actionsFor(row({ state: 'LEGACY_UNCONFIRMED', managed: true }));
    expect(p.getByRole('button', { name: 'Adopt' })).toBeInTheDocument();
    expect(p.getByRole('button', { name: 'Revert' })).toBeInTheDocument();
    expect(p.queryByRole('button', { name: 'Disable' })).toBeNull();
  });

  it('Disable calls the Phase A endpoint with the required headers', async () => {
    const fx = installFetch([
      { match: /\/v2\/config$/, responses: [{ body: config({}, { v2MutationsEnabled: false }) }] },
      { match: /\/accounts\/7\/groups$/, responses: [{ body: list([row({ state: 'LEGACY_UNCONFIRMED', managed: true })]) }] },
      { match: /\/accounts\/7\/groups\/g-1$/, responses: [{ body: detailOf() }] },
      { method: 'POST', match: /\/api\/autospotting\/asgs\/123456789012\/disable/, responses: [{ body: {} }] },
    ]);
    renderWithProviders(<AutoSpottingPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'web-asg' }));
    await user.click(await screen.findByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.url).toContain('region=us-east-1');
    expect(post.headers['X-XamOps-Intent']).toBe('autospotting');
    expect(post.headers['Idempotency-Key']).toBeTruthy();
  });
});

describe('load, empty, error and partial states', () => {
  it('shows a skeleton while loading', async () => {
    mount(list());
    expect(screen.getByRole('status', { name: 'Loading groups' })).toBeInTheDocument();
    await screen.findByText('web-asg');
  });

  it('shows the empty state for zero groups when not partial', async () => {
    mount(list([]));
    expect(await screen.findByText('No Auto Scaling Groups in this account')).toBeInTheDocument();
  });

  it('never renders a permission error as an empty list', async () => {
    mount({ code: 'AS_FORBIDDEN_ROLE', detail: 'no' }, { status: 403 });
    expect(await screen.findByText("XamOps can't read Auto Scaling in this account.")).toBeInTheDocument();
    expect(screen.queryByText(/No Auto Scaling Groups/i)).toBeNull();
    expect(screen.getByRole('link', { name: 'Update stack' })).toBeInTheDocument();
  });

  it('treats an all-PERMISSION partial result with no rows as a permission error', async () => {
    mount(list([], { partial: true, failedLocations: [{ location: 'us-east-1', code: 'PERMISSION' }] }));
    expect(await screen.findByText("XamOps can't read Auto Scaling in this account.")).toBeInTheDocument();
    expect(screen.queryByText(/No Auto Scaling Groups/i)).toBeNull();
  });

  it('shows a load error with the request id and Retry', async () => {
    mount({ code: 'INTERNAL', requestId: 'req-42' }, { status: 500 });
    expect(await screen.findByText("Couldn't load groups.")).toBeInTheDocument();
    expect(screen.getByText('Request id req-42')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows rows plus a partial banner naming the failed regions, and marks the KPIs (partial)', async () => {
    mount(
      list([row()], {
        partial: true,
        failedLocations: [
          { location: 'ap-east-1', code: 'TIMEOUT' },
          { location: 'me-south-1', code: 'TIMEOUT' },
        ],
      }),
    );
    expect(await screen.findByText('web-asg')).toBeInTheDocument();
    expect(screen.getByText(/2 regions could not be read \(ap-east-1, me-south-1\)/)).toBeInTheDocument();
    expect(screen.getByText('Est. monthly savings (partial)')).toBeInTheDocument();
    expect(screen.getByText('Groups (partial)')).toBeInTheDocument();
  });

  it('keeps an empty partial result off the "no groups" message', async () => {
    mount(list([], { partial: true, failedLocations: [{ location: 'us-east-1', code: 'TIMEOUT' }] }));
    expect(await screen.findByText(/1 region could not be read/)).toBeInTheDocument();
    expect(screen.queryByText(/No Auto Scaling Groups in this account/)).toBeNull();
  });
});

describe('AutoSpottingPage views (plan-phase-14)', () => {
  const emptyAnalytics = {
    from: '2026-10-22T00:00:00Z',
    to: '2026-11-21T00:00:00Z',
    notTracked: ['spotLaunches', 'launchFailures', 'interruptions', 'rebalanceRecommendations', 'successRate'],
    totals: { spotLaunches: null, launchFailures: null, failuresByCause: {}, interruptions: null, rebalanceRecommendations: null, successRate: null },
    groups: [],
  };
  const mountViews = (route: string) => {
    installFetch([
      { match: /\/v2\/config$/, responses: [{ body: config() }] },
      { match: /\/accounts\/7\/groups$/, responses: [{ body: list() }] },
      { match: /\/accounts\/7\/analytics/, responses: [{ body: emptyAnalytics }] },
      { match: /\/accounts\/7\/audit/, responses: [{ body: [] }] },
    ]);
    return renderWithProviders(<AutoSpottingPage />, route);
  };

  it('lists the four views and opens on Groups', async () => {
    mountViews('/autospotting');
    await screen.findByText('web-asg');
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['Groups', 'Analytics', 'Activity', 'Commitments']);
    expect(screen.getByRole('tab', { name: 'Groups' })).toHaveAttribute('aria-selected', 'true');
  });

  it('keeps the view in ?view= so a reload opens the same view', async () => {
    const first = mountViews('/autospotting?view=analytics');
    expect(await screen.findByRole('region', { name: 'Analytics' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Analytics' })).toHaveAttribute('aria-selected', 'true');
    first.unmount();
    // "Reload": a fresh mount at the same URL.
    mountViews('/autospotting?view=activity');
    expect(await screen.findByRole('region', { name: 'Activity' })).toBeInTheDocument();
  });

  it('switches views from the tabs', async () => {
    mountViews('/autospotting');
    await screen.findByText('web-asg');
    await userEvent.click(screen.getByRole('tab', { name: 'Activity' }));
    expect(await screen.findByRole('region', { name: 'Activity' })).toBeInTheDocument();
    expect(screen.queryByText('web-asg')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Groups' }));
    expect(await screen.findByText('web-asg')).toBeInTheDocument();
  });

  it('falls back to Groups for an unknown ?view=', async () => {
    mountViews('/autospotting?view=nope');
    expect(await screen.findByText('web-asg')).toBeInTheDocument();
  });
});
