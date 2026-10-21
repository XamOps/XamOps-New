import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import type { UseEksDashboardReturn } from '../../../../../../hooks/useEksDashboard';
import ActivityPage, { EVENTS_STREAM_TITLE, FEED_EMPTY, LATEST_ONLY_NOTE } from '../ActivityPage';
import { ACTIVITY_FEED_CAPTION } from '../ActivityFeedTable';
import { LATEST_CHANGE_CAPTION } from '../LatestChangeTable';
import { ALL_ENTRIES, APPLIED, HPA_CHANGE, LEGACY, MODE, PUT_BACK, REFUSED, TEST_RUN, feedPage } from './activityFixtures';
import { ACCOUNT, CLUSTER, IDLE, OVERPROVISIONED, REGION, SOLO, THROTTLED, endpointOne, iso, json, renderAt, wire } from '../../workload/__tests__/fixtures';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;
const method = (init: unknown) => ((init as RequestInit | undefined)?.method ?? 'GET');
const PAGE = `/eks-details?name=${CLUSTER}&accountIds=${ACCOUNT}&region=${REGION}&tab=automation&sub=activity`;
const isSummary = (url: string) => /\/api\/eks\/automation\/activity\/[^/?]+\/summary(\?|$)/.test(url);
const isFeed = (url: string) => url.includes('/api/eks/automation/activity/') && !isSummary(url);
const summaryCalls = () => mockedFetch.mock.calls.filter(([url]) => isSummary(String(url))).map(([url]) => new URL(String(url), 'http://test'));
const feedCalls = () => mockedFetch.mock.calls.filter(([url]) => isFeed(String(url))).map(([url]) => new URL(String(url), 'http://test'));
/** The newest feed read (lib is ES2021, so no Array.prototype.at). */
const lastFeedCall = () => {
  const calls = feedCalls();
  return calls[calls.length - 1];
};
const endpointOneCalls = () =>
  mockedFetch.mock.calls.filter(([url]) => String(url).includes('/api/eks/automation/vertical/') && !String(url).includes('/rca'));

// Each workload's latest change, as endpoint 1 records it (the view for a backend without the feed).
const TESTED = wire('overprovisioned-api', 'team-a', { ...OVERPROVISIONED, lastAction: { at: iso(30 * 60_000), dryRun: true, outcome: 'APPLIED' } });
const FAILED = wire('throttled-encoder', 'prod-sim', { ...THROTTLED, lastAction: { at: iso(5 * 60_000), dryRun: false, outcome: 'FAILED' } });
const MODE_SWITCH = wire('solo', 'team-b', { ...SOLO, lastAction: { at: iso(2 * 24 * 3600_000), dryRun: false, outcome: 'POLICY_CHANGE' } });

// The RCA sub-route the RootCausePanel reads (FEAT-002). Its honest empty body keeps the panel in
// its empty state without adding causes to the assertions above.
const RCA_EMPTY = { generatedAt: iso(0), windowHours: 24, analyzed: { actionRows: 0, workloads: 0 }, causes: [], dependencies: [{ kind: 'OOM_EVICTION', needs: 'Kubernetes Warning events persisted server-side' }] };

type FeedAnswer = { status: number; body: unknown } | ((url: URL) => { status: number; body: unknown });

/**
 * The feed answers `feed`; endpoint 1 answers `read`; the per-day summary answers `summary` (by
 * default a backend without it: an untyped 404); any write throws.
 */
function serve(
  feed: FeedAnswer = { status: 200, body: feedPage() },
  read: { status: number; body: unknown } = { status: 200, body: endpointOne([TESTED, FAILED, IDLE, MODE_SWITCH]) },
  summary: { status: number; body: unknown } = { status: 404, body: null },
) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if (method(init) !== 'GET') throw new Error(`unexpected write ${method(init)} ${url}`);
    if (url.includes('/rca')) return json(200, RCA_EMPTY);
    if (isSummary(url)) return json(summary.status, summary.body);
    if (isFeed(url)) {
      const answer = typeof feed === 'function' ? feed(new URL(url, 'http://test')) : feed;
      return json(answer.status, answer.body);
    }
    return json(read.status, read.body);
  });
}

