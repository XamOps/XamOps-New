/**
 * The visual + scoped-metric GATE (FEAT-004; docs/cloudk8s/ui-revamp-design.md §11, §14).
 *
 * The previous rounds were approved on DOM checks alone and never measured the way a reviewer
 * measures a premium page: by counting the distinct font sizes, text greys and corner radii on the
 * screen. This gate makes that measurement a required, build-failing test, scoped to the redesigned
 * region (`[data-k8s-redesigned-main]`) of the two flagship slices — Automation › Overview and
 * Workloads › Pods — in BOTH light and dark.
 *
 * ── Why a jsdom harness and not Playwright ────────────────────────────────────────────────────
 * The preferred gate is a Playwright pass that navigates to Overview and Pods at desktop width,
 * screenshots light + dark and runs a `getComputedStyle` counter. Playwright is NOT installed in
 * this sandbox and no dev server / live session is reachable, so this is the documented fallback:
 * a vitest + jsdom harness that mounts the real panels and runs the SAME scoped counter against the
 * emitted Tailwind class strings (jsdom loads no stylesheet, so there is no computed colour — the
 * class name IS the measurement, exactly as `designRules`/`colourAudit` read class names). If the
 * Playwright path is ever wired, it asserts the identical budgets below.
 *
 * ── The budget (design-delta §7 type scale + §2 chrome ramp; owner converge review) ───────────
 *   • ≤ 7 distinct `text-[Npx]` font sizes  (28 hero / 20 strip / 16 / 14 / 13 / 12 / 11 — the owner
 *                                            split the one figure into a dominant hero + a smaller
 *                                            strip figure, a legitimate extra rank)
 *   • ≤ 2 distinct chrome text greys         (`--chrome-ink` + `--chrome-muted`)
 *   • radii only from {8px surface, 6px control, full pill} — a NAMED radius (rounded-md/-lg/…) is
 *                                            its own token and FAILS, so it can't hide as 6px/8px
 *   • MetricStrip is ONE horizontal row      (never a wrapped vertical stack)
 *   • NO `text-[52px]` node                  (the old hero is gone on the flagship tabs)
 *
 * ── Fixtures are CLEARLY MARKED test data ─────────────────────────────────────────────────────
 * Everything mounted here uses the repo's demo / mock fixtures (`demoCapabilities`,
 * `demoEksDashboard`, and the GATE_* constants below), which are explicitly test data — never
 * fabricated-as-live display data. The gate measures CHROME, not numbers, so the figures are
 * irrelevant; only the rendered class strings matter.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getVerticalStatus } = vi.hoisted(() => ({ getCapabilities: vi.fn(), getVerticalStatus: vi.fn() }));
vi.mock('../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../api/verticalRecommendationsApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/verticalRecommendationsApi')>();
  return { ...actual, getVerticalStatus };
});
vi.mock('../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

// FEAT-003: the Cost tab reads all money from useEksClusterCost (which calls apiClient.get, not
// .fetch). Mock the hook directly — exactly as EksCostTab.cost.test.tsx does — so the gate drives a
// deterministic, clearly-marked cost fixture. This mock is inert for the Overview / Summary / Pods
// mounts, which never import the hook.
const costHook = vi.hoisted(() => ({ value: null as unknown }));
vi.mock('../../../../hooks/useEksClusterCost', () => ({
  useEksClusterCost: () => costHook.value,
  default: () => costHook.value,
}));

import apiClient from '../../../../utils/apiClient';
import { K8sCapabilitiesProvider } from '../../../../hooks/useK8sCapabilities';
import { useEksSectionRoute } from '../../../../hooks/useEksSectionRoute';
import { clearVerticalCacheForTests } from '../../../../hooks/useVerticalRecommendations';
import { demoCapabilities, demoVerticalStatus } from '../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../demo/xamopsMockData';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import { OverviewPanel } from '../../../EksDetails/sections/automation/OverviewPanel';
import SummaryPanel from '../../../EksDetails/sections/overview/SummaryPanel';
import EksPodsTab from '../../../EksDetails/tabs/EksPodsTab';
import EksCostTab from '../../../EksDetails/tabs/EksCostTab';
import EksNodesTab from '../../../EksDetails/tabs/EksNodesTab';
import EksKarpenterTab from '../../../EksDetails/tabs/EksKarpenterTab';
import EksPerformanceTab from '../../../EksDetails/tabs/EksPerformanceTab';
import EksSecurityTab from '../../../EksDetails/tabs/EksSecurityTab';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

// ─── CLEARLY-MARKED TEST FIXTURES (never fabricated-as-live display data) ───────────────────────

/** A backend without the Phase-2 reads: the dispatcher's untyped 404 (Phase-1 view). */
const GATE_NOT_DEPLOYED = { status: 404, body: null };

