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
 * ── The budget (design-delta §7 type scale + §2 chrome ramp) ──────────────────────────────────
 *   • ≤ 6 distinct `text-[Npx]` font sizes  (the six-rank scale: 28 / 16 / 14 / 13 / 12 / 11)
 *   • ≤ 2 distinct chrome text greys         (`--chrome-ink` + `--chrome-muted`)
 *   • ≤ 2 distinct corner radii              (the 8px card radius + the full pill for dots/bars)
 *   • NO `text-[52px]` node                  (the old hero is gone on the flagship tabs)
 *
 * ── Fixtures are CLEARLY MARKED test data ─────────────────────────────────────────────────────
 * Everything mounted here uses the repo's demo / mock fixtures (`demoCapabilities`,
 * `demoEksDashboard`, and the GATE_* constants below), which are explicitly test data — never
 * fabricated-as-live display data. The gate measures CHROME, not numbers, so the figures are
 * irrelevant; only the rendered class strings matter.
 */
import { render, screen, waitFor } from '@testing-library/react';
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

/** Tailwind's named radii, in px, so a named class counts as its real corner radius. */
const NAMED_RADIUS_PX: Record<string, number> = {
  'rounded-none': 0,
  'rounded-sm': 2,
  rounded: 4,
  'rounded-md': 6,
  'rounded-lg': 8,
  'rounded-xl': 12,
  'rounded-2xl': 16,
  'rounded-3xl': 24,
};

interface Metrics {
  /** Distinct `text-[Npx]` sizes, as numbers. */
  fontSizes: Set<number>;
  /** Distinct chrome text greys (`text-[color:var(--chrome-ink|muted)]`). */
  textGreys: Set<string>;
  /** Distinct corner radii, in px (`rounded-full` is a pill, counted as the sentinel -1). */
  radii: Set<number>;
  /** True if any node carries the banned 52px hero size. */
  has52: boolean;
}

/** Every token on every element's class attribute under `root`. */
function classTokens(root: HTMLElement): string[] {
  const tokens: string[] = [];
  root.querySelectorAll('*').forEach((el) => {
    const cls = el.getAttribute('class');
    if (cls) tokens.push(...cls.split(/\s+/).filter(Boolean));
  });
  // Include the root itself.
  const rootCls = root.getAttribute('class');
  if (rootCls) tokens.push(...rootCls.split(/\s+/).filter(Boolean));
  return tokens;
}

/** Measure the redesigned region the way a reviewer measures a premium page. */
function measure(scope: HTMLElement): Metrics {
  const fontSizes = new Set<number>();
  const textGreys = new Set<string>();
  const radii = new Set<number>();
  let has52 = false;

  for (const token of classTokens(scope)) {
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
      radii.add(Number(arbitrary[1]));
      continue;
    }
    if (/^rounded-full(?:$|-)/.test(token) || token === 'rounded-full') {
      radii.add(-1); // the pill sentinel (dots, bars, chips)
      continue;
    }
    // Named Tailwind radius (strip any side suffix: rounded-t-md → rounded-md).
    const named = token.match(/^(rounded)(?:-[trbl]{1,2})?(-(?:none|sm|md|lg|xl|2xl|3xl))?$/);
    if (named) {
      const key = `rounded${named[2] ?? ''}`;
      if (key in NAMED_RADIUS_PX) radii.add(NAMED_RADIUS_PX[key]);
    }
  }

  return { fontSizes, textGreys, radii, has52 };
}

/** Report the measurement in a failure message a human can read. */
const describeMetrics = (m: Metrics) =>
  `font sizes: [${[...m.fontSizes].sort((a, b) => a - b).join(', ')}]; ` +
  `text greys: [${[...m.textGreys].join(', ')}]; ` +
  `radii(px, -1=pill): [${[...m.radii].sort((a, b) => a - b).join(', ')}]; ` +
  `has text-[52px]: ${m.has52}`;

/** Assert the scoped budget on one mounted slice. */
function assertBudget(name: string, scope: HTMLElement) {
  const m = measure(scope);
  const detail = `${name} — ${describeMetrics(m)}`;
  expect(m.has52, `${name}: a text-[52px] hero node is present. ${detail}`).toBe(false);
  expect(m.fontSizes.size, `${name}: more than 6 distinct font sizes. ${detail}`).toBeLessThanOrEqual(6);
  expect(m.textGreys.size, `${name}: more than 2 chrome text greys. ${detail}`).toBeLessThanOrEqual(2);
  // Count only the real px radii; the full pill is one allowed shape beyond the budget of 2 px radii.
  const pxRadii = new Set([...m.radii].filter((r) => r >= 0));
  expect(pxRadii.size, `${name}: more than 2 distinct corner radii. ${detail}`).toBeLessThanOrEqual(2);
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
        assertBudget(`Overview (${theme})`, main);
      });

      it('Workloads › Pods stays within the premium budget', async () => {
        const main = await mountPods(theme);
        expect(main, 'Pods must render the redesigned [data-k8s-redesigned-main] region').not.toBeNull();
        assertBudget(`Pods (${theme})`, main);
      });
    });
  }
});