const istDay = (msAgo: number) => new Date(Date.now() - msAgo + 5.5 * 3600_000).toISOString().slice(0, 10);
/** Seven IST days, of which XamOps recorded every kind of change for the last two. */
const SUMMARY = {
  engine: { enabled: true }, timezone: 'Asia/Kolkata', from: istDay(6 * 24 * 3600_000), to: istDay(0),
  firstRecordedAt: iso(4 * 24 * 3600_000), completeSince: iso(26 * 3600_000), truncated: false,
  days: [
    { date: istDay(24 * 3600_000), applied: 1, putBack: 0, refused: 0, testRuns: 0, failed: 0, byFeature: { VERTICAL: { applied: 1, putBack: 0, refused: 0, testRuns: 0, failed: 0 } } },
    { date: istDay(0), applied: 2, putBack: 1, refused: 1, testRuns: 1, failed: 0, byFeature: { VERTICAL: { applied: 1, putBack: 1, refused: 1, testRuns: 1, failed: 0 }, HPA: { applied: 1, putBack: 0, refused: 0, testRuns: 0, failed: 0 } } },
  ],
};

const EVENTS = {
  loading: false,
  dashboard: {
    k8sEvents: [
      { type: 'Normal', reason: 'ResizeCompleted', namespace: 'dev-sim', objectKind: 'Pod', involvedObject: 'idle-batch-listener-2bddb', message: 'Pod resize completed', lastTime: iso(17 * 60_000), count: 1 },
      { type: 'Warning', reason: 'ResizeDeferred', namespace: 'prod-sim', objectKind: 'Pod', involvedObject: 'throttled-encoder-mcw8q', message: 'OutOfcpu', lastTime: iso(4 * 60_000), count: 1 },
      { type: 'Warning', reason: 'BackOff', namespace: 'staging', name: 'api-staging-84c6d9f7-vx5pt', message: 'Back-off restarting failed container', lastTimestamp: iso(60_000), count: 2 },
    ],
  },
} as unknown as UseEksDashboardReturn;

/** `eksData: null` renders the page without the XamOps agent's data. */
const page = (eksData: UseEksDashboardReturn | null = EVENTS) =>
  renderAt(PAGE, <ActivityPage clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} eksData={eksData ?? undefined} />);

const feedTable = () => screen.getByRole('table', { name: ACTIVITY_FEED_CAPTION });
const feedRows = () => [...feedTable().querySelectorAll<HTMLElement>('tbody > tr')].filter((tr) => !tr.id);
const rowOf = (name: string, index = 0) => feedRows().filter((tr) => (tr.querySelectorAll('td')[2]?.textContent ?? '').startsWith(name))[index];
const latestTable = () => screen.getByRole('table', { name: LATEST_CHANGE_CAPTION });
const workloadNames = () =>
  [...latestTable().querySelectorAll('tbody tr')].map((tr) => tr.querySelectorAll('td')[1]?.textContent ?? '');