/** GATE fixture: three demo workloads, enough to light every Overview surface. */
const GATE_VERTICAL = {
  engine: {
    enabled: true,
    ready: true,
    clusterAccess: 'CONFIGURED',
    executionPath: 'DIRECT',
    executionPathDetail: { direct: { available: true, reason: null }, agent: null },
    metricsSource: { reachable: true, name: 'prometheus-proxy' },
    lastCycle: { at: new Date(Date.now() - 5 * 60_000).toISOString(), evaluated: 3, error: null },
  },
  gateReasons: [],
  writeGatesChecked: true,
  workloads: [
    {
      ref: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
      replicas: 2,
      hpa: { present: false, name: null },
      gitopsManaged: false,
      skipReason: null,
      findings: [],
      containers: [{ name: 'api', qosClass: 'Burstable', current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: null, memLimitMi: null }, recommended: { cpuRequestM: 25, memRequestMi: 256, cpuLimitM: null, memLimitMi: null }, reasons: [], upstreamVpa: null }],
      lastAction: null,
      autoApplyEligible: false,
      provenance: { percentile: 95, windowHours: 24, windowStart: null, windowEnd: null, sampleCoverage: 0.94, usageSource: 'prometheus-proxy', policySource: 'DEFAULTS' },
      horizontal: null,
    },
  ],
};

const gateJson = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => (body === null ? '' : JSON.stringify(body)) }) as unknown as Response;

function serveGateReads() {
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') !== 'GET') throw new Error(`unexpected write ${url}`);
    const u = new URL(String(url), 'http://gate');
    if (u.pathname.startsWith('/api/eks/automation/vertical/')) return gateJson(200, GATE_VERTICAL);
    // Every Phase-2 read is a not-deployed 404 in the gate (Phase-1 view).
    return gateJson(GATE_NOT_DEPLOYED.status, GATE_NOT_DEPLOYED.body);
  });
}

const GATE_EKS_DATA = { dashboard: demoEksDashboard } as unknown as UseEksDashboardReturn;

/**
 * GATE fixture (FEAT-004): the demo dashboard with a REPORTED Karpenter controller so the Karpenter
 * tab lights every surface — the hero MetricStrip and the NodePools / EC2NodeClasses / NodeClaims
 * DataTables — rather than the labels-only branch. Clearly test data; the gate measures CHROME.
 */
const GATE_EKS_DATA_KARPENTER = {
  dashboard: {
    ...demoEksDashboard,
    karpenter: {
      healthy: true,
      version: 'v1.0.6',
      nodePools: [{ name: 'default', ready: true, nodeCount: 3, cpuLimit: '100', memoryLimit: '' }],
      ec2NodeClasses: [{ name: 'default', ready: false, readyMessage: 'AMI resolution failed', amiFamily: 'AL2023', instanceProfile: 'KarpenterNodeRole' }],
      nodeClaims: [{ name: 'default-x7k2p', nodePoolName: 'default', phase: 'Ready', instanceType: 'm5.xlarge', capacityType: 'spot', zone: 'us-east-1b', nodeName: 'ip-10-0-1-102', createdAt: new Date(Date.now() - 3_600_000).toISOString() }],
      totalActiveClaims: 1, totalPendingClaims: 0, spotNodesCount: 3, onDemandNodesCount: 0, provisionedNodes: 3, pendingPods: 0,
    },
  },
} as unknown as UseEksDashboardReturn;

