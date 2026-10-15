import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import PoliciesPage, { POLICIES_HEADER } from '../PoliciesPage';
import { EKS_SECTIONS } from '../../../../../../hooks/useEksSectionRoute';
import {
  ACCOUNT,
  CLUSTER,
  IDLE,
  OVERPROVISIONED,
  POLICIES_HREF,
  REGION,
  SOLO,
  THROTTLED,
  WORKLOADS_HREF,
  endpointOne,
  json,
  renderAt,
  res,
  wire,
} from './fixtures';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;
const method = (init: unknown) => ((init as RequestInit | undefined)?.method ?? 'GET');

const AUTO = wire('auto-api', 'team-a', {
  policy: { mode: 'AUTO', headroomPct: 20, percentile: 90, window: '72h', applyMethod: 'IN_PLACE' },
  provenance: { percentile: 90, windowHours: 72, windowStart: null, windowEnd: null, sampleCoverage: 1, usageSource: 'prometheus-proxy', policySource: 'WORKLOAD_ROW' },
  containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: ['HPA_CPU_MANAGED'], upstreamVpa: null }],
  hpa: { present: true, name: 'auto-api' },
});
const LOCKED = wire('locked', 'team-a', { skipReason: 'LOCKED', containers: [] });

function serve(body: unknown = endpointOne([OVERPROVISIONED, THROTTLED, IDLE, SOLO, AUTO, LOCKED]), status = 200) {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if (method(init) !== 'GET') throw new Error(`unexpected write ${method(init)} ${url}`);
    return json(status, body);
  });
}

const page = () =>
  renderAt(POLICIES_HREF, <PoliciesPage clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} workloadsHref={WORKLOADS_HREF} />);

const section = (title: string) => screen.getByRole('heading', { name: title }).closest('section')! as HTMLElement;
const term = (scope: HTMLElement, name: string) =>
  [...scope.querySelectorAll('dt')].find((dt) => dt.textContent === name)!.nextElementSibling as HTMLElement;

