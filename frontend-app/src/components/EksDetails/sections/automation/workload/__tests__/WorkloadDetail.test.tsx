import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import WorkloadsPage from '../WorkloadsPage';
import {
  ACCOUNT,
  APPLIED_REPLY,
  CLUSTER,
  DEFERRED_REPLY,
  DEMO_REFUSAL,
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
import { APPLIED, REFUSED, feedPage } from '../../activity/__tests__/activityFixtures';
import type { ActivityEntryWire } from '../../../../../../types/automationActivity';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const method = (init: unknown) => ((init as RequestInit | undefined)?.method ?? 'GET');
const isFeed = (url: unknown) => String(url).includes('/api/eks/automation/activity/');
const isUsage = (url: unknown) => String(url).includes('/api/eks/automation/usage/');
/** Endpoint-1 reads; the activity feed and the usage feed (FEAT-004) are counted apart. */
const gets = () => mockedFetch.mock.calls.filter(([url, init]) => method(init) === 'GET' && !isFeed(url) && !isUsage(url));
const feedGets = () => mockedFetch.mock.calls.filter(([url, init]) => method(init) === 'GET' && isFeed(url)).map(([url]) => new URL(String(url), 'http://test'));
const usageGets = () => mockedFetch.mock.calls.filter(([url, init]) => method(init) === 'GET' && isUsage(url)).map(([url]) => new URL(String(url), 'http://test'));
const posts = () => mockedFetch.mock.calls.filter(([, init]) => method(init) === 'POST');

type UsageAnswer = (url: URL) => { status: number; body: unknown };
/** A backend without the usage feed: the dispatcher's untyped 404. */
const NO_USAGE: UsageAnswer = () => ({ status: 404, body: null });

/**
 * Endpoint 1 for every GET, the activity feed (`feed`: its entries, or `null` for a backend without
 * the feed) and the usage feed (`usage`) for their own; `apply` answers the POST (a promise lets a
 * test hold it open).
 */
function serve(apply?: () => Promise<Response> | Response, feed: ActivityEntryWire[] | null = [], usage: UsageAnswer = NO_USAGE) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if (method(init) === 'GET' && isFeed(url)) return feed === null ? json(404, null) : json(200, feedPage(feed));
    if (method(init) === 'GET' && isUsage(url)) {
      const a = usage(new URL(String(url), 'http://test'));
      return json(a.status, a.body);
    }
    if (method(init) === 'GET') return json(200, endpointOne());
    if (method(init) === 'POST' && apply) return apply();
    throw new Error(`unexpected ${method(init)} ${url}`);
  });
}

const isoSec = (sec: number) => new Date(sec * 1000).toISOString().replace('.000Z', 'Z');
/** scope=workload, in Prometheus units: the busiest pod's use (one point unmeasured), its request and limit. */
function workloadUsage(u: URL, used: (i: number) => number | null, request: number, limit: number | null) {
  const custom = u.searchParams.get('window') === 'custom';
  const step = custom ? Number(u.searchParams.get('step')) : 600;
  const start = custom ? Date.parse(u.searchParams.get('from')!) / 1000 : Math.floor(Date.now() / 60_000) * 60 - 24 * 3600;
  const end = custom ? Date.parse(u.searchParams.get('to')!) / 1000 : start + 24 * 3600;
  const times: number[] = [];
  for (let t = start; t <= end; t += step) times.push(t);
  return {
    schemaVersion: 1, scope: 'workload', resource: u.searchParams.get('resource'), unit: u.searchParams.get('resource') === 'cpu' ? 'cores' : 'bytes',
    workloadRef: { namespace: u.searchParams.get('namespace'), kind: u.searchParams.get('kind'), name: u.searchParams.get('name') },
    window: u.searchParams.get('window'), stepSeconds: step, start: isoSec(start), end: isoSec(end),
    source: { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' },
    series: [
      { key: 'used', kind: 'used', label: 'Used', points: times.map((t, i) => [t, used(i)]), query: 'max(sum by (pod) (rate(container_cpu_usage_seconds_total{…}[5m])))' },
      { key: 'request', kind: 'request', label: 'Request now', points: times.map((t) => [t, request]), query: 'requests' },
      { key: 'limit', kind: 'limit', label: 'Limit', points: times.map((t) => [t, limit]), query: 'limits' },
    ],
    namespaces: null,
    coverage: { firstSampleAt: isoSec(start), covered: 1 },
    unavailable: null,
  };
}

const detail = (ref: string) =>
  renderAt(
    `${PAGE}&workload=${encodeURIComponent(ref)}`,
    <WorkloadsPage clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} workloadsHref={WORKLOADS_HREF} policiesHref={POLICIES_HREF} />,
  );