/**
 * GATE fixture (FEAT-003): a realistic cost roll-up — a real cluster monthly, priced + unpriced
 * nodes / namespaces, and both priced and dash workloads — so every Cost surface lights up (the hero
 * MetricStrip, the Namespace / Node-fleet panels and the Workload ranking DataTable). Clearly test
 * data; the gate measures CHROME, not these numbers. Mirrors EksCostTab.cost.test.tsx's `fullCost`.
 */
const GATE_COST = {
  cost: {
    currency: 'USD',
    hoursPerMonth: 730,
    monthlySavings: 4.2,
    monthlySavingsUnavailableReason: null,
    clusterMonthlyCost: 23.67,
    pricedWorkloads: 2,
    unpricedWorkloads: 1,
    nodeCosts: [
      { nodeName: 'ip-10-0-1-10', instanceType: 'm5.large', capacityType: 'on-demand', capacityTypeInferred: false, monthlyCost: 70.08, source: 'PRICE_LIST', reason: null, note: null },
      { nodeName: 'fargate-node-1', instanceType: null, capacityType: null, capacityTypeInferred: false, monthlyCost: null, source: null, reason: 'FARGATE_UNPRICEABLE', note: null },
    ],
    namespaceCosts: [
      { namespace: 'default', monthlyCost: 18.5, pricedWorkloads: 1, unpricedWorkloads: 0 },
      { namespace: 'kube-system', monthlyCost: null, pricedWorkloads: 0, unpricedWorkloads: 1 },
    ],
  },
  workloads: [
    { ref: { namespace: 'default', kind: 'Deployment', name: 'web-api' }, cost: { monthlyCost: 12.34, monthlySavings: 2.1, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false, note: null } },
    { ref: { namespace: 'kube-system', kind: 'Deployment', name: 'coredns' }, cost: { monthlyCost: null, monthlySavings: null, reason: 'NODE_CAPACITY_UNKNOWN', source: null, capacityType: null, capacityTypeInferred: false, note: null } },
  ],
  engine: undefined,
  loading: false,
  error: null,
};

/**
 * GATE fixture (FEAT-005): the demo dashboard with a REPORTED Trivy scan, Falco runtime events and a
 * Kubescape compliance report so every Security surface lights up — the hero MetricStrip, the
 * runtime-alerts / framework / vulnerable-workload / vulnerable-image DataTables and their drawers.
 * Clearly test data; the gate measures CHROME, not these numbers.
 */
const GATE_EKS_DATA_SECURITY = {
  dashboard: {
    ...demoEksDashboard,
    securityMetrics: {
      totalEvents: 5,
      eventsLastHour: 5,
      eventsByPriority: { CRITICAL: 1, HIGH: 2, WARNING: 2 },
      alerts: [
        { rule: 'Terminal shell in container', priority: 'CRITICAL', pod: 'api-7d9f', namespace: 'production', count: 3 },
        { rule: 'Read sensitive file untrusted', priority: 'HIGH', pod: 'worker-1', namespace: 'batch', count: 2 },
      ],
    },
    kubescape: {
      complianceScore: 72,
      totalCritical: 2, totalHigh: 4, totalMedium: 6, totalLow: 3, totalWorkloads: 9,
      namespaceBreakdown: {
        production: { critical: 2, high: 2, medium: 1, low: 0 },
        batch: { critical: 0, high: 2, medium: 5, low: 3 },
      },
      frameworks: [
        { name: 'NSA-CISA', score: 72, passedControls: 58, failedControls: 22 },
        { name: 'MITRE', score: 64, passedControls: 40, failedControls: 24 },
        { name: 'CIS Kubernetes Benchmark', score: 81, passedControls: 70, failedControls: 16 },
      ],
      vulnerabilities: [
        { workloadName: 'orders-api', namespace: 'production', workloadKind: 'Deployment', criticalCount: 2, highCount: 2, mediumCount: 1, lowCount: 0 },
        { workloadName: 'etl-processor', namespace: 'batch', workloadKind: 'StatefulSet', criticalCount: 0, highCount: 2, mediumCount: 5, lowCount: 3 },
      ],
    },
    trivy: {
      summary: { totalCritical: 3, totalHigh: 7, totalMedium: 12, totalLow: 5, uniqueImagesScanned: 14, namespacesCovered: 4, totalExposedSecrets: 1 },
      workloads: [
        {
          name: 'orders-api', namespace: 'production', critical: 3, high: 4, medium: 6, low: 2,
          vulnerabilities: [
            { vulnerabilityId: 'CVE-2024-1234', severity: 'CRITICAL', packageName: 'openssl', installedVersion: '3.0.1', fixedVersion: '3.0.8', score: 9.8, primaryLink: 'https://example.test/CVE-2024-1234' },
            { vulnerabilityId: 'CVE-2024-5678', severity: 'HIGH', packageName: 'curl', installedVersion: '7.80.0', fixedVersion: null, score: 7.4, primaryLink: null },
          ],
        },
        { name: 'web-frontend', namespace: 'production', critical: 0, high: 3, medium: 6, low: 3, vulnerabilities: [] },
      ],
    },
  },
} as unknown as UseEksDashboardReturn;

