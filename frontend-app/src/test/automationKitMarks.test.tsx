/**
 * Phase-1 dark mode, page level: every coloured element of every Automation page sits under a
 * `data-k8s-kit` root, so style.css's legacy `.dark` rules (which repaint buttons, white surfaces
 * and text with `!important`) never reach it, and the pages' own `dark:` variants decide what a
 * disabled, pressed or primary control looks like.
 *
 * darkKitExemption.test.ts proves the legacy selectors stop at a kit root; this proves the pages put
 * their roots where they need them. Each page is rendered for real, in demo mode, through the same
 * section host the cluster page uses. jsdom applies no stylesheet, so this reads the marks and the
 * classes; the light and dark screenshot pass measures the painted result.
 *
 * Two things stay outside a kit root on purpose:
 *  - main's Workload rightsizing cards (`EksAutomationTab`), hosted under "Change a workload" and
 *    "Change this workload": light-only, so the legacy CSS still paints them (owner-approved
 *    follow-up). Their write controls are marked one by one, which is checked here too;
 *  - the cluster page's access banner, shared with every other section.
 */
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveDemoResponse } from '../demo/demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from '../demo/demoConfig';
import { EksDashboardCacheProvider } from '../contexts/EksDashboardCacheContext';
import { K8sCapabilitiesProvider } from '../hooks/useK8sCapabilities';
import { useEksDashboard } from '../hooks/useEksDashboard';
import { useEksSectionRoute } from '../hooks/useEksSectionRoute';
import AutomationSection from '../components/EksDetails/sections/AutomationSection';

const CLUSTER = 'prod-eks-cluster';
const ACCOUNT = 'demo-aws-001';
const REGION = 'us-east-1';

/** A colour utility: light (`text-[…]`, `bg-white`, …) or its dark variant. */
const COLOUR_CLASS = /(?:^|\s)(?:dark:)?(?:text|bg|border|divide|accent|placeholder)-(?:\[|white\b|black\b)/;
const HOST_HEADING = /^Change (a|this) workload$/;

function Page() {
  const eksData = useEksDashboard({ clusterName: CLUSTER, accountId: ACCOUNT, region: REGION, version: '1.29' });
  const route = useEksSectionRoute();
  if (eksData.loading && !eksData.dashboard) return <p>Loading the cluster…</p>;
  return (
    <main className="dark" data-cloudk8s-theme="dark">
      <AutomationSection clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} eksData={eksData} route={route} onOpenGuide={() => {}} />
    </main>
  );
}

function renderSub(sub: string, extra = '') {
  return render(
    <MemoryRouter initialEntries={[`/eks-details?name=${CLUSTER}&accountIds=${ACCOUNT}&region=${REGION}&tab=automation&sub=${sub}${extra}`]}>
      <EksDashboardCacheProvider>
        <K8sCapabilitiesProvider accountId={ACCOUNT} provider="AWS">
          <Page />
        </K8sCapabilitiesProvider>
      </EksDashboardCacheProvider>
    </MemoryRouter>,
  );
}

/** Main's cards: everything under the v3 host section except the host's own heading. */
function mainCardHosts(): Element[] {
  return [...document.querySelectorAll('h3')].filter((h) => HOST_HEADING.test((h.textContent ?? '').trim())).map((h) => h.parentElement!);
}

const describeEl = (el: Element) => `<${el.tagName.toLowerCase()} class="${(el.getAttribute('class') ?? '').slice(0, 90)}"> ${(el.textContent ?? '').trim().slice(0, 50)}`;

/** Coloured elements the legacy dark CSS can still reach. */
function unmarked(): string[] {
  const hosts = mainCardHosts();
  const main = document.querySelector('main[data-cloudk8s-theme]')!;
  return [...main.querySelectorAll('*')]
    .filter((el) => COLOUR_CLASS.test(el.getAttribute('class') ?? ''))
    .filter((el) => !el.closest('[data-k8s-kit]'))
    .filter((el) => !el.closest('[data-testid="access-banner"]'))
    .filter((el) => !hosts.some((host) => host.contains(el) && !HOST_HEADING.test((el.textContent ?? '').trim())))
    .map(describeEl);
}

