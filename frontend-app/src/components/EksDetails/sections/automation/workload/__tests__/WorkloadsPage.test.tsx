import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import WorkloadsPage, { MALFORMED_WORKLOAD_NOTICE } from '../WorkloadsPage';
import {
  ACCOUNT,
  CLUSTER,
  PAGE,
  POLICIES_HREF,
  REGION,
  WORKLOADS_HREF,
  currentSearch,
  endpointOne,
  json,
  renderAt,
  res,
  wire,
} from './fixtures';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const isFeed = (url: unknown) => String(url).includes('/api/eks/automation/activity/');
const isUsage = (url: unknown) => String(url).includes('/api/eks/automation/usage/');
/** Endpoint-1 reads; the activity feed and the usage feed (FEAT-004) are counted apart. */
const gets = () => mockedFetch.mock.calls.filter(([url, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') === 'GET' && !isFeed(url) && !isUsage(url));
const feedGets = () => mockedFetch.mock.calls.filter(([url]) => isFeed(url));
const sparklineGets = () => mockedFetch.mock.calls.filter(([url]) => String(url).includes('/sparklines')).map(([url]) => new URL(String(url), 'http://test'));
const writes = () => mockedFetch.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') !== 'GET');

const S_END = Math.floor(Date.now() / 60_000) * 60;
const S_TIMES = Array.from({ length: 73 }, (_, i) => S_END - 72 * 3600 + i * 3600);
const isoSec = (sec: number) => new Date(sec * 1000).toISOString().replace('.000Z', 'Z');
const spark = (namespace: string, name: string, used: (i: number) => number | null, request: number | null) => ({
  ref: { namespace, kind: 'Deployment', name },
  used: S_TIMES.map((t, i) => [t, used(i)]),
  request: S_TIMES.map((t) => [t, request]),
});
/** The sparklines batch: overprovisioned-api busy, idle-batch-listener with no pod running; solo not in the answer. */
const SPARKLINES = {
  schemaVersion: 1, resource: 'cpu', unit: 'cores', window: '72h', stepSeconds: 3600, start: isoSec(S_TIMES[0]), end: isoSec(S_TIMES[72]),
  source: { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' },
  queries: { used: 'sum by (namespace, pod, container) (rate(...))', request: 'max by (namespace, pod, container) (requests)' },
  workloads: [
    spark('dev-sim', 'idle-batch-listener', () => null, null),
    spark('prod-sim', 'throttled-encoder', (i) => (i === 30 ? null : 0.049), 0.01),
    spark('team-a', 'overprovisioned-api', (i) => 0.02 + (i % 5) * 0.004, 1),
  ],
  unavailable: null,
};

/** Endpoint 1 for every read; the feed answers empty; `sparklines` answers the table's batch. */
function serveRead(body: unknown = endpointOne(), status = 200, sparklines: { status: number; body: unknown } = { status: 200, body: SPARKLINES }) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') !== 'GET') throw new Error(`unexpected write ${url}`);
    if (isFeed(url)) return json(200, { engine: { enabled: true }, entries: [], nextCursor: null });
    if (String(url).includes('/sparklines')) return json(sparklines.status, sparklines.body);
    // A workload's usage chart, on its detail: a backend without it.
    if (isUsage(url)) return json(404, null);
    return json(status, body);
  });
}

const page = (url = PAGE) =>
  renderAt(
    url,
    <WorkloadsPage clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} workloadsHref={WORKLOADS_HREF} policiesHref={POLICIES_HREF} />,
  );

const table = () => screen.getByRole('table', { name: 'Workloads at a glance' });
const rowNames = () => [...table().querySelectorAll('[data-workload-row] th a')].map((a) => a.textContent);
/** The KPI card whose label (its first line) is `label`. */
const kpi = (label: string) => {
  const card = [...document.querySelectorAll<HTMLElement>('[data-kpi-tone]')].find((k) => (k.textContent ?? '').startsWith(label));
  if (!card) throw new Error(`no KPI "${label}" in: ${[...document.querySelectorAll('[data-kpi-tone]')].map((k) => k.textContent).join(' | ')}`);
  return card;
};