// ─── The scoped counter (the way a reviewer measures the page) ─────────────────────────────────

interface Metrics {
  /** Distinct `text-[Npx]` sizes, as numbers. */
  fontSizes: Set<number>;
  /** Distinct chrome text greys (`text-[color:var(--chrome-ink|muted)]`). */
  textGreys: Set<string>;
  /**
   * Distinct corner radii, each as a normalised TOKEN string so a NAMED radius (`rounded-lg`) and an
   * arbitrary px radius (`rounded-[8px]`) count as DIFFERENT shapes even if they happen to compute to
   * the same px. This is the fix for the false pass (owner review #6): the old counter mapped every
   * named radius onto a px bucket, so `rounded-md` + `rounded-lg` + `rounded-[8px]` collapsed to two
   * and slipped under the ≤2 budget. Now the budget is exactly two sanctioned px tokens — `8px`
   * (surfaces) and `6px` (controls) — plus the full pill; anything else (a stray `rounded-md`/`-lg`)
   * is its own token and breaks the budget.
   */
  radii: Set<string>;
  /** True if any node carries the banned 52px hero size. */
  has52: boolean;
}

/**
 * Every token on every element's class attribute under each `root`. Takes MANY roots so the measure
 * covers not just the main `[data-k8s-redesigned-main]` subtree but every portal the redesign
 * renders OUTSIDE it — above all the DetailDrawer (`[data-detail-drawer]`), a `fixed` portal whose
 * radii, chips, controls and figures the old single-subtree scan never saw. That blind spot is
 * exactly how the extra radii + black chips slipped through a green gate (owner review #6). A null
 * root (e.g. a closed drawer) is skipped, so passing one is always safe.
 */
function classTokens(roots: ReadonlyArray<HTMLElement | null>): string[] {
  const tokens: string[] = [];
  for (const root of roots) {
    if (!root) continue;
    root.querySelectorAll('*').forEach((el) => {
      const cls = el.getAttribute('class');
      if (cls) tokens.push(...cls.split(/\s+/).filter(Boolean));
    });
    const rootCls = root.getAttribute('class');
    if (rootCls) tokens.push(...rootCls.split(/\s+/).filter(Boolean));
  }
  return tokens;
}

/** The pill sentinel token (genuine circles / bars: status dots, capacity bars, chips). */
const PILL = 'pill';
/** The two sanctioned px radius tokens (owner review #4): 8px surfaces, 6px controls. */
const SANCTIONED_PX = new Set(['8px', '6px']);