describe('Workload autoscaler › Policies (UI v3 plan §2)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    serve();
  });
  afterEach(() => vi.restoreAllMocks());

  it('is registered right after Workloads, in the Workload autoscaler group', () => {
    const automation = EKS_SECTIONS.find((s) => s.id === 'automation')!;
    const ids = automation.subs.map((s) => s.id);
    expect(ids.indexOf('workload-autoscaler-policies')).toBe(ids.indexOf('workload-autoscaler') + 1);
    expect(automation.subs.find((s) => s.id === 'workload-autoscaler-policies')).toEqual({
      id: 'workload-autoscaler-policies', label: 'Policies', group: 'Workload autoscaler',
    });
  });

  it('says what the page is, reads endpoint 1 once and sends nothing', async () => {
    page();
    await screen.findByRole('heading', { name: 'Rules for each workload' });
    expect(screen.getByText(POLICIES_HEADER)).toBeInTheDocument();
    expect(POLICIES_HEADER).toBe("These are the rules XamOps uses today. Changing them here isn't available yet; the mode switch is on each workload.");
    expect(mockedFetch).toHaveBeenCalledTimes(1);
    expect(method(mockedFetch.mock.calls[0][1])).toBe('GET');
    // Nothing on the page can change a rule: no form control at all.
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('opens with the Now sentence from endpoint 1', async () => {
    page();
    await screen.findByRole('heading', { name: 'Rules for each workload' });
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      '5 of 6 workloads use Suggest only. 1 has its own saved rules. 1 is set to Apply automatically. 3 are left alone by a rule below.',
    );
  });

  it('states the defaults in plain words, from the engine, with how many workloads use each', async () => {
    page();
    const defaults = await waitFor(() => section('What most workloads use'));
    expect(defaults).toHaveTextContent('From the engine');
    expect(term(defaults, 'Mode')).toHaveTextContent('Suggest only5 of 6 workloads');
    expect(term(defaults, 'CPU target')).toHaveTextContent('95th percentile of usage5 of 6 workloads');
    expect(term(defaults, 'Headroom')).toHaveTextContent('25% above the target5 of 6 workloads');
    expect(term(defaults, 'Look-back window')).toHaveTextContent('24 hours5 of 6 workloads');
    expect(term(defaults, 'How changes are applied')).toHaveTextContent('In place, no restart6 of 6 workloads');
    expect(term(defaults, 'Settings source')).toHaveTextContent('XamOps defaults5 of 6 workloads');
  });

  it("lists each workload's rules with its mode chip and a link to its mode switch", async () => {
    page();
    await screen.findByRole('heading', { name: 'Rules for each workload' });
    const table = screen.getByRole('table', { name: 'Rules for each workload' });
    const auto = table.querySelector<HTMLElement>('[data-policy-row="team-a/Deployment/auto-api"]')!;
    expect(auto.querySelector('[data-tone="good"]')).toHaveTextContent('Apply automatically');
    expect(auto).toHaveTextContent('20% above the target');
    expect(auto).toHaveTextContent('90th percentile of usage');
    expect(auto).toHaveTextContent('3 days');
    expect(auto).toHaveTextContent('Saved for this workload');
    const link = within(auto).getByRole('link', { name: 'Open the mode switch for auto-api' });
    const href = new URL(link.getAttribute('href')!, 'http://test');
    expect(href.searchParams.get('sub')).toBe('workload-autoscaler');
    expect(href.searchParams.get('workload')).toBe('team-a/Deployment/auto-api');
    expect(table.querySelector('[data-policy-row="team-a/Deployment/overprovisioned-api"] [data-tone="good"]')).toHaveTextContent('Suggest only');
  });

  it('builds "Left alone" from the reasons present, in the glossary wording', async () => {
    page();
    const leftAlone = await waitFor(() => section('Left alone'));
    expect([...leftAlone.querySelectorAll('[data-left-alone]')].map((li) => li.getAttribute('data-left-alone'))).toEqual([
      'SINGLE_REPLICA', 'HPA_MANAGED', 'LOCKED',
    ]);
    const solo = leftAlone.querySelector('[data-left-alone="SINGLE_REPLICA"]')!;
    expect(solo).toHaveTextContent('Single replica');
    expect(solo).toHaveTextContent('1 workload: solo');
    expect(solo).toHaveTextContent('It runs a single replica, so a problem during a resize would take it down.');
    expect(leftAlone.querySelector('[data-left-alone="LOCKED"]')).toHaveTextContent('Paused');
    expect(leftAlone.querySelector('[data-left-alone="HPA_MANAGED"]')).toHaveTextContent('Scaled by an HPA');
  });

  it('marks the values the engine fixes as Fixed in XamOps', async () => {
    page();
    const fixed = await waitFor(() => section('Set by XamOps'));
    expect(term(fixed, 'Wait after a change')).toHaveTextContent('60 minutesFixed in XamOps');
    expect(term(fixed, 'Minimums')).toHaveTextContent('25m CPU · 250Mi memory');
    expect(term(fixed, 'Memory target')).toHaveTextContent('Peak usage');
  });

  it('shows the failure with Retry, and reads again only when asked', async () => {
    const user = userEvent.setup();
    serve({ code: 'K8S_CLUSTER_ACCESS_NOT_CONFIGURED', message: 'No access entry maps this role.' }, 403);
    page();
    const failure = await waitFor(() => {
      const el = document.querySelector<HTMLElement>('[data-read-failure]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(failure).toHaveTextContent("XamOps can't sign in to this cluster");
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read its rules just now.");
    expect(screen.queryByRole('heading', { name: 'Rules for each workload' })).toBeNull();
    serve();
    await user.click(within(failure).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Rules for each workload' })).toBeInTheDocument();
    expect(mockedFetch).toHaveBeenCalledTimes(2);
  });
});