/** Turns the test run off and confirms a real resize from the workload's own card. */
async function applyLive(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(screen.getByRole('checkbox', { name: 'Test run only (nothing is sent)' }));
  await user.click(screen.getByRole('button', { name: `Apply ${name}` }));
  await user.click(screen.getByRole('button', { name: 'Resize pods' }));
}

const stepper = () => document.querySelector<HTMLElement>('[data-apply-stepper]');
const stepTitles = () => [...(stepper()?.querySelectorAll('[data-apply-step]') ?? [])].map((s) => s.querySelector('div > div')?.textContent);

describe('Workload autoscaler › Workloads › one workload (UI v3 plan §2 "Workload detail")', () => {
  beforeEach(() => mockedFetch.mockReset());
  afterEach(() => vi.restoreAllMocks());

  it('opens the detail: back link, Now sentence, lifecycle, sizes, recent change and its own card only', async () => {
    serve();
    detail('team-a/Deployment/overprovisioned-api');
    expect(await screen.findByText('Where it is now')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All workloads' }).getAttribute('href')).not.toContain('workload=');
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      'XamOps suggests 1000m → 25m per pod, freeing 1.95 vCPU and 1.5 GiB across 2 pods. Nothing changes until you click Apply.',
    );
    const stages = [...document.querySelectorAll('[data-step-indicator="full"] [data-stage]')];
    expect(stages.map((s) => s.getAttribute('data-stage-status'))).toEqual(['done', 'done', 'todo', 'todo', 'todo']);
    expect(stages[1]).toHaveTextContent('CPU 1000m → 25m, memory 1 GiB → 256Mi');

    const sizes = screen.getByRole('heading', { name: 'Request now, suggested and limit, per container' }).closest('section')!;
    const api = within(sizes).getByRole('rowheader', { name: 'api', hidden: true }).closest('tr')!;
    expect([...api.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['1000m', '25m', '1000m', '1 GiB', '256Mi', '1 GiB']);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Recent changes' }).closest('section')).toHaveTextContent("XamOps hasn't recorded a change to this workload."));

    // Only this workload's card, with the mode switch and the test-run switch.
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Apply throttled-encoder' })).toBeNull();
    expect(screen.getByRole('group', { name: 'Automation mode for overprovisioned-api' })).toBeInTheDocument();
    expect(document.querySelectorAll('[data-now-banner]')).toHaveLength(1);
    // Endpoint 1 once for the page; the activity feed once, for this workload's recent changes.
    expect(gets()).toHaveLength(1);
    expect(feedGets()).toHaveLength(1);
  });

  it('names every chart XamOps can\'t draw yet, and why', async () => {
    serve();
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    const body = (title: string) => screen.getByRole('heading', { name: title }).closest('section')!.querySelector('[data-chart-body]')!;
    // No cost on this read (the cost feature off): a reason, never a $0.
    expect(body('Cost per day')).toHaveTextContent("XamOps didn't price this workload: the cost read is off in this environment.");
    expect(body('Did the new size take effect?')).toHaveTextContent("Needs the pod's cgroup series");
    // A backend without the usage feed (an untyped 404) renders no usage chart, and nothing was applied
    // live, so there is no impact chart either.
    await waitFor(() => expect(usageGets()).toHaveLength(1));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'CPU and memory over the window' })).toBeNull());
    expect(screen.queryByRole('heading', { name: 'Impact: CPU around the change' })).toBeNull();
  });

  it("charts the workload's use against its request and limit over its window, with the suggestion now as a line", async () => {
    const user = userEvent.setup();
    serve(undefined, [], (u) => ({ status: 200, body: workloadUsage(u, (i) => (i === 5 ? null : 0.04), 1, 1) }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    const chart = await waitFor(() => {
      const el = screen.getByRole('heading', { name: 'CPU and memory over the window' }).closest('section')!;
      expect(el.querySelectorAll('tbody tr')).toHaveLength(145);
      return el;
    });
    const q = usageGets()[0];
    expect([q.searchParams.get('scope'), q.searchParams.get('resource'), q.searchParams.get('window')]).toEqual(['workload', 'cpu', '24h']);
    expect([q.searchParams.get('namespace'), q.searchParams.get('kind'), q.searchParams.get('name')]).toEqual(['team-a', 'Deployment', 'overprovisioned-api']);
    const rows = [...chart.querySelectorAll('tbody tr')];
    expect([...rows[0].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['40m', '1000m', '1000m']);
    // The step Prometheus didn't measure is a dash, never 0m.
    expect(rows[5].querySelector('td')).toHaveTextContent('—');
    expect(chart).toHaveTextContent('XamOps suggests 25m per pod now (the dashed line).');
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('the same pods and containers the suggestion reads');
    expect(chart.querySelector('[data-chart-source]')).toHaveTextContent('step 10 min, 24 hours.');
    await user.click(within(chart).getByRole('button', { name: 'Memory' }));
    await waitFor(() => expect(usageGets().some((u) => u.searchParams.get('resource') === 'memory')).toBe(true));
  });

  it("says in words why a workload has no usage chart: no pod running to read", async () => {
    serve(undefined, [], (u) => ({ status: 200, body: { ...workloadUsage(u, () => null, 1, 1), series: [], coverage: null, unavailable: { code: 'INSUFFICIENT_HISTORY', message: 'No running pod.' } } }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await waitFor(() => expect(screen.getByRole('heading', { name: 'CPU and memory over the window' }).closest('section')!.querySelector('[data-empty="unavailable"]'))
      .toHaveTextContent("No pod of this workload is running now, so XamOps can't tell which pods to read."));
  });

  it('charts CPU around the latest live change, from 3 hours before it at 1-minute steps, with what it freed', async () => {
    // idle-batch-listener's change 17 minutes ago, as the activity log holds it: 100m → 25m in each pod.
    const idleAt = endpointOne().workloads![2].lastAction!.at!;
    const applied: ActivityEntryWire = {
      ...APPLIED, id: 77, at: idleAt, target: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
      change: { containers: [{ container: 'sleeper', before: { cpuRequestMillis: 100, cpuLimitMillis: 200, memRequestBytes: null, memLimitBytes: null }, beforeBasis: 'OBSERVED', after: { cpuRequestMillis: 25, cpuLimitMillis: 50, memRequestBytes: null, memLimitBytes: null }, requested: null }], hpa: null, mode: null },
    };
    serve(undefined, [applied], (u) => ({ status: 200, body: workloadUsage(u, () => 0.002, 0.025, 0.05) }));
    detail('dev-sim/Deployment/idle-batch-listener');
    await screen.findByText('Where it is now');
    const impact = await waitFor(() => screen.getByRole('heading', { name: 'Impact: CPU around the change' }).closest('section')!);
    const custom = await waitFor(() => {
      const u = usageGets().find((x) => x.searchParams.get('window') === 'custom');
      expect(u).toBeDefined();
      return u!;
    });
    expect(custom.searchParams.get('step')).toBe('60');
    expect(custom.searchParams.get('resource')).toBe('cpu');
    const from = Date.parse(custom.searchParams.get('from')!);
    const to = Date.parse(custom.searchParams.get('to')!);
    expect(Math.abs(from - (Date.parse(idleAt) - 3 * 3600_000))).toBeLessThan(60_000);
    // 17 minutes after the change, so the window ends now (to the minute), not 3 hours after it.
    expect(Date.now() - to).toBeLessThan(60_000);
    expect(impact).toHaveTextContent(/from 3 hours before the change applied at .+ \(100m → 25m per pod\), at 1-minute steps\./);
    expect(impact).toHaveTextContent('The hatched band is the request it freed in each pod.');
    expect(impact).toHaveTextContent('Freed');
    expect(impact.querySelector('[data-chart-source]')).toHaveTextContent('The change: the activity log (FEAT-007).');
  });

  it('success: a live stepper while the apply runs, then every step, What changed and the next change time', async () => {
    const user = userEvent.setup();
    let release!: (r: Response) => void;
    serve(() => new Promise<Response>((resolve) => { release = resolve; }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await applyLive(user, 'overprovisioned-api');

    await waitFor(() => expect(stepper()).toHaveTextContent('Live: updates every 2 seconds.'));
    expect(stepTitles()).toEqual(['Waiting for the cluster…']);
    expect(document.querySelector('[data-stage="applying"]')).toHaveAttribute('data-stage-status', 'current');

    release(json(202, APPLIED_REPLY));
    await waitFor(() => expect(stepTitles()).toContain('Done: applied in every pod, no restart'));
    expect(stepper()).toHaveTextContent('Each step as it happened.');
    expect(stepTitles()).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 2, the test pod',
      'Resized pod 2 of 2',
      'Done: applied in every pod, no restart',
    ]);
    expect(stepper()!.querySelector('[data-result-tone="good"]')).toHaveTextContent('Applied');

    const changed = document.querySelector<HTMLElement>('[data-what-changed]')!;
    expect(changed).toHaveTextContent('Container api, in each pod. 1950m CPU freed across 2 pods.');
    // The wait is timed from when the reply arrived, recorded just after it renders.
    await waitFor(() => expect(changed).toHaveTextContent(/Next change allowed at .+ IST/));
    const first = within(changed).getByRole('rowheader', { name: /2bddb/ }).closest('tr')!;
    expect(first).toHaveTextContent('test pod, first');
    expect(first).toHaveTextContent('Same pod');
    expect(first).toHaveTextContent('1000m → 25m');
    expect(first).toHaveTextContent('1 GiB → 256Mi');
    expect(first).toHaveTextContent('0 → 0');
    expect(first.querySelector('[data-tone="good"]')).toHaveTextContent('Ready');

    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      /Resized .+: CPU request 1000m → 25m and limit 1000m → 25m on all 2 pods, in place, with no restart\./,
    );
    expect(document.querySelector('[data-stage="watching"]')).toHaveAttribute('data-stage-status', 'current');
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(String((posts()[0][1] as RequestInit).body))).toMatchObject({ dryRun: false });
  });

  // ResizeProof: restartFree false is "not proved", and skippedPods means "every pod" is never claimed.
  it('applied without the restart-free proof and with a pod left out: every running pod (2 of 3), never "every pod"', async () => {
    const user = userEvent.setup();
    serve(() => json(202, {
      ...APPLIED_REPLY,
      result: { ...APPLIED_REPLY.result, restartFree: false, skippedPods: [{ podName: 'overprovisioned-api-6f7d8-x9k2p', phase: 'Pending', reason: 'NOT_RUNNING' }] },
    }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await applyLive(user, 'overprovisioned-api');

    await waitFor(() => expect(stepTitles()).toContain('Done: applied in every running pod (2 of 3), no restart-free proof'));
    expect(stepTitles()).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 3, the test pod',
      'Resized pod 2 of 3',
      'Pod 3 of 3 not touched',
      'Done: applied in every running pod (2 of 3), no restart-free proof',
    ]);
    expect(stepper()).toHaveTextContent(
      "The Deployment itself wasn't changed, so a pod created later (a rollout, a rescheduled pod) starts at the size in its template, not the new one.",
    );
    const now = document.querySelector('[data-now-banner]')!;
    expect(now).toHaveTextContent(/on every running pod \(2 of 3\), in place, no restart-free proof\./);
    expect(now).not.toHaveTextContent(/on all|restart detected/);
    expect(now.querySelector('[data-tone="bad"]')).toBeNull();
    expect(document.querySelector('[data-stage="applying"]')).toHaveTextContent('Every running pod (2 of 3)');
    expect(document.querySelector('[data-stage="applied"]')).toHaveTextContent('no restart-free proof');
    expect(document.querySelector('[data-what-changed]')).toHaveTextContent(
      "Container api, in each pod XamOps resized. 1950m CPU freed across 2 pods. 1 more pod wasn't touched: it runs at the workload's own size.",
    );
    // Main's card on the same page says the same thing (EksAutomationTab, unchanged).
    expect(screen.getByText('Applied. Confirmed in every running pod (2 of 3).')).toBeInTheDocument();
    expect(screen.getByText('In place · no restart-free proof')).toBeInTheDocument();
  });

  // In-place resize never writes the template: after a first in-place change the pods' size before
  // this apply (25m / 250Mi) is not the template's (1000m / 1 GiB), so the caveat names no size.
  it('applied to pods resized in place before: the Done caveat names the template, not the pods\' old size', async () => {
    const user = userEvent.setup();
    const resizedBefore = { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 };
    serve(() => json(202, {
      ...APPLIED_REPLY,
      result: {
        ...APPLIED_REPLY.result,
        pods: APPLIED_REPLY.result.pods.map((p) => ({ ...p, containers: [{ ...p.containers[0], observedBefore: resizedBefore }] })),
      },
    }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await applyLive(user, 'overprovisioned-api');

    await waitFor(() => expect(stepTitles()).toContain('Done: applied in every pod, no restart'));
    const done = stepper()!.querySelector('[data-apply-step="done"]')!;
    expect(done).toHaveTextContent(
      "The Deployment itself wasn't changed, so a pod created later (a rollout, a rescheduled pod) starts at the size in its template, not the new one.",
    );
    expect(done).not.toHaveTextContent(/25m|250Mi|1000m/);
  });

  it('applied with a restart count that rose: the Restart row and "restart detected", and never "no restart"', async () => {
    const user = userEvent.setup();
    const [first, second] = APPLIED_REPLY.result.pods;
    serve(() => json(202, {
      ...APPLIED_REPLY,
      result: {
        ...APPLIED_REPLY.result,
        restartFree: false,
        pods: [{ ...first, containers: [{ ...first.containers[0], containerIdAfter: 'c2', restartCountAfter: 1 }] }, second],
      },
    }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await applyLive(user, 'overprovisioned-api');

    await waitFor(() => expect(stepTitles()).toContain('A container restarted during the resize'));
    expect(stepper()).toHaveTextContent('2bddb, container api: restart count 0 → 1. This was not a restart-free change.');
    expect(stepTitles()).toContain('Done: applied in every pod, no restart-free proof');
    const now = document.querySelector('[data-now-banner]')!;
    expect(now).toHaveTextContent(/on all 2 pods, restart detected\./);
    expect(now.querySelector('[data-tone="bad"]')).toHaveTextContent('overprovisioned-api: restart detected');
    expect(document.querySelector('[data-stage="applied"]')).toHaveTextContent('restart detected');
  });

  it('deferred: the kubelet\'s deferral, "So XamOps put the pod back", and nothing changed', async () => {
    const user = userEvent.setup();
    serve(() => json(202, DEFERRED_REPLY));
    detail('prod-sim/Deployment/throttled-encoder');
    await screen.findByText('Where it is now');
    await applyLive(user, 'throttled-encoder');

    await waitFor(() => expect(stepTitles()).toContain('So XamOps put the pod back'));
    // The reply lists only the test pod; endpoint 1 says throttled-encoder has 2 replicas, so pod 2
    // was never reached (mock 14: "Pod 2 of 2 not touched").
    expect(stepTitles()).toEqual([
      'Checked the safety rules: all passed',
      'Resizing pod 1 of 2, the test pod',
      'Kubernetes deferred this: the node has no free CPU',
      'So XamOps put the pod back',
      'Pod 2 of 2 not touched',
      'Result: not applied. Nothing changed.',
    ]);
    expect(stepper()).toHaveTextContent("XamOps stops at the first pod that doesn't take the change.");
    expect(stepper()).toHaveTextContent('Back to 10m CPU, 64Mi memory, and the pod confirmed it: nothing is pending. No restart.');
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      'throttled-encoder: XamOps suggests 63m (limit 200m). Kubernetes deferred the change: its node had no free room. XamOps put the pod back. Nothing changed.',
    );
    expect(document.querySelector('[data-stage="applying"]')).toHaveAttribute('data-stage-status', 'problem');
    // Put back and proved back: no wait starts, so no "Next change allowed".
    expect(document.querySelector('[data-what-changed]')).not.toHaveTextContent('Next change allowed');
  });

  it('refused: stopped by a rule before anything was sent, in plain words', async () => {
    const user = userEvent.setup();
    serve(() => json(403, DEMO_REFUSAL));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await applyLive(user, 'overprovisioned-api');

    await waitFor(() => expect(stepTitles()).toEqual(['Stopped by a safety rule', 'Result: not applied. Nothing changed.']));
    expect(stepper()).toHaveTextContent("Demo workspace. Changes aren't saved.");
    expect(stepper()!.querySelector('[data-result-tone="bad"]')).toHaveTextContent('Refused: nothing was sent');
    expect(document.querySelector('[data-what-changed]')).toBeNull();
    // The caller-level refusal greys Apply on the page, as the tab always has.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled());
  });

  it('a test run shows its stepper and changes nothing', async () => {
    const user = userEvent.setup();
    serve(() => json(202, { ...APPLIED_REPLY, dryRun: true, result: { ...APPLIED_REPLY.result, terminal: 'DRY_RUN', pods: [] } }));
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await user.click(screen.getByRole('button', { name: 'Apply overprovisioned-api' }));
    await waitFor(() => expect(stepTitles()).toContain('Test run passed: nothing was changed'));
    expect(document.querySelector('[data-what-changed]')).toBeNull();
    expect(JSON.parse(String((posts()[0][1] as RequestInit).body))).toMatchObject({ dryRun: true });
  });

  it("lists the workload's recent changes from the activity feed, matched exactly on its ref", async () => {
    const elsewhere: ActivityEntryWire = { ...APPLIED, id: 99, target: { namespace: 'team-z', kind: 'Deployment', name: 'overprovisioned-api' } };
    const longer: ActivityEntryWire = { ...APPLIED, id: 98, target: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api-canary' } };
    serve(undefined, [APPLIED, elsewhere, longer, REFUSED]);
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    const recent = screen.getByRole('heading', { name: 'Recent changes' }).closest('section')!;
    await waitFor(() => expect(recent.querySelectorAll('[data-recent-entry]')).toHaveLength(2));
    const [applied, refused] = [...recent.querySelectorAll<HTMLElement>('[data-recent-entry]')];
    expect(applied.querySelector('[data-tone="good"]')).toHaveTextContent('Applied');
    expect(applied).toHaveTextContent('Restart-free');
    expect(applied).toHaveTextContent('api: CPU request 1000m → 25m');
    expect(applied).toHaveTextContent('A user');
    expect(refused.querySelector('[data-tone="bad"]')).toHaveTextContent('Refused: nothing was sent');
    expect(recent).toHaveTextContent('Source: the activity log');
    const url = feedGets()[0];
    expect(url.searchParams.get('feature')).toBe('VERTICAL');
    expect(url.searchParams.get('q')).toBe('overprovisioned-api');
  });

  it('shows the latest recorded change and the wait for a workload applied earlier, without the activity feed', async () => {
    serve(undefined, null);
    detail('dev-sim/Deployment/idle-batch-listener');
    await screen.findByText('Where it is now');
    const recent = screen.getByRole('heading', { name: 'Recent changes' }).closest('section')!;
    await waitFor(() => expect(recent.querySelector('[data-recent-changes="not-deployed"]')).not.toBeNull());
    expect(recent.querySelector('[data-tone="good"]')).toHaveTextContent('Applied');
    expect(recent).toHaveTextContent('Live');
    expect(recent).toHaveTextContent('Test runs and changes that were put back need the activity log.');
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(/Last change XamOps recorded: .+, applied\. XamOps waits until .+ before it suggests another change\./);
    expect(document.querySelector('[data-stage="watching"]')).toHaveAttribute('data-stage-status', 'current');
  });

  it('goes back to the list in place', async () => {
    const user = userEvent.setup();
    serve();
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    await user.click(screen.getByRole('link', { name: 'All workloads' }));
    expect(await screen.findByRole('table', { name: 'Workloads at a glance' })).toBeInTheDocument();
    expect(currentSearch().has('workload')).toBe(false);
    expect(gets()).toHaveLength(1);
  });

  it('renders the confidence chip for a recommended container, with its why sub-line (FEAT-001)', async () => {
    const scored = wire('scored-api', 'team-a', {
      containers: [{
        name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256),
        reasons: ['LIMIT_MOVED'], upstreamVpa: null,
        confidence: { score: 0.82, band: 'HIGH', sampleCoverage: 0.96, windowHours: 24, belowFloor: false },
      }],
    });
    mockedFetch.mockImplementation(async () => json(200, endpointOne([scored])));
    detail('team-a/Deployment/scored-api');
    await screen.findByText('Where it is now');

    const confidence = document.querySelector<HTMLElement>('[data-confidence]')!;
    expect(confidence).toHaveTextContent('0.82 · High');
    expect(confidence).toHaveTextContent('96% window coverage over 24h');
  });

  it('shows no confidence block when nothing is recommended', async () => {
    const noRec = wire('rightsized', 'team-a', {
      containers: [{
        name: 'api', qosClass: 'Burstable', current: res(100, 256), recommended: null,
        reasons: ['WITHIN_HYSTERESIS'], upstreamVpa: null, confidence: null,
      }],
    });
    mockedFetch.mockImplementation(async () => json(200, endpointOne([noRec])));
    detail('team-a/Deployment/rightsized');
    await screen.findByText('Where it is now');

    expect(document.querySelector('[data-confidence]')).toBeNull();
  });

  describe('cost rows (FEAT-002)', () => {
    it('shows Current cost (plain ink) and Est. monthly savings (via Delta) when priced', async () => {
      const w = wire('priced-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
        cost: { monthlyCost: 12.34, monthlySavings: 6.84, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false, note: 'AWS Price List on-demand rate' },
      });
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w])));
      detail('team-a/Deployment/priced-api');
      await screen.findByText('Where it is now');

      const cost = document.querySelector<HTMLElement>('[data-cost]')!;
      expect(cost).toHaveTextContent('Current cost');
      // formatMoney renders whole dollars above $1 (12.34 → $12, 6.84 → $7).
      expect(cost).toHaveTextContent('$12/mo');
      // The savings figure carries the money tone via Delta; current cost does not.
      expect(cost.querySelector('[data-delta="saved"]')).toHaveTextContent('$7/mo');
      expect(cost.querySelector('[data-assumed-on-demand]')).toBeNull();
    });

    it('shows a dash + reason tooltip when the money is unavailable', async () => {
      const w = wire('unpriced-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
        cost: { monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', source: null, capacityType: null, capacityTypeInferred: false, note: null },
      });
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w])));
      detail('team-a/Deployment/unpriced-api');
      await screen.findByText('Where it is now');

      const cost = document.querySelector<HTMLElement>('[data-cost]')!;
      expect(cost.querySelector('[data-delta]')).toBeNull();
      expect(cost).toHaveTextContent('—');
      const dd = cost.querySelectorAll('dd');
      expect(dd[0].getAttribute('title')).toContain('No live AWS price');
    });

    it('appends "assumed on-demand" beside both figures when capacityTypeInferred', async () => {
      const w = wire('inferred-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
        cost: { monthlyCost: 30, monthlySavings: 4.2, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: true, note: null },
      });
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w])));
      detail('team-a/Deployment/inferred-api');
      await screen.findByText('Where it is now');

      const cost = document.querySelector<HTMLElement>('[data-cost]')!;
      expect(cost.querySelectorAll('[data-assumed-on-demand]')).toHaveLength(2);
      expect(cost.querySelector('[data-assumed-on-demand]')).toHaveTextContent('assumed on-demand');
    });

    it('renders no cost card when the response carried no cost (feature off / older build)', async () => {
      const w = wire('no-cost-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
      });
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w])));
      detail('team-a/Deployment/no-cost-api');
      await screen.findByText('Where it is now');

      expect(document.querySelector('[data-cost]')).toBeNull();
    });

    it("fills Cost per day with today's priced rate, never a series (owner decision A)", async () => {
      const w = wire('priced-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
        cost: { monthlyCost: 73, monthlySavings: 6.84, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false, note: 'AWS Price List on-demand rate' },
      });
      const summary = { currency: 'USD', hoursPerMonth: 730, monthlySavings: 6.84, monthlySavingsUnavailableReason: null, clusterMonthlyCost: 73, pricedWorkloads: 1, unpricedWorkloads: 0 };
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w], { cost: summary })));
      detail('team-a/Deployment/priced-api');
      await screen.findByText('Where it is now');

      const card = screen.getByRole('heading', { name: 'Cost per day' }).closest('section')!;
      // $73/mo over 730 hours = $2.40/day, formatMoney's whole dollars.
      expect(card.querySelector('[data-cost-per-day]')).toHaveTextContent('$2/day');
      expect(card).toHaveTextContent("At today's price: $73/mo over 730 hours a month.");
      expect(card).toHaveTextContent('AWS Price List on-demand rate');
      expect(card).toHaveTextContent('XamOps keeps no daily cost history, so there is no series to draw.');
      expect(card.querySelector('svg.recharts-surface')).toBeNull();
    });

    it("shows a dash and the backend's reason on Cost per day when the node isn't priced", async () => {
      const w = wire('unpriced-api', 'team-a', {
        containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
        cost: { monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', source: null, capacityType: null, capacityTypeInferred: false, note: null },
      });
      mockedFetch.mockImplementation(async () => json(200, endpointOne([w])));
      detail('team-a/Deployment/unpriced-api');
      await screen.findByText('Where it is now');

      const body = screen.getByRole('heading', { name: 'Cost per day' }).closest('section')!.querySelector('[data-chart-body]')!;
      expect(body).toHaveTextContent('— No live AWS price for this node type.');
      expect(body.querySelector('[data-cost-per-day]')).toBeNull();
    });
  });
});