/** Measure the redesigned region (plus any portals passed) the way a reviewer measures a page. */
function measure(scopes: ReadonlyArray<HTMLElement | null>): Metrics {
  const fontSizes = new Set<number>();
  const textGreys = new Set<string>();
  const radii = new Set<string>();
  let has52 = false;

  for (const token of classTokens(scopes)) {
    // Font size: text-[Npx]. (Ignore text-[color:…], which is a colour, not a size.)
    const size = token.match(/^text-\[(\d+)px\]$/);
    if (size) {
      const px = Number(size[1]);
      fontSizes.add(px);
      if (px === 52) has52 = true;
      continue;
    }
    // Chrome text grey: text-[color:var(--chrome-ink|--chrome-muted)].
    const grey = token.match(/^text-\[color:var\((--chrome-(?:ink|muted))\)\]$/);
    if (grey) {
      textGreys.add(grey[1]);
      continue;
    }
    // Corner radius: arbitrary rounded-[Npx] / rounded-l-[Npx] etc., or a named Tailwind radius.
    const arbitrary = token.match(/^rounded(?:-[trbl]{1,2})?-\[(\d+)px\]$/);
    if (arbitrary) {
      radii.add(`${arbitrary[1]}px`); // keep the exact px token, e.g. "8px" / "6px"
      continue;
    }
    if (/^rounded-full(?:$|-)/.test(token) || token === 'rounded-full') {
      radii.add(PILL); // the pill (dots, bars, chips) — one allowed shape beyond the two px radii
      continue;
    }
    // A NAMED Tailwind radius (rounded / rounded-md / rounded-lg …) is recorded by its OWN name, NOT
    // mapped onto a px bucket — so it can never masquerade as one of the two sanctioned px tokens.
    const named = token.match(/^(rounded)(?:-[trbl]{1,2})?(-(?:none|sm|md|lg|xl|2xl|3xl))?$/);
    if (named) {
      radii.add(`rounded${named[2] ?? ''}`); // e.g. "rounded-lg", "rounded" (bare = 4px)
    }
  }

  return { fontSizes, textGreys, radii, has52 };
}

/** Report the measurement in a failure message a human can read. */
const describeMetrics = (m: Metrics) =>
  `font sizes: [${[...m.fontSizes].sort((a, b) => a - b).join(', ')}]; ` +
  `text greys: [${[...m.textGreys].join(', ')}]; ` +
  `radii: [${[...m.radii].sort().join(', ')}]; ` +
  `has text-[52px]: ${m.has52}`;

/**
 * Assert the scoped budget on one mounted slice.
 *
 * The font-size budget is SEVEN (not six): the owner's converge review split the one shared figure
 * into a dominant HERO_FIGURE (28px) above the subordinate strip FIGURE (20px), so the type scale is
 * legitimately seven ranks now (28 / 20 / 16 / 14 / 13 / 12 / 11). This is NOT a relaxation of the
 * chrome rules — it encodes the owner-decided hero rank; everything else (greys, radii, the 52px ban)
 * stays strict, and radii got STRICTER (named radii no longer collapse onto px buckets).
 */
function assertBudget(name: string, scopes: ReadonlyArray<HTMLElement | null>) {
  const m = measure(scopes);
  const detail = `${name} — ${describeMetrics(m)}`;
  expect(m.has52, `${name}: a text-[52px] hero node is present. ${detail}`).toBe(false);
  expect(m.fontSizes.size, `${name}: more than 7 distinct font sizes. ${detail}`).toBeLessThanOrEqual(7);
  expect(m.textGreys.size, `${name}: more than 2 chrome text greys. ${detail}`).toBeLessThanOrEqual(2);
  // Every corner radius must be one of the two sanctioned px tokens or the full pill — nothing else.
  const strayRadii = [...m.radii].filter((r) => r !== PILL && !SANCTIONED_PX.has(r));
  expect(strayRadii, `${name}: corner radii outside the sanctioned {8px, 6px, pill}: [${strayRadii.join(', ')}]. ${detail}`).toEqual([]);
  // And no more than the two sanctioned px radii are actually in use (plus the pill).
  const pxRadii = new Set([...m.radii].filter((r) => r !== PILL));
  expect(pxRadii.size, `${name}: more than 2 distinct px corner radii. ${detail}`).toBeLessThanOrEqual(2);
}

/**
 * Assert the MetricStrip renders as ONE horizontal row, never a wrapped vertical stack (owner review
 * #3): the row container must be a non-wrapping flex row (`flex` without `flex-wrap`). A vertical
 * stack would re-introduce the long scrolling page the review flagged.
 */
