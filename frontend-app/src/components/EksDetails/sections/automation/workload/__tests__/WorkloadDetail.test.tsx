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

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const method = (init: unknown) => ((init as RequestInit | undefined)?.method ?? 'GET');
const gets = () => mockedFetch.mock.calls.filter(([, init]) => method(init) === 'GET');
const posts = () => mockedFetch.mock.calls.filter(([, init]) => method(init) === 'POST');

/** Endpoint 1 for every GET; `apply` answers the POST (a promise lets a test hold it open). */
function serve(apply?: () => Promise<Response> | Response) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if (method(init) === 'GET') return json(200, endpointOne());
    if (method(init) === 'POST' && apply) return apply();
    throw new Error(`unexpected ${method(init)} ${url}`);
  });
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
    expect(screen.getByRole('heading', { name: 'Recent change' }).closest('section')).toHaveTextContent("XamOps hasn't recorded a change to this workload.");

    // Only this workload's card, with the mode switch and the test-run switch.
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Apply throttled-encoder' })).toBeNull();
    expect(screen.getByRole('group', { name: 'Automation mode for overprovisioned-api' })).toBeInTheDocument();
    expect(document.querySelectorAll('[data-now-banner]')).toHaveLength(1);
    expect(gets()).toHaveLength(1);
  });

  it('names every chart XamOps can\'t draw yet, and why', async () => {
    serve();
    detail('team-a/Deployment/overprovisioned-api');
    await screen.findByText('Where it is now');
    const body = (title: string) => screen.getByRole('heading', { name: title }).closest('section')!.querySelector('[data-chart-body]')!;
    expect(body('CPU and memory over the window')).toHaveTextContent('History arrives with the usage feed');
    expect(body('Impact around the change')).toHaveTextContent('History arrives with the usage feed');
    expect(body('Cost per day')).toHaveTextContent("Needs CPU and memory prices, which XamOps doesn't read yet.");
    expect(body('Did the new size take effect?')).toHaveTextContent("Needs the pod's cgroup series");
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

  it('shows the latest recorded change and the wait for a workload applied earlier', async () => {
    serve();
    detail('dev-sim/Deployment/idle-batch-listener');
    await screen.findByText('Where it is now');
    const recent = screen.getByRole('heading', { name: 'Recent change' }).closest('section')!;
    expect(recent.querySelector('[data-tone="good"]')).toHaveTextContent('Applied');
    expect(recent).toHaveTextContent('Live');
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
  });
});
