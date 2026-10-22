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

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import { K8sCapabilitiesProvider } from '../../../../hooks/useK8sCapabilities';
import { demoCapabilities } from '../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../demo/xamopsMockData';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import { OverviewPanel } from '../../../EksDetails/sections/automation/OverviewPanel';
import EksPodsTab from '../../../EksDetails/tabs/EksPodsTab';

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

// ─── The gate ────────────────────────────────────────────────────────────────────────────────────

describe('visual + scoped-metric gate (FEAT-004 §11; jsdom fallback, Playwright not installed)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    mockedFetch.mockReset();
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
    });
  }
});
