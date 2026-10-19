import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import type { UseEksDashboardReturn } from '../../../../../../hooks/useEksDashboard';
import ActivityPage, { EVENTS_STREAM_TITLE, LATEST_ONLY_NOTE } from '../ActivityPage';
import { LATEST_CHANGE_CAPTION } from '../LatestChangeTable';
import { ACCOUNT, CLUSTER, IDLE, OVERPROVISIONED, REGION, SOLO, THROTTLED, endpointOne, iso, json, renderAt, wire } from '../../workload/__tests__/fixtures';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;
const method = (init: unknown) => ((init as RequestInit | undefined)?.method ?? 'GET');
const PAGE = `/eks-details?name=${CLUSTER}&accountIds=${ACCOUNT}&region=${REGION}&tab=automation&sub=activity`;

// Each workload's latest change, as endpoint 1 records it: idle-batch-listener applied 17 minutes
// ago (fixtures), a test run, a failure, and a mode switch.
const TESTED = wire('overprovisioned-api', 'team-a', { ...OVERPROVISIONED, lastAction: { at: iso(30 * 60_000), dryRun: true, outcome: 'APPLIED' } });
const FAILED = wire('throttled-encoder', 'prod-sim', { ...THROTTLED, lastAction: { at: iso(5 * 60_000), dryRun: false, outcome: 'FAILED' } });
const MODE = wire('solo', 'team-b', { ...SOLO, lastAction: { at: iso(2 * 24 * 3600_000), dryRun: false, outcome: 'POLICY_CHANGE' } });

// The RCA sub-route the RootCausePanel reads (FEAT-002). Its honest empty body keeps the panel in
// its empty state without adding causes to the assertions above.
const RCA_EMPTY = { generatedAt: iso(0), windowHours: 24, analyzed: { actionRows: 0, workloads: 0 }, causes: [], dependencies: [{ kind: 'OOM_EVICTION', needs: 'Kubernetes Warning events persisted server-side' }] };

function serve(body: unknown = endpointOne([TESTED, FAILED, IDLE, MODE]), status = 200) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if (method(init) !== 'GET') throw new Error(`unexpected write ${method(init)} ${url}`);
    if (url.includes('/rca')) return json(200, RCA_EMPTY);
    return json(status, body);
  });
}

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

const latestTable = () => screen.getByRole('table', { name: LATEST_CHANGE_CAPTION });
const workloadNames = () =>
  [...latestTable().querySelectorAll('tbody tr')].map((tr) => tr.querySelectorAll('td')[1]?.textContent ?? '');

describe('Automation › Activity (UI v3 plan §2)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    serve();
  });
  afterEach(() => vi.restoreAllMocks());

  it("reads endpoint 1 once and lists each workload's latest change, newest first, coloured by result", async () => {
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    // Endpoint 1 is read exactly once; the RootCausePanel reads the /rca sub-route once more.
    const endpointOneCalls = mockedFetch.mock.calls.filter(([url]) => !String(url).includes('/rca'));
    expect(endpointOneCalls).toHaveLength(1);
    expect(workloadNames().map((t) => t.split('Deployment')[0])).toEqual(['throttled-encoder', 'idle-batch-listener', 'overprovisioned-api', 'solo']);
    const rows = [...latestTable().querySelectorAll('tbody tr')];
    expect(rows.map((tr) => tr.querySelector('[data-tone]')?.getAttribute('data-tone'))).toEqual(['bad', 'good', 'info', 'info']);
    expect(rows.map((tr) => tr.querySelector('[data-tone]')?.textContent)).toEqual(['Failed', 'Applied', 'Test run passed', 'Mode changed']);
    expect(rows[2]).toHaveTextContent('Test run');
    expect(rows[3]).toHaveTextContent('Mode change');
    // The workload's source is the Workload autoscaler, by name and its feature dot.
    expect(rows[0].querySelector('[data-feature-dot="workload"]')).not.toBeNull();
    expect(rows[0]).toHaveTextContent('Workload autoscaler');
  });

  it('counts the KPIs over the latest change per workload, and opens with the Now sentence', async () => {
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    const kpis = [...document.querySelectorAll('[data-activity-kpis] [data-kpi-tone]')].map((k) => k.textContent);
    expect(kpis[0]).toMatch(/^Applied1/);
    expect(kpis[1]).toMatch(/^Test runs1/);
    expect(kpis[2]).toMatch(/^Refused or failed1/);
    expect(kpis[3]).toMatch(/^In place now1/);
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent('idle-batch-listener is in place now.');
  });

  it('filters by source (chips with their feature dots), by result and by text', async () => {
    const user = userEvent.setup();
    page();
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    const sources = within(document.querySelector<HTMLElement>('[data-activity-sources]')!);
    expect(sources.getByRole('button', { name: 'Workload autoscaler 4' })).toHaveAttribute('aria-pressed', 'false');
    expect(sources.getByRole('button', { name: 'HPA 0' }).querySelector('[data-feature-dot="hpa"]')).not.toBeNull();
    await user.click(sources.getByRole('button', { name: 'HPA 0' }));
    expect(screen.getByText(/No HPA change is recorded here yet/)).toBeInTheDocument();
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
    // Never mixed into XamOps's rows.
    expect(latestTable()).not.toHaveTextContent('ResizeCompleted');
    expect(latestTable()).not.toHaveTextContent('ResizeDeferred');
    expect(latestTable().querySelectorAll('tbody tr')).toHaveLength(4);
    expect(stream.querySelector('[data-tone="warn"]')).toHaveTextContent('Warning');
    expect(stream.querySelector('[data-tone="info"]')).toHaveTextContent('Normal');
  });

  it('says so when the agent reported no scaling events, or no events at all', async () => {
    page({ loading: false, dashboard: { k8sEvents: [] } } as unknown as UseEksDashboardReturn);
    expect(await screen.findByText('No scaling, resize or eviction events in what the XamOps agent reported.')).toBeInTheDocument();
  });

  it('keeps the per-day chart in its honest empty state and says the table shows only the latest change', async () => {
    page(null);
    await screen.findByRole('table', { name: LATEST_CHANGE_CAPTION });
    expect(screen.getByRole('heading', { name: 'Changes per day, by result' }).closest('section')!.querySelector('[data-empty="history-pending"]'))
      .toHaveTextContent('History arrives with the usage feed');
    expect(screen.getByText(LATEST_ONLY_NOTE)).toBeInTheDocument();
    expect(screen.getByText("The XamOps agent hasn't reported this cluster's Kubernetes events.")).toBeInTheDocument();
  });

  it('shows a read failure with Retry, and the events stream still renders', async () => {
    serve({ code: 'K8S_CLUSTER_UNREACHABLE', message: 'The API server did not answer.' }, 403);
    page();
    await waitFor(() => expect(document.querySelector('[data-read-failure]')).not.toBeNull());
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read its recorded changes just now.");
    expect(screen.queryByRole('table', { name: LATEST_CHANGE_CAPTION })).toBeNull();
    expect(document.querySelector('[data-activity-events]')).not.toBeNull();
  });
});
