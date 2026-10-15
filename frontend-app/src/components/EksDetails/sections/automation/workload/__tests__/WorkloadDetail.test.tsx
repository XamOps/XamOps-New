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

  it('deferred: the kubelet\'s deferral, "So XamOps put the pod back", and nothing changed', async () => {
    const user = userEvent.setup();
    serve(() => json(202, DEFERRED_REPLY));
    detail('prod-sim/Deployment/throttled-encoder');
    await screen.findByText('Where it is now');
    await applyLive(user, 'throttled-encoder');

    await waitFor(() => expect(stepTitles()).toContain('So XamOps put the pod back'));
    expect(stepTitles()).toEqual([
      'Checked the safety rules: all passed',
      'Resizing pod 1 of 1, the test pod',
      'Kubernetes deferred this: the node has no free CPU',
      'So XamOps put the pod back',
      'Result: not applied. Nothing changed.',
    ]);
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
});