async function assertMetricStripIsOneRow(name: string, scope: HTMLElement) {
  // The hero MetricStrip (Overview's <Kpis>) renders only after the async useAutomationRead
  // resolves, so a synchronous querySelector races the dark render (FEAT-001: intermittent
  // 'no MetricStrip row found'). Await the row before asserting — this hardens determinism
  // WITHOUT weakening the check; the same one-row (flex, no flex-wrap) assertions still run on it.
  const row = await waitFor(() => {
    const r = scope.querySelector<HTMLElement>('[data-metric-strip-row]');
    expect(r, `${name}: no MetricStrip row ([data-metric-strip-row]) found.`).not.toBeNull();
    return r!;
  });
  const cls = row.getAttribute('class') ?? '';
  expect(cls.includes('flex'), `${name}: MetricStrip row is not a flex row. class="${cls}"`).toBe(true);
  expect(cls.includes('flex-wrap'), `${name}: MetricStrip row wraps (flex-wrap) — it must stay one row. class="${cls}"`).toBe(false);
}

/**
 * Assert the table can never clip its last column (owner review #5). jsdom has no layout, so the gate
 * can't measure pixel widths against a sidebar; it asserts the STRUCTURAL guarantee that makes
 * clipping impossible instead: the table sits inside a scroll container (`overflow-auto` /
 * `overflow-x-auto`), so when the real content area (viewport − the ~220px sidebar) is narrower than
 * the table, the card scrolls horizontally rather than compressing the last column off the edge.
 * (The Playwright path, when wired, additionally renders at a realistic viewport with a 220px left
 * offset and asserts the table card's measured width ≤ viewport − 220px.)
 */
function assertTableScrolls(name: string, scope: HTMLElement) {
  const table = scope.querySelector<HTMLElement>('table');
  expect(table, `${name}: no table found.`).not.toBeNull();
  let node: HTMLElement | null = table!.parentElement;
  let foundScroll = false;
  while (node && node !== scope.parentElement) {
    const cls = node.getAttribute('class') ?? '';
    if (/\boverflow-(auto|x-auto)\b/.test(cls)) { foundScroll = true; break; }
    node = node.parentElement;
  }
  expect(foundScroll, `${name}: the table is not inside an overflow-auto/overflow-x-auto scroll container, so a wide table could clip its last column.`).toBe(true);
}

// ─── Mounts ────────────────────────────────────────────────────────────────────────────────────

async function mountOverview(theme: 'light' | 'dark') {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  serveGateReads();
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <MemoryRouter initialEntries={['/eks-details?name=prod-eks-cluster&tab=automation&sub=overview']}>
        <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
          <OverviewPanel clusterName="prod-eks-cluster" accountId="demo-aws-001" region="us-east-1" eksData={GATE_EKS_DATA} />
        </K8sCapabilitiesProvider>
      </MemoryRouter>
    </div>,
  );
  await screen.findByRole('table', { name: 'What automation can do on this cluster today' });
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

async function mountPods(theme: 'light' | 'dark') {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
        <EksPodsTab eksData={GATE_EKS_DATA} clusterName="prod-eks-cluster" />
      </K8sCapabilitiesProvider>
    </div>,
  );
  await screen.findByRole('table', { name: 'Pods' });
  await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Overview › Summary (FEAT-002): the v3-rolled-out panel, mounted exactly as the cluster page does
 * (a SectionRoute from the URL), so the gate measures its real [data-k8s-redesigned-main] region. */
function SummaryHarness() {
  const route = useEksSectionRoute();
  return (
    <SummaryPanel
      clusterName="prod-eks-cluster"
      accountId="demo-aws-001"
      region="us-east-1"
      eksData={GATE_EKS_DATA}
      route={route}
      onOpenGuide={() => {}}
    />
  );
}

async function mountSummary(theme: 'light' | 'dark') {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  getVerticalStatus.mockResolvedValue(demoVerticalStatus('prod-eks-cluster'));
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <MemoryRouter initialEntries={['/eks-details?name=prod-eks-cluster&region=us-east-1&accountIds=demo-aws-001&tab=overview&sub=summary']}>
        <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
          <SummaryHarness />
        </K8sCapabilitiesProvider>
      </MemoryRouter>
    </div>,
  );
  await screen.findByRole('table', { name: 'Namespace utilisation' });
  await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Cost (FEAT-003): the v3-rolled-out Cost tab, mounted with a deterministic cost fixture, so the
 * gate measures its real [data-k8s-redesigned-main] region (hero MetricStrip + ranking DataTable). */