describe('Workload autoscaler › Workloads (UI v3 plan §2)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    serveRead();
  });
  afterEach(() => vi.restoreAllMocks());

  it('reads endpoint 1 exactly once for the header, the table and the cards', async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(rowNames()).toEqual(['overprovisioned-api', 'throttled-encoder', 'idle-batch-listener', 'solo']);
    expect(gets()).toHaveLength(1);
    expect(String(gets()[0][0])).toBe(`/api/eks/automation/vertical/${CLUSTER}?accountId=${ACCOUNT}&region=${REGION}`);
    expect(writes()).toHaveLength(0);
    // One Now banner and one How it works on the page: the cards leave theirs to the page.
    expect(document.querySelectorAll('[data-now-banner]')).toHaveLength(1);
    expect(document.querySelectorAll('[data-how-it-works]')).toHaveLength(1);
    expect(document.querySelector('[data-how-it-works]')).toHaveAttribute('data-how-it-works', 'collapsed');
    // The existing cards keep their own heading and test-run switch, under "Change a workload".
    expect(screen.getByRole('heading', { name: 'Change a workload' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Test run only (nothing is sent)' })).toBeChecked();
  });

  it('opens with the Now sentence from endpoint 1, in freed capacity', async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    const banner = document.querySelector('[data-now-banner]')!.textContent!;
    expect(banner).toMatch(/4 workloads analysed at .+ IST\./);
    expect(banner).toContain('1 is over-provisioned and can be resized now: overprovisioned-api, freeing 1.95 vCPU and 1.5 GiB.');
    expect(banner).toMatch(/idle-batch-listener was resized at .+\./);
    expect(banner).not.toMatch(/\$/);
  });

  it('shows the KPIs: analysed, can save, needs more, applied', async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(kpi('Workloads analysed')).toHaveTextContent('4');
    expect(kpi('Workloads analysed')).toHaveTextContent('Over-provisioned 1 · Needs more 1 · Left alone 1 · Skipped 1');
    expect(kpi('Can save now')).toHaveTextContent('1.95 vCPU');
    expect(kpi('Can save now')).toHaveTextContent('1.5 GiB memory, freed across every pod');
    expect(kpi('Needs more')).toHaveTextContent('+0.11 vCPU if applied');
    expect(kpi('Applied')).toHaveTextContent('1');
  });

  it('charts the top savings by workload, with its data table', async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    const chart = screen.getByRole('heading', { name: 'Top savings by workload' }).closest('section')!;
    const row = within(chart).getByRole('rowheader', { name: 'overprovisioned-api', hidden: true }).closest('tr')!;
    expect([...row.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['1.95 vCPU', '1.5 GiB']);
    expect(within(chart).queryByRole('rowheader', { name: 'throttled-encoder', hidden: true })).toBeNull();
  });

  it('shows each row: status chip, now → suggested, freed or more, the mini-bar, the stage and the sparkline state', async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    const over = table().querySelector<HTMLElement>('[data-workload-row="team-a/Deployment/overprovisioned-api"]')!;
    expect(over.querySelector('[data-tone="warn"]')).toHaveTextContent('Over-provisioned');
    expect(over).toHaveTextContent('1000m → 25m');
    expect(over).toHaveTextContent('1 GiB → 256Mi');
    expect(over.querySelector('[data-delta="saved"]')).toHaveTextContent('1.95 vCPU · 1.5 GiB');
    expect(over.querySelector('[data-mini-bar]')).not.toBeNull();
    expect(over.querySelector('[data-step-indicator]')).toHaveTextContent('Suggestion ready');
    await waitFor(() => expect(over.querySelector('svg[data-sparkline]')).not.toBeNull());

    const throttled = table().querySelector<HTMLElement>('[data-workload-row="prod-sim/Deployment/throttled-encoder"]')!;
    expect(throttled.querySelector('[data-tone="bad"]')).toHaveTextContent('Needs more CPU (throttled)');
    expect(throttled.querySelector('[data-delta="extra"]')).toHaveTextContent('+0.11 vCPU');

    const idle = table().querySelector<HTMLElement>('[data-workload-row="dev-sim/Deployment/idle-batch-listener"]')!;
    expect(idle.querySelector('[data-step-indicator]')).toHaveTextContent(/Watching until /);
    expect(table().querySelector('[data-workload-row="team-b/Deployment/solo"] [data-step-indicator]')).toHaveTextContent('Not sized: 1 replica');

    const header = within(table()).getByRole('columnheader', { name: /Last 3 days/ });
    expect(header).toHaveTextContent('Busiest pod: CPU use and its request');
  });

  it("draws each row's 3-day sparkline from one sparklines read: use against request, no pod running, or a dash", async () => {
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    const row = (id: string) => table().querySelector<HTMLElement>(`[data-workload-row="${id}"]`)!;
    await waitFor(() => expect(row('team-a/Deployment/overprovisioned-api').querySelector('svg[data-sparkline]')).not.toBeNull());
    expect(sparklineGets()).toHaveLength(1);
    expect(sparklineGets()[0].searchParams.get('resource')).toBe('cpu');
    expect(sparklineGets()[0].searchParams.get('window')).toBe('72h');
    // Use and the request it's compared with, on one scale from 0.
    const over = row('team-a/Deployment/overprovisioned-api');
    expect(over.querySelectorAll('svg[data-sparkline] path')).toHaveLength(2);
    expect(over.querySelector('[data-sparkline-cell]')).toHaveAttribute('title', 'CPU use of the busiest pod, last 3 days: 20m to 36m, request 1000m.');
    // A gap breaks the line: the hour nothing was measured is not drawn as 0.
    const encoder = row('prod-sim/Deployment/throttled-encoder').querySelector('svg[data-sparkline] path:not([data-sparkline-reference])')!;
    expect((encoder.getAttribute('d')!.match(/M/g) ?? []).length).toBe(2);
    expect(row('dev-sim/Deployment/idle-batch-listener').querySelector('[data-sparkline="empty"]')).toHaveTextContent('no pod running');
    // A workload the answer doesn't list is a dash.
    expect(row('team-b/Deployment/solo')).toHaveTextContent('—');
    expect(document.querySelector('[data-sparkline-source]')).toHaveTextContent('Source: usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}/sparklines?resource=cpu&window=72h), Prometheus monitoring/prometheus-server:80');
  });

  it('leaves the sparkline column out for a backend without the usage feed, and says why when Prometheus has none', async () => {
    serveRead(endpointOne(), 200, { status: 404, body: null });
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    await waitFor(() => expect(sparklineGets()).toHaveLength(1));
    await waitFor(() => expect(within(table()).queryByRole('columnheader', { name: /Last 3 days/ })).toBeNull());
    cleanup();
    mockedFetch.mockReset();
    serveRead(endpointOne(), 200, { status: 200, body: { ...SPARKLINES, workloads: [], unavailable: { code: 'NO_METRICS_SOURCE', message: 'No Prometheus.' } } });
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    await waitFor(() => expect(within(table()).getByRole('columnheader', { name: /Last 3 days/ })).toHaveTextContent('No Prometheus answered in this cluster, so there is no history to draw.'));
  });

  it('filters by status, namespace and text, on the page', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    await user.selectOptions(screen.getByLabelText('Status'), 'needs-more');
    expect(rowNames()).toEqual(['throttled-encoder']);
    await user.selectOptions(screen.getByLabelText('Status'), 'all');
    await user.selectOptions(screen.getByLabelText('Namespace'), 'dev-sim');
    expect(rowNames()).toEqual(['idle-batch-listener']);
    await user.selectOptions(screen.getByLabelText('Namespace'), 'all');
    await user.type(screen.getByLabelText('Search'), 'encoder');
    expect(rowNames()).toEqual(['throttled-encoder']);
    expect(screen.getByText('Showing 1 of 4 workloads')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Search'));
    await user.type(screen.getByLabelText('Search'), 'nothing-matches');
    expect(screen.getByText('No workload matches these filters.')).toBeInTheDocument();
    expect(gets()).toHaveLength(1);
  });

  it('links each row to ?workload=<ns>/<Kind>/<name> and opens its detail in place, without reading again', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    const link = within(table()).getByRole('link', { name: 'Open throttled-encoder' });
    const href = new URL(link.getAttribute('href')!, 'http://test');
    expect(href.searchParams.get('workload')).toBe('prod-sim/Deployment/throttled-encoder');
    expect(href.searchParams.get('sub')).toBe('workload-autoscaler');
    expect(href.searchParams.get('name')).toBe(CLUSTER);
    await user.click(link);
    expect(await screen.findByText('Where it is now')).toBeInTheDocument();
    expect(document.querySelector('[data-workload-detail="prod-sim/Deployment/throttled-encoder"]')).not.toBeNull();
    expect(gets()).toHaveLength(1);
    // The detail's "Recent changes" reads the activity feed, once.
    await waitFor(() => expect(feedGets()).toHaveLength(1));
  });

  it('removes a malformed ?workload= with replace and says so', async () => {
    const router = page(`${PAGE}&workload=${encodeURIComponent('Not/A/Ref/<x>')}`);
    expect(await screen.findByText(MALFORMED_WORKLOAD_NOTICE)).toBeInTheDocument();
    await waitFor(() => expect(currentSearch().has('workload')).toBe(false));
    expect(router.state.historyAction).toBe('REPLACE');
    expect(currentSearch().get('sub')).toBe('workload-autoscaler');
    expect(currentSearch().get('name')).toBe(CLUSTER);
    // The list shows, and the notice stays after the URL changed until it is dismissed.
    await screen.findByRole('table', { name: 'Workloads at a glance' });
    expect(screen.getByText(MALFORMED_WORKLOAD_NOTICE).closest('[role="status"]')).not.toBeNull();
    await act(async () => {
      screen.getByRole('button', { name: 'Dismiss' }).click();
    });
    expect(screen.queryByText(MALFORMED_WORKLOAD_NOTICE)).toBeNull();
  });

  it("shows the list and a notice for a well-formed workload that isn't in the cluster", async () => {
    const user = userEvent.setup();
    page(`${PAGE}&workload=${encodeURIComponent('team-a/Deployment/gone')}`);
    expect(await screen.findByText("team-a/Deployment/gone isn't in this cluster any more.")).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Workloads at a glance' })).toBeInTheDocument();
    expect(document.querySelector('[data-workload-detail]')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    await waitFor(() => expect(currentSearch().has('workload')).toBe(false));
    expect(screen.queryByText(/isn't in this cluster any more/)).toBeNull();
  });

  it('says it could not read, once, and leaves the cards to show the failure', async () => {
    serveRead({ code: 'K8S_INTERNAL', message: 'Something went wrong on our side.' }, 500);
    page();
    expect(await screen.findByText("Couldn't load Workload rightsizing")).toBeInTheDocument();
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read its workloads just now.");
    expect(document.querySelector('[data-kpi-tone]')).toBeNull();
    expect(gets()).toHaveLength(1);
  });

  describe('cost column + KPI (FEAT-002)', () => {
    const priced = wire('priced-api', 'team-a', {
      replicas: 2,
      containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
      cost: { monthlyCost: 20, monthlySavings: 6.84, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false, note: 'AWS Price List on-demand rate' },
    });
    const inferred = wire('inferred-api', 'team-a', {
      replicas: 2,
      containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
      cost: { monthlyCost: 30, monthlySavings: 4.2, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: true, note: null },
    });
    const unpriced = wire('unpriced-api', 'team-a', {
      replicas: 2,
      containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
      cost: { monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', source: null, capacityType: null, capacityTypeInferred: false, note: null },
    });
    const withCost = endpointOne([priced, inferred, unpriced], {
      cost: { currency: 'USD', hoursPerMonth: 730, monthlySavings: 11.04, monthlySavingsUnavailableReason: null, clusterMonthlyCost: 50, pricedWorkloads: 2, unpricedWorkloads: 1 },
    });

    const rowOf = (name: string) =>
      [...table().querySelectorAll('[data-workload-row]')].find((r) => r.querySelector('th a')?.textContent === name) as HTMLElement;

    it('renders $X/mo via Delta when real, a dash + reason tooltip when null, and "assumed on-demand" when inferred', async () => {
      serveRead(withCost);
      page();
      await screen.findByRole('table', { name: 'Workloads at a glance' });

      // formatMoney renders whole dollars above $1 (6.84 → $7), two sig-figs under $1. The savings
      // cell is the 7th column (Monthly cost sits before it); select it by its Delta/dash content.
      const savings = (name: string) => rowOf(name).querySelector('td:nth-child(7)') as HTMLElement;
      expect(savings('priced-api').querySelector('[data-delta="saved"]')).toHaveTextContent('$7/mo');

      const unpricedCell = savings('unpriced-api');
      expect(unpricedCell.querySelector('[data-delta]')).toBeNull();
      expect(unpricedCell).toHaveTextContent('—');
      expect(unpricedCell.querySelector('span[title]')!.getAttribute('title')).toContain('No live AWS price');

      const inferredCell = savings('inferred-api');
      expect(inferredCell.querySelector('[data-delta="saved"]')).toHaveTextContent('$4/mo');
      expect(inferredCell.querySelector('span[title]')!.getAttribute('title')).toContain('Assumed on-demand');
    });

    it('leads the "Can save now" KPI with the cost roll-up and an "n of m priced" sub-line, never $0', async () => {
      serveRead(withCost);
      page();
      await screen.findByRole('table', { name: 'Workloads at a glance' });
      const card = kpi('Can save now');
      expect(card.querySelector('[data-delta="saved"]')).toHaveTextContent('$11/mo');
      expect(card.querySelector('[data-priced-line]')).toHaveTextContent('2 of 3 priced');
      expect(card).not.toHaveTextContent('$0');
    });

    // Regression for the live UAT break: the backend returns real $ cost (cluster $23.67,
    // per-workload $2.50+), but every monthlySavings is 0.0 (no rightsizing recommendation yet,
    // which is correct). Before the fix, the only cost surface on this page was keyed off
    // monthlySavings, so the page showed only "$0/mo saved" and dropped the real cost the payload
    // carried — the user saw no cost anywhere. The cluster cost KPI and the per-workload Monthly
    // cost column must render the real figures independently of savings.
    describe('real cost with no savings yet (live UAT shape)', () => {
      const priced = (name: string, monthlyCost: number) =>
        wire(name, 'dev-sim', {
          replicas: 2,
          containers: [{ name: 'c', qosClass: 'Burstable', current: res(25, 128, 50, 256), recommended: null, reasons: ['BELOW_FLOOR_NO_RAISE'], upstreamVpa: null }],
          cost: { monthlyCost, monthlySavings: 0.0, reason: null, source: 'PRICE_LIST', capacityType: 'on_demand', capacityTypeInferred: false, note: 'AWS Price List on-demand rate for t3.medium in ap-south-1' },
        });
      // One workload the backend could not price, like unschedulable-giant in the live payload.
      const unpriced = wire('unschedulable-giant', 'team-a', {
        replicas: 1,
        skipReason: 'INSUFFICIENT_HISTORY',
        containers: [],
        cost: null,
      });
      const liveShape = endpointOne([priced('idle-batch-listener', 2.5000662697924256), priced('php-apache', 10.286852497315364), unpriced], {
        cost: { currency: 'USD', hoursPerMonth: 730, monthlySavings: 0.0, monthlySavingsUnavailableReason: null, clusterMonthlyCost: 23.670027275035324, pricedWorkloads: 8, unpricedWorkloads: 1 },
      });

      const rowOf = (name: string) =>
        [...table().querySelectorAll('[data-workload-row]')].find((r) => r.querySelector('th a')?.textContent === name) as HTMLElement;

      it('shows the cluster monthly cost KPI from clusterMonthlyCost, even though savings are $0', async () => {
        serveRead(liveShape);
        page();
        await screen.findByRole('table', { name: 'Workloads at a glance' });
        const card = kpi('Monthly cost');
        // formatMoney renders whole dollars above $1: 23.67 → $24.
        expect(card).toHaveTextContent('$24');
        expect(card.querySelector('[data-priced-line]')).toHaveTextContent('8 of 9 priced');
      });

      it('shows each workload its real Monthly cost, not just the $0 saving', async () => {
        serveRead(liveShape);
        page();
        await screen.findByRole('table', { name: 'Workloads at a glance' });
        // The Monthly cost column surfaces cost.monthlyCost (2.50 → $3, 10.29 → $10).
        const costCell = (name: string) => rowOf(name).querySelector('[data-workload-cost]') as HTMLElement;
        expect(costCell('idle-batch-listener')).toHaveTextContent('$3/mo');
        expect(costCell('php-apache')).toHaveTextContent('$10/mo');
        // The unpriced workload shows a dash, never a fabricated $0.
        const giant = costCell('unschedulable-giant');
        expect(giant).toHaveTextContent('—');
        expect(giant).not.toHaveTextContent('$0');
      });
    });
  });
});
