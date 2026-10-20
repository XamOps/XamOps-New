import { act, screen, waitFor, within } from '@testing-library/react';
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

const gets = () => mockedFetch.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') === 'GET');
const writes = () => mockedFetch.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') !== 'GET');

function serveRead(body: unknown = endpointOne(), status = 200) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') !== 'GET') throw new Error(`unexpected write ${url}`);
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
    expect(over.querySelector('[data-sparkline="empty"]')).not.toBeNull();

    const throttled = table().querySelector<HTMLElement>('[data-workload-row="prod-sim/Deployment/throttled-encoder"]')!;
    expect(throttled.querySelector('[data-tone="bad"]')).toHaveTextContent('Needs more CPU (throttled)');
    expect(throttled.querySelector('[data-delta="extra"]')).toHaveTextContent('+0.11 vCPU');

    const idle = table().querySelector<HTMLElement>('[data-workload-row="dev-sim/Deployment/idle-batch-listener"]')!;
    expect(idle.querySelector('[data-step-indicator]')).toHaveTextContent(/Watching until /);
    expect(table().querySelector('[data-workload-row="team-b/Deployment/solo"] [data-step-indicator]')).toHaveTextContent('Not sized: 1 replica');

    const header = within(table()).getByRole('columnheader', { name: /Last 3 days/ });
    expect(header).toHaveTextContent('History arrives with the usage feed');
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

      // formatMoney renders whole dollars above $1 (6.84 → $7), two sig-figs under $1.
      const savings = (name: string) => rowOf(name).querySelector('td:nth-child(6)') as HTMLElement;
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
  });
});