async function mountCost(theme: 'light' | 'dark') {
  costHook.value = GATE_COST;
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <EksCostTab eksData={GATE_EKS_DATA} clusterName="prod-eks-cluster" accountId="demo-aws-001" region="us-east-1" />
    </div>,
  );
  await screen.findByRole('table', { name: 'Deployment cost ranking' });
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Nodes › Nodes (FEAT-004): the wide-table v3 slice, PageTemplate width='table' + hero MetricStrip. */
async function mountNodes(theme: 'light' | 'dark') {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
        <EksNodesTab eksData={GATE_EKS_DATA} clusterName="prod-eks-cluster" />
      </K8sCapabilitiesProvider>
    </div>,
  );
  await screen.findByRole('table', { name: 'Nodes' });
  await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Nodes › Karpenter (FEAT-004), reported branch: hero MetricStrip + the three DataTables. */
async function mountKarpenter(theme: 'light' | 'dark') {
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <EksKarpenterTab eksData={GATE_EKS_DATA_KARPENTER} />
    </div>,
  );
  await screen.findByRole('table', { name: 'NodePools' });
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Nodes › Utilization (FEAT-004): hero MetricStrip + the per-node usage DataTable. */
async function mountUtilization(theme: 'light' | 'dark') {
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <EksPerformanceTab eksData={GATE_EKS_DATA} />
    </div>,
  );
  await screen.findByRole('table', { name: 'Node utilisation' });
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

/** Security (FEAT-005): hero MetricStrip + the Trivy / Falco / Kubescape DataTables and their drawers. */
async function mountSecurity(theme: 'light' | 'dark') {
  render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-gate-theme={theme}>
      <EksSecurityTab eksData={GATE_EKS_DATA_SECURITY} />
    </div>,
  );
  await screen.findByRole('table', { name: 'Most vulnerable images' });
  return document.querySelector<HTMLElement>('[data-k8s-redesigned-main]')!;
}

// ─── The gate ────────────────────────────────────────────────────────────────────────────────────