describe('Automation › Activity: the activity feed (FEAT-007)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    serve();
  });
  afterEach(() => vi.restoreAllMocks());

  it('reads the feed once, newest first, and never endpoint 1 while the feed answers', async () => {
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    expect(feedCalls()).toHaveLength(1);
    const url = feedCalls()[0];
    expect(url.pathname).toBe(`/api/eks/automation/activity/${CLUSTER}`);
    expect(url.searchParams.get('accountId')).toBe(ACCOUNT);
    expect(url.searchParams.get('region')).toBe(REGION);
    expect(url.searchParams.get('limit')).toBe('50');
    expect(url.searchParams.has('feature')).toBe(false);
    expect(url.searchParams.has('result')).toBe(false);
    expect(endpointOneCalls()).toHaveLength(0);
    expect(feedRows()).toHaveLength(ALL_ENTRIES.length);
  });

  it('shows when, who, the workload, before → after, the result and the restart-free proof of each entry', async () => {
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });

    const putBack = rowOf('throttled-encoder');
    expect(putBack).toHaveTextContent('Deployment · prod-sim · Workload autoscaler');
    expect(putBack).toHaveTextContent('CPU request 10m → 63m asked, put back to 10m');
    expect(putBack).toHaveTextContent('CPU limit 50m → 200m asked, put back to 50m');
    expect(putBack.querySelector('[data-tone="warn"]')).toHaveTextContent('Deferred, put back');
    // The kubelet's words, without the revert note the chip already gives.
    expect(putBack).toHaveTextContent("because its node has no room for it right now (Node didn't have enough resource: cpu");
    expect(putBack).not.toHaveTextContent('Revert: REVERTED');
    expect(putBack).toHaveTextContent('Restart-free');
    expect(putBack).toHaveTextContent('1 pod: same pod, restarts 0 → 0, ready');
    expect(putBack).toHaveTextContent('A user');
    expect(putBack).toHaveTextContent('d3f3rr3d');

    const applied = rowOf('overprovisioned-api');
    expect(applied).toHaveTextContent('CPU request 1000m → 25m');
    expect(applied).toHaveTextContent('Memory request 1 GiB → 250Mi');
    expect(applied.querySelector('[data-tone="good"]')).toHaveTextContent('Applied');
    expect(applied).toHaveTextContent('2 pods: same pods, restarts 0 → 0, all ready');
    expect(applied.querySelector('[data-feature-dot="workload"]')).not.toBeNull();

    const tested = rowOf('idle-batch-listener', 0);
    expect(tested).toHaveTextContent('Test run');
    expect(tested).toHaveTextContent('CPU request would set 25m');
    expect(tested).toHaveTextContent('Nothing was sent to the cluster.');
    expect(tested).toHaveTextContent('No pod touched: a test run');
    expect(tested.querySelector('[data-tone="info"]')).toHaveTextContent('Test run');

    const hpa = rowOf('php-apache');
    expect(hpa).toHaveTextContent('HPA · team-b · HPA');
    expect(hpa).toHaveTextContent('HPA change');
    expect(hpa).toHaveTextContent('1–5 replicas, CPU target 50% → 1–8 replicas, CPU target 50%');
    expect(hpa).toHaveTextContent('No pod touched: an HPA change');
    expect(hpa.querySelector('[data-feature-dot="hpa"]')).not.toBeNull();

    const refused = rowOf('overprovisioned-api', 1);
    expect(refused.querySelector('[data-tone="bad"]')).toHaveTextContent('Refused: nothing was sent');
    expect(refused).toHaveTextContent('Template persistence is not available');
    expect(refused).not.toHaveTextContent('K8S_GITOPS_MANAGED');

    const mode = rowOf('idle-batch-listener', 1);
    expect(mode).toHaveTextContent('Mode change');
    expect(mode).toHaveTextContent('Suggest only → Apply automatically');
    expect(mode.querySelector('[data-tone="info"]')).toHaveTextContent('Mode changed');

    // A row from before the activity log: the target it asked for isn't shown as a "before".
    const legacy = rowOf('idle-batch-listener', 2);
    expect(legacy).toHaveTextContent('CPU request before not recorded → 25m');
    expect(legacy).toHaveTextContent('XamOps (automatic)');
    expect(legacy).toHaveTextContent('Per-pod proof not recorded for this entry');
  });

  it('gives every result and proof chip an icon and a label', async () => {
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    const chips = [...feedTable().querySelectorAll('[data-tone]')];
    expect(chips.length).toBeGreaterThanOrEqual(ALL_ENTRIES.length);
    for (const chip of chips) expect((chip.textContent ?? '').trim()).not.toBe('');
    // The icon mounts after the chip; every chip carries one, never colour alone.
    await waitFor(() => {
      expect(chips.filter((c) => !c.querySelector('svg[aria-hidden="true"]')).map((c) => c.textContent)).toEqual([]);
    });
  });

  it('expands an entry into its per-pod proof, detail and reference', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    await user.click(within(rowOf('throttled-encoder')).getByRole('button', { name: /Show details for throttled-encoder/ }));
    const detail = document.querySelector<HTMLElement>(`[data-activity-entry-detail="${PUT_BACK.id}"]`)!;
    const pod = within(detail).getByRole('rowheader', { name: 'throttled-encoder-8b999cc87-mcw8q' }).closest('tr')!;
    expect(pod).toHaveTextContent('Same pod');
    expect(pod).toHaveTextContent('0 → 0');
    expect([...pod.querySelectorAll('[data-tone]')].map((c) => [c.getAttribute('data-tone'), c.textContent])).toEqual([
      ['good', 'Ready'],
      ['good', 'Put back and confirmedXamOps put the pod back.'],
    ]);
    expect(detail).toHaveTextContent('Revert: REVERTED.');
    expect(detail).toHaveTextContent('d3f3rr3d-0000-4000-8000-000000000000');
  });

  it('counts the KPIs over the loaded entries and says so, and opens with what XamOps did today', async () => {
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    const kpis = [...document.querySelectorAll('[data-activity-kpis] [data-kpi-tone]')].map((k) => k.textContent);
    expect(kpis[0]).toMatch(/^Applied3in all 7 entries/);
    expect(kpis[1]).toMatch(/^Deferred or put back1/);
    expect(kpis[2]).toMatch(/^Refused or failed1/);
    expect(kpis[3]).toMatch(/^Test runs1/);
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent('Today XamOps recorded 2 applied, 1 deferred and put back, 1 refused and 1 test run.');
  });

  it('sends the Feature and Result filters and the search as the query, and clears them', async () => {
    const user = userEvent.setup();
    serve((url) => {
      const feature = url.searchParams.get('feature');
      const result = url.searchParams.get('result');
      const q = url.searchParams.get('q');
      const entries = ALL_ENTRIES.filter((e) => (!feature || e.feature === feature) && (!result || e.result === result) && (!q || (e.target?.name ?? '').includes(q)));
      return { status: 200, body: feedPage(entries) };
    });
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    const features = within(document.querySelector<HTMLElement>('[data-activity-sources]')!);
    expect(features.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    expect(features.getByRole('button', { name: 'HPA' }).querySelector('[data-feature-dot="hpa"]')).not.toBeNull();

    await user.click(features.getByRole('button', { name: 'HPA' }));
    await waitFor(() => expect(lastFeedCall().searchParams.get('feature')).toBe('HPA'));
    await waitFor(() => expect(feedRows()).toHaveLength(1));
    expect(features.getByRole('button', { name: 'HPA' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(features.getByRole('button', { name: 'Workload autoscaler' }));
    const results = within(document.querySelector<HTMLElement>('[data-activity-results]')!);
    await user.click(results.getByRole('button', { name: 'Deferred or put back' }));
    await waitFor(() => {
      const last = lastFeedCall();
      expect(last.searchParams.get('feature')).toBe('VERTICAL');
      expect(last.searchParams.get('result')).toBe('PUT_BACK');
    });
    await waitFor(() => expect(feedRows()).toHaveLength(1));
    expect(rowOf('throttled-encoder')).toBeDefined();

    await user.click(results.getByRole('button', { name: 'Failed' }));
    expect(await screen.findByText('No recorded change matches these filters.', { selector: 'p' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(feedRows()).toHaveLength(ALL_ENTRIES.length));

    await user.type(screen.getByRole('searchbox', { name: 'Search the activity by workload or HPA name' }), 'php');
    await waitFor(() => expect(lastFeedCall().searchParams.get('q')).toBe('php'));
    await waitFor(() => expect(feedRows()).toHaveLength(1));
    // No role=tablist: the filters are pressed buttons.
    expect(document.querySelector('[role="tablist"]')).toBeNull();
  });

  it('loads older entries with the cursor and appends them', async () => {
    const user = userEvent.setup();
    serve((url) =>
      url.searchParams.get('cursor') === 'page-2'
        ? { status: 200, body: feedPage([MODE, LEGACY], null) }
        : { status: 200, body: feedPage([PUT_BACK, APPLIED, TEST_RUN, HPA_CHANGE, REFUSED], 'page-2') });
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    expect(feedRows()).toHaveLength(5);
    expect([...document.querySelectorAll('[data-activity-kpis] [data-kpi-tone]')][0]).toHaveTextContent('in the last 5 entries');
    await user.click(screen.getByRole('button', { name: 'Load older' }));
    await waitFor(() => expect(feedRows()).toHaveLength(7));
    expect(lastFeedCall().searchParams.get('cursor')).toBe('page-2');
    expect(screen.queryByRole('button', { name: 'Load older' })).toBeNull();
  });

  it('says when nothing is recorded yet', async () => {
    serve({ status: 200, body: feedPage([]) });
    page();
    expect(await screen.findByText(FEED_EMPTY)).toBeInTheDocument();
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps hasn't recorded a change on this cluster yet.");
  });

  it('says the engine is off when the history is from while it was on', async () => {
    serve({ status: 200, body: feedPage(ALL_ENTRIES, null, false) });
    page();
    expect(await screen.findByText('The Workload autoscaler is off in this environment. This is the history XamOps recorded while it was on.')).toBeInTheDocument();
  });

  it('shows a read failure with Retry, and the events stream still renders', async () => {
    const user = userEvent.setup();
    serve({ status: 500, body: { code: 'K8S_INTERNAL', message: "XamOps couldn't read its activity log just now." } });
    page();
    await waitFor(() => expect(document.querySelector('[data-activity-failure]')).not.toBeNull());
    expect(document.querySelector('[data-activity-failure]')).toHaveTextContent("Couldn't read the activity log");
    expect(document.querySelector('[data-activity-failure]')).toHaveTextContent("XamOps couldn't read its activity log just now.");
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read its recorded changes just now.");
    expect(document.querySelector('[data-activity-events]')).not.toBeNull();
    serve();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION })).toBeInTheDocument();
  });

  it('renders no per-day chart for a backend without the summary', async () => {
    page(null);
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    await waitFor(() => expect(summaryCalls()).toHaveLength(1));
    expect(screen.queryByRole('heading', { name: 'Changes per day, by result' })).toBeNull();
    expect(screen.getByText("The XamOps agent hasn't reported this cluster's Kubernetes events.")).toBeInTheDocument();
  });

  it('charts changes per IST day by result from the summary, with days it did not record as "Not recorded yet", never 0', async () => {
    serve(undefined, undefined, { status: 200, body: SUMMARY });
    page();
    const chart = await waitFor(() => {
      const el = screen.getByRole('heading', { name: 'Changes per day, by result' }).closest('section')!;
      expect(el.querySelectorAll('tbody tr')).toHaveLength(7);
      return el;
    });
    expect(summaryCalls().map((u) => u.searchParams.get('days'))).toEqual(['7']);
    expect(summaryCalls()[0].searchParams.has('feature')).toBe(false);
    const rows = [...chart.querySelectorAll('tbody tr')];
    // Oldest first: five days before XamOps recorded every kind of change, then two counted days.
    expect([...rows[0].querySelectorAll('td')].map((td) => td.textContent)).toEqual(Array(6).fill('Not recorded yet'));
    expect([...rows[5].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['1', '0', '0', '0', '0', '1']);
    expect([...rows[6].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['2', '1', '1', '0', '1', '5']);
    expect(chart).toHaveTextContent(/Not recorded yet: .+ to .+, before XamOps recorded every kind of change here; those days aren't counted as 0\./);
    // Every result's tone is named by its chip, with its icon.
    const legend = chart.querySelector<HTMLElement>('[data-day-bars-legend]')!;
    expect([...legend.querySelectorAll('[data-tone]')].map((c) => [c.getAttribute('data-tone'), c.textContent])).toEqual([
      ['good', 'Applied'], ['warn', 'Deferred or put back'], ['bad', 'Refused'], ['bad', 'Failed'], ['info', 'Test runs'],
    ]);
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('Activity summary (FEAT-005, GET /api/eks/automation/activity/{cluster}/summary?days=7)');
  });

  it('says "Not recorded yet" when the summary lists no day, and offers Retry when it failed', async () => {
    const user = userEvent.setup();
    serve(undefined, undefined, { status: 200, body: { ...SUMMARY, completeSince: null, days: [] } });
    page();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Changes per day, by result' }).closest('section')!.querySelector('[data-empty="unavailable"]'))
      .toHaveTextContent('Not recorded yet: XamOps counts changes per day from the first day it records every kind of change'));
    cleanup();
    mockedFetch.mockReset();
    serve(undefined, undefined, { status: 500, body: { code: 'K8S_INTERNAL', message: "XamOps couldn't read its activity log just now." } });
    page();
    const chart = await waitFor(() => {
      const el = screen.getByRole('heading', { name: 'Changes per day, by result' }).closest('section')!;
      expect(el.querySelector('[data-empty="error"]')).not.toBeNull();
      return el;
    });
    expect(chart).toHaveTextContent("XamOps couldn't read its activity log just now.");
    serve(undefined, undefined, { status: 200, body: SUMMARY });
    await user.click(within(chart).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Changes per day, by result' }).closest('section')!.querySelectorAll('tbody tr')).toHaveLength(7));
  });

  it('sends the time filter as the server\'s from, and counts it as a filter', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
    expect(lastFeedCall().searchParams.has('from')).toBe(false);
    await user.selectOptions(screen.getByLabelText('Time'), '7d');
    await waitFor(() => expect(lastFeedCall().searchParams.has('from')).toBe(true));
    const from = Date.parse(lastFeedCall().searchParams.get('from')!);
    // Fixed to the minute, rounded up (rangeFrom.test.ts holds the 90-day option to the server's rule).
    expect(from % 60_000).toBe(0);
    expect(Date.now() - from).toBeGreaterThan(7 * 24 * 3600_000 - 60_000);
    expect(Date.now() - from).toBeLessThan(7 * 24 * 3600_000 + 60_000);
  });

  it('keeps "Last 90 days" inside the server\'s limit on every read, however long the page stays open', async () => {
    // Only Date is faked: Testing Library's waitFor and the search's debounce keep real timers.
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      const DAY = 24 * 3600_000;
      const picked = Date.parse('2026-10-20T09:30:41.250Z');
      vi.setSystemTime(picked);
      const sent: Array<{ from: string | null; refused: boolean }> = [];
      serve((url) => {
        const from = url.searchParams.get('from');
        // AutomationActivityController.requireRange, on a server clock 4 minutes ahead of this browser's.
        const refused = from !== null && Date.now() + 4 * 60_000 - Date.parse(from) > 90 * DAY;
        sent.push({ from, refused });
        if (refused) return { status: 400, body: { code: 'K8S_INVALID_REQUEST', field: 'from', message: 'from and to may be at most 90 days apart.' } };
        return url.searchParams.get('cursor') === 'page-2'
          ? { status: 200, body: feedPage([MODE, LEGACY], null) }
          : { status: 200, body: feedPage([PUT_BACK, APPLIED, TEST_RUN, HPA_CHANGE, REFUSED], 'page-2') };
      });
      const user = userEvent.setup();
      page();
      await screen.findByRole('table', { name: ACTIVITY_FEED_CAPTION });
      await user.selectOptions(screen.getByLabelText('Time'), '90d');
      await waitFor(() => expect(lastFeedCall().searchParams.has('from')).toBe(true));
      await waitFor(() => expect(feedRows()).toHaveLength(5));

      // Ten minutes later, on the same page: a filter, then Load older.
      vi.setSystemTime(picked + 10 * 60_000);
      const results = within(document.querySelector<HTMLElement>('[data-activity-results]')!);
      await user.click(results.getByRole('button', { name: 'Applied' }));
      await waitFor(() => expect(lastFeedCall().searchParams.get('result')).toBe('APPLIED'));
      await waitFor(() => expect(feedRows()).toHaveLength(5));
      await user.click(screen.getByRole('button', { name: 'Load older' }));
      await waitFor(() => expect(feedRows()).toHaveLength(7));
      expect(lastFeedCall().searchParams.get('cursor')).toBe('page-2');

      expect(document.querySelector('[data-activity-failure]')).toBeNull();
      expect(screen.queryByText(/Couldn't load older entries/)).toBeNull();
      const ranged = sent.filter((s) => s.from !== null);
      expect(ranged.length).toBeGreaterThanOrEqual(3);
      expect(ranged.filter((s) => s.refused)).toEqual([]);
      // Each read asks for the 90 days before it was sent, not before the pick.
      expect(Date.parse(ranged[ranged.length - 1].from!) - Date.parse(ranged[0].from!)).toBe(10 * 60_000);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('Automation › Activity: a backend without the feed (latest change per workload)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    // An untyped 404 from the dispatcher: this backend has no activity feed.
    serve({ status: 404, body: null });
  });
  afterEach(() => vi.restoreAllMocks());

  it("falls back to endpoint 1, read once, listing each workload's latest change, newest first, coloured by result", async () => {
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    // Endpoint 1 is read exactly once; the RootCausePanel reads the /rca sub-route once more.
    expect(endpointOneCalls()).toHaveLength(1);
    expect(screen.getByText(LATEST_ONLY_NOTE)).toBeInTheDocument();
    expect(workloadNames().map((t) => t.split('Deployment')[0])).toEqual(['throttled-encoder', 'idle-batch-listener', 'overprovisioned-api', 'solo']);
    const rows = [...latestTable().querySelectorAll('tbody tr')];
    expect(rows.map((tr) => tr.querySelector('[data-tone]')?.getAttribute('data-tone'))).toEqual(['bad', 'good', 'info', 'info']);
    expect(rows.map((tr) => tr.querySelector('[data-tone]')?.textContent)).toEqual(['Failed', 'Applied', 'Test run passed', 'Mode changed']);
    expect(rows[0].querySelector('[data-feature-dot="workload"]')).not.toBeNull();
    expect(screen.queryByRole('table', { name: ACTIVITY_FEED_CAPTION })).toBeNull();
  });

  it('counts the KPIs over the latest change per workload, and opens with the Now sentence', async () => {
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    const kpis = [...document.querySelectorAll('[data-activity-kpis] [data-kpi-tone]')].map((k) => k.textContent);
    expect(kpis[0]).toMatch(/^Applied1/);
    expect(kpis[1]).toMatch(/^Test runs1/);
    expect(kpis[2]).toMatch(/^Refused or failed1/);
    expect(kpis[3]).toMatch(/^In place now1/);
    await waitFor(() => expect(document.querySelector('[data-now-banner]')).toHaveTextContent('idle-batch-listener is in place now.'));
  });

  it('filters by source, by result and by text on the page', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    const sources = within(document.querySelector<HTMLElement>('[data-activity-sources]')!);
    expect(sources.getByRole('button', { name: 'Workload autoscaler 4' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(sources.getByRole('button', { name: 'HPA 0' }));
    expect(screen.getByText(/No HPA change is recorded here/)).toBeInTheDocument();
    await user.click(sources.getByRole('button', { name: 'All 4' }));
    const result = within(screen.getByRole('group', { name: 'Result' }));
    await user.click(result.getByRole('button', { name: 'Test run' }));
    expect(workloadNames().map((t) => t.split('Deployment')[0])).toEqual(['overprovisioned-api']);
    await user.click(result.getByRole('button', { name: 'All' }));
    await user.type(screen.getByRole('searchbox', { name: `Search ${LATEST_CHANGE_CAPTION.toLowerCase()}` }), 'idle');
    expect(workloadNames().map((t) => t.split('Deployment')[0])).toEqual(['idle-batch-listener']);
  });

  it('keeps the Kubernetes events from the XamOps agent a separate, labelled stream of scaling and resize reasons', async () => {
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    const stream = document.querySelector<HTMLElement>('[data-activity-events]')!;
    expect(within(stream).getByRole('heading', { name: EVENTS_STREAM_TITLE })).toBeInTheDocument();
    const events = within(stream).getByRole('table', { name: EVENTS_STREAM_TITLE });
    expect([...events.querySelectorAll('tbody tr')].map((tr) => tr.textContent)).toEqual([
      expect.stringContaining('ResizeDeferred'),
      expect.stringContaining('ResizeCompleted'),
    ]);
    expect(events).not.toHaveTextContent('BackOff');
    expect(latestTable()).not.toHaveTextContent('ResizeCompleted');
    expect(latestTable().querySelectorAll('tbody tr')).toHaveLength(4);
    expect(stream.querySelector('[data-tone="warn"]')).toHaveTextContent('Warning');
    expect(stream.querySelector('[data-tone="info"]')).toHaveTextContent('Normal');
  });

  it('says so when the agent reported no scaling events', async () => {
    page({ loading: false, dashboard: { k8sEvents: [] } } as unknown as UseEksDashboardReturn);
    expect(await screen.findByText('No scaling, resize or eviction events in what the XamOps agent reported.')).toBeInTheDocument();
  });

  it('shows an endpoint-1 read failure with Retry', async () => {
    serve({ status: 404, body: null }, { status: 403, body: { code: 'K8S_CLUSTER_UNREACHABLE', message: 'The API server did not answer.' } });
    page();
    await waitFor(() => expect(document.querySelector('[data-read-failure]')).not.toBeNull());
    await waitFor(() => expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read its recorded changes just now."));
    expect(screen.queryByRole('table', { name: LATEST_CHANGE_CAPTION })).toBeNull();
    expect(document.querySelector('[data-activity-events]')).not.toBeNull();
  });
});