const realFetch = global.fetch;
beforeEach(() => {
  sessionStorage.setItem(DEMO_MODE_KEY, 'true');
  sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  // Every read is answered in-process by the demo router, as main.tsx does in the browser.
  global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const mock = resolveDemoResponse(url, init?.method ?? 'GET', typeof init?.body === 'string' ? init.body : undefined);
    if (mock) return mock;
    throw new Error(`unexpected live fetch in demo mode: ${url}`);
  }) as typeof fetch;
});
afterEach(() => {
  global.fetch = realFetch;
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('every Automation page keeps the legacy dark CSS off its v3 parts (data-k8s-kit)', () => {
  it.each([
    ['Overview', 'overview', '', 'What automation can do on this cluster today'],
    ['Workloads', 'workload-autoscaler', '', 'Workloads at a glance'],
    ['a workload detail', 'workload-autoscaler', '&workload=staging%2FDeployment%2Fapi-staging', 'Where it is now'],
    ['Policies', 'workload-autoscaler-policies', '', 'Rules for each workload'],
    ['Node autoscaler', 'node-autoscaler', '', 'Who changes the node count on this cluster'],
    ['Consolidation', 'consolidation', '', 'No consolidation plan yet'],
    // The demo records no Spot events (none are invented), so the page shows its honest empty state.
    ['Spot interruptions', 'spot-interruptions', '', 'No interruptions recorded in the last 30 days'],
    ['HPA', 'hpa', '', 'HPAs on this cluster'],
    ['an HPA detail', 'hpa', '&hpa=production%2Fworker-hpa', "XamOps's suggestion"],
    ['VPA', 'vpa', '', 'Kubernetes VPA coexistence'],
    // The demo serves the activity feed (FEAT-007), so Activity lists every recorded change.
    ['Activity', 'activity', '', 'Every change XamOps recorded'],
  ])('%s', async (_name, sub, extra, landmark) => {
    renderSub(sub, extra);
    expect((await screen.findAllByText(landmark, {}, { timeout: 5000 })).length).toBeGreaterThan(0);
    // Let the page's other reads (capabilities, node automation, endpoint 1) settle too.
    await waitFor(() => expect(document.querySelector('.skeleton, [aria-busy="true"]')).toBeNull());
    expect(unmarked()).toEqual([]);
  });

  it('marks main\'s write controls one by one: the mode switch, Apply, the test run and the confirmations', async () => {
    renderSub('workload-autoscaler');
    expect(await screen.findByText('Workloads at a glance', {}, { timeout: 5000 })).toBeInTheDocument();
    const host = mainCardHosts()[0];
    const segments = [...host.querySelectorAll('[role="group"][aria-label^="Automation mode for"] button[aria-pressed]')];
    const applies = [...host.querySelectorAll('button[aria-label^="Apply "]')];
    const testRun = host.querySelector('input[type="checkbox"]');
    expect(segments.length).toBeGreaterThan(0);
    expect(applies.length).toBeGreaterThan(0);
    expect(segments.filter((b) => !b.hasAttribute('data-k8s-kit')).map(describeEl)).toEqual([]);
    expect(applies.filter((b) => !b.hasAttribute('data-k8s-kit')).map(describeEl)).toEqual([]);
    expect(testRun?.hasAttribute('data-k8s-kit')).toBe(true);
    // Each marked control carries its own dark selected / disabled colours.
    for (const b of segments) expect(b.getAttribute('class')).toMatch(b.getAttribute('aria-pressed') === 'true' ? /dark:bg-\[rgba\(255,255,255,0\.92\)\]/ : /dark:disabled:text-/);
    for (const b of applies) expect(b.getAttribute('class')).toMatch(/dark:disabled:bg-transparent/);
  });
});