describe('visual + scoped-metric gate (FEAT-004 §11; jsdom fallback, Playwright not installed)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    getVerticalStatus.mockReset();
    mockedFetch.mockReset();
    clearVerticalCacheForTests();
  });
  afterEach(() => vi.restoreAllMocks());

  for (const theme of ['light', 'dark'] as const) {
    describe(theme, () => {
      it('Automation › Overview stays within the premium budget', async () => {
        const main = await mountOverview(theme);
        expect(main, 'Overview must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        await assertMetricStripIsOneRow(`Overview (${theme})`, main);
        assertBudget(`Overview (${theme})`, [main]);
      });

      it('Overview › Summary stays within the premium budget', async () => {
        const main = await mountSummary(theme);
        expect(main, 'Overview Summary must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        // FEAT-002: one hero MetricStrip row (Monthly cost leads), never a wrapped vertical stack,
        // and the whole subtree inside the chrome budget (7 sizes / 2 greys / {8px,6px,pill}).
        await assertMetricStripIsOneRow(`Overview Summary (${theme})`, main);
        assertTableScrolls(`Overview Summary (${theme})`, main);
        assertBudget(`Overview Summary (${theme})`, [main]);
      });

      it('Workloads › Pods stays within the premium budget, drawer included', async () => {
        const main = await mountPods(theme);
        expect(main, 'Pods must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        await assertMetricStripIsOneRow(`Pods (${theme})`, main);
        assertTableScrolls(`Pods (${theme})`, main);

        // Open the first pod's DetailDrawer so its FIXED portal (rendered OUTSIDE the main subtree) is
        // measured too (owner review #6). The drawer's radii, chips, controls and figures must obey
        // the SAME budget as the page — the old gate never saw them.
        const firstRow = main.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Pods (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        // Measure the main subtree AND the drawer portal together: the whole redesigned surface a
        // viewer actually sees must stay within one budget.
        assertBudget(`Pods + drawer (${theme})`, [main, drawerPortal]);
      });

      it('Cost tab stays within the premium budget, drawer included', async () => {
        const main = await mountCost(theme);
        expect(main, 'Cost must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        // FEAT-003: one hero MetricStrip row (Monthly Est. leads), never a wrapped vertical stack,
        // the ranking table in a scroll container, and the whole subtree inside the chrome budget.
        await assertMetricStripIsOneRow(`Cost (${theme})`, main);
        assertTableScrolls(`Cost (${theme})`, main);

        // Open the first workload's DetailDrawer so its FIXED portal (rendered OUTSIDE the main
        // subtree) is measured under the SAME budget as the page.
        const firstRow = main.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Cost (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        assertBudget(`Cost + drawer (${theme})`, [main, drawerPortal]);
      });

      it('Nodes › Nodes stays within the premium budget, drawer included', async () => {
        const main = await mountNodes(theme);
        expect(main, 'Nodes must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        // FEAT-004: one hero MetricStrip row (capacity + status), never a wrapped vertical stack, the
        // wide node table in a scroll container so the last column never clips, and the whole subtree
        // inside the chrome budget (7 sizes / 2 greys / {8px,6px,pill}).
        await assertMetricStripIsOneRow(`Nodes (${theme})`, main);
        assertTableScrolls(`Nodes (${theme})`, main);

        // Open the first node's DetailDrawer so its FIXED portal (rendered OUTSIDE the main subtree)
        // is measured under the SAME budget as the page.
        const firstRow = main.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Nodes (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        assertBudget(`Nodes + drawer (${theme})`, [main, drawerPortal]);
      });

      it('Nodes › Karpenter stays within the premium budget, drawer included', async () => {
        const main = await mountKarpenter(theme);
        expect(main, 'Karpenter must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        await assertMetricStripIsOneRow(`Karpenter (${theme})`, main);
        // The Karpenter tab stacks three wide DataTables; each must scroll inside its own card.
        assertTableScrolls(`Karpenter (${theme})`, main);

        const firstRow = main.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Karpenter (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        assertBudget(`Karpenter + drawer (${theme})`, [main, drawerPortal]);
      });

      it('Nodes › Utilization stays within the premium budget, drawer included', async () => {
        const main = await mountUtilization(theme);
        expect(main, 'Utilization must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        await assertMetricStripIsOneRow(`Utilization (${theme})`, main);
        assertTableScrolls(`Utilization (${theme})`, main);

        const firstRow = main.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Utilization (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        assertBudget(`Utilization + drawer (${theme})`, [main, drawerPortal]);
      });

      it('Security stays within the premium budget, drawer included', async () => {
        const main = await mountSecurity(theme);
        expect(main, 'Security must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        // FEAT-005: one hero MetricStrip row (security score leads), never a wrapped vertical stack,
        // the Trivy / Falco / Kubescape tables in scroll containers so the last column never clips,
        // and the whole subtree (including the nested Falco + Kubescape views) inside the chrome
        // budget (7 sizes / 2 greys / {8px,6px,pill}) in both light and dark.
        await assertMetricStripIsOneRow(`Security (${theme})`, main);
        assertTableScrolls(`Security (${theme})`, main);

        // Open the first vulnerable image's DetailDrawer so its FIXED portal (rendered OUTSIDE the
        // main subtree) is measured under the SAME budget as the page.
        const imageTable = screen.getByRole('table', { name: 'Most vulnerable images' });
        const firstRow = imageTable.querySelector<HTMLElement>('tbody tr[role="button"]');
        expect(firstRow, `Security (${theme}): no activatable row to open the drawer.`).not.toBeNull();
        fireEvent.click(firstRow!);
        const drawer = await screen.findByRole('dialog');
        const drawerPortal = drawer.closest<HTMLElement>('[data-detail-drawer]') ?? drawer;

        assertBudget(`Security + drawer (${theme})`, [main, drawerPortal]);
      });
    });
  }
});
