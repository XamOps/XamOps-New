import type { ReactElement } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cleanup, render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HpaPage, { MALFORMED_HPA_NOTICE, missingHpaNotice } from '../HpaPage';
import { currentSearch, renderAt } from '../../workload/__tests__/fixtures';
import type { UseEksDashboardReturn } from '../../../../../../hooks/useEksDashboard';
import { adaptHpaPage } from '../../../../../../api/hpaAdapter';
import { fetchHpaPage, applyHpaRecommendation, fetchHpaHistory, fetchHpaHistories } from '../../../../../../api/hpaApi';
import { adaptHpaHistory } from '../../../../../../api/automationInsightsAdapter';
import type { HpaHistoryResponseWire } from '../../../../../../types/automationInsights';
import { K8sCapabilitiesProvider } from '../../../../../../hooks/useK8sCapabilities';
import { READINESS_KEYS, type CapabilitiesDto } from '../../../../../../types/k8sAutomation';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';

vi.mock('../../../../../../api/hpaApi', () => ({
  fetchHpaPage: vi.fn(),
  applyHpaRecommendation: vi.fn(),
  hpaApplyUrl: vi.fn(),
  // FEAT-004's history reads (one HPA, and the list's one read for several); each test starts with a
  // backend that doesn't serve them (beforeEach).
  fetchHpaHistory: vi.fn(),
  fetchHpaHistories: vi.fn(),
}));

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

const ACCOUNT = '123456789012';
const CLUSTER = 'c1';

/** A read-write, hpa-apply-ready capabilities fixture: can('hpa.apply') is allowed (FEAT-001/002). */
function readyCapabilities(): CapabilitiesDto {
  const connection = { accessLevel: 'READ_WRITE', status: 'CONNECTED' } as const;
  const writeGate = { allowed: true, reasons: [] as string[] };
  return {
    schemaVersion: 1,
    accountId: ACCOUNT,
    generatedAt: new Date().toISOString(),
    connection,
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate,
    readiness: Object.fromEntries(
      READINESS_KEYS.map((key) => [key, key === 'hpa-apply'
        ? { ready: true, reason: null, message: null }
        : { ready: false, reason: 'CONTROLLER_NOT_READY', message: 'off' }]),
    ),
    verticalEngine: { enabled: true },
    clusters: [
      {
        clusterName: CLUSTER,
        registered: true,
        connection,
        writeGate,
        dataSourceMode: 'agent',
        agent: { status: 'CONNECTED', lastDataAt: new Date().toISOString(), version: 'v2.1.0' },
        automationAgent: { status: 'CONNECTED', lastSeenAt: new Date().toISOString(), version: '1.0.0-autoscaler' },
        nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'OFF' },
        dataSources: [],
      },
    ],
  };
}

const withCaps = (element: ReactElement) => (
  <K8sCapabilitiesProvider accountId={ACCOUNT}>{element}</K8sCapabilitiesProvider>
);

const mockedRead = fetchHpaPage as unknown as ReturnType<typeof vi.fn>;
const mockedApply = applyHpaRecommendation as unknown as ReturnType<typeof vi.fn>;
const mockedHistory = fetchHpaHistory as unknown as ReturnType<typeof vi.fn>;
const mockedHistories = fetchHpaHistories as unknown as ReturnType<typeof vi.fn>;

const engine: VerticalEngineResponseWire['engine'] = {
  enabled: true,
  ready: true,
  clusterAccess: 'CONFIGURED',
  executionPath: 'DIRECT',
  executionPathDetail: { direct: { available: true, reason: null }, agent: { available: false, reason: null, lastSeenAt: null } },
  metricsSource: { reachable: true, name: 'prometheus-proxy' },
  lastCycle: { at: '2026-10-13T10:00:00Z', evaluated: 2, error: null },
};

const phpApache = (overrides: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire => ({
  ref: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' },
  policy: null,
  replicas: 5,
  hpa: { present: true, name: 'php-apache' },
  gitopsManaged: null,
  skipReason: null,
  findings: [],
  containers: [
    {
      name: 'php-apache',
      qosClass: 'Burstable',
      current: { cpuRequestM: 200, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
      recommended: null,
      reasons: [],
      upstreamVpa: null,
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: null,
  horizontal: {
    state: 'CHANGE',
    minReplicas: null,
    maxReplicas: 8,
    targetCpuUtilizationPct: null,
    reasons: ['AT_MAX_UNMET_DEMAND'],
    formula: 'ceil(5 × 64% ÷ 50%) = 7, capped to 8',
    autoApplyEligible: false,
  } as VerticalWorkloadWire['horizontal'],
  ...overrides,
});

const resolveWith = (wire: VerticalEngineResponseWire) =>
  mockedRead.mockResolvedValue({ kind: 'ok', view: adaptHpaPage({ writeGatesChecked: true, ...wire }) });

// The page reads `?hpa=` (its detail), so it renders under a router; nothing else about it changed.
const renderTab = () => render(<MemoryRouter>{withCaps(<HpaPage clusterName="c1" accountId="123456789012" region="ap-south-1" />)}</MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  mockedHistory.mockImplementation(async () => ({ kind: 'not-deployed' }));
  mockedHistories.mockImplementation(async (_c: string, hpas: Array<{ namespace: string; name: string }>) =>
    Object.fromEntries(hpas.map((h) => [`${h.namespace}/${h.name}`, { kind: 'not-deployed' }])));
  getCapabilities.mockResolvedValue(readyCapabilities());
});

// Moved verbatim from main's EksHpaTab.test.tsx ("EksHpaTab — HPA decision + Apply gating") when the
// combined Pod autoscaler page was split; only the rendered component and the import paths changed.
describe('HpaPage — HPA decision + Apply gating', () => {
  it('renders the decision as plain language with the formula and an enabled Apply', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    renderTab();
    expect(await screen.findByText(/raising the maximum to 8/i)).toBeInTheDocument();
    expect(screen.getByText(/ceil\(5 × 64% ÷ 50%\)/)).toBeInTheDocument();
    const apply = screen.getByRole('button', { name: /Apply php-apache/i });
    await waitFor(() => expect(apply).toBeEnabled());
    // No raw code is visible as text.
    expect(screen.queryByText('AT_MAX_UNMET_DEMAND')).not.toBeInTheDocument();
  });

  it('greys Apply with the reason when the server did not check the write gates', async () => {
    mockedRead.mockResolvedValue({ kind: 'ok', view: adaptHpaPage({ engine, gateReasons: [], workloads: [phpApache()] }) });
    renderTab();
    const apply = await screen.findByRole('button', { name: /Apply php-apache/i });
    expect(apply).toBeDisabled();
  });

  it('greys Apply for a GitOps-managed HPA with a plain reason', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache({ gitopsManaged: true })] });
    renderTab();
    const apply = await screen.findByRole('button', { name: /Apply php-apache/i });
    expect(apply).toBeDisabled();
    expect(screen.getByText(/Argo CD or Flux/i)).toBeInTheDocument();
  });

  it('submits a test-run apply (dry run default) through the hook', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedApply.mockResolvedValue({ kind: 'submitted', correlationId: 'cid', state: 'ACCEPTED', dryRun: true, applied: { minReplicas: null, maxReplicas: 8, targetUtilizationPct: null, dryRun: true } });
    renderTab();
    const apply = await screen.findByRole('button', { name: /Apply php-apache/i });
    await waitFor(() => expect(apply).toBeEnabled());
    fireEvent.click(apply);
    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(1));
    expect(mockedApply.mock.calls[0][4]).toEqual({ maxReplicas: 8, dryRun: true });
    expect(await screen.findByText(/Test run passed/i)).toBeInTheDocument();
  });

  it('shows a gate-keyed empty state when HPA automation is off', async () => {
    resolveWith({ engine: { ...engine, enabled: false, ready: false }, gateReasons: ['CONTROLLER_NOT_READY'], workloads: [] });
    renderTab();
    expect(await screen.findByText(/HPA automation is off in this environment/i)).toBeInTheDocument();
  });
});

// ─── UI v3 (FEAT-003): the HPA list joined with the XamOps agent, and the ?hpa= detail ─────────

/** php-apache as the XamOps agent reports it (HPADetail): pinned at its maximum of 5, CPU 64% of a 50% target. */
const PHP_AGENT = {
  name: 'php-apache', namespace: 'team-b', targetKind: 'Deployment', targetName: 'php-apache',
  minReplicas: 1, maxReplicas: 5, currentReplicas: 5, desiredReplicas: 5, cpuTargetPct: 50, currentCpuPct: 64,
  conditions: [
    { type: 'AbleToScale', status: 'True', reason: 'ReadyForNewScale' },
    { type: 'ScalingActive', status: 'True', reason: 'ValidMetricFound' },
    { type: 'ScalingLimited', status: 'True', reason: 'TooManyReplicas', message: 'the desired replica count is more than the maximum replica count' },
  ],
};

const agentData = (hpas: unknown[] = [PHP_AGENT]) => ({ dashboard: { k8sHpas: hpas }, loading: false }) as unknown as UseEksDashboardReturn;
const HPA_PAGE = '/eks-details?name=c1&accountIds=123456789012&region=ap-south-1&tab=automation&sub=hpa';
/** `eksData: null` renders the page without the XamOps agent's data. */
const hpaPage = (url = HPA_PAGE, eksData: UseEksDashboardReturn | null = agentData()) =>
  renderAt(url, withCaps(<HpaPage clusterName="c1" accountId="123456789012" region="ap-south-1" eksData={eksData ?? undefined} />));
const rowOf = async (id: string) =>
  waitFor(() => {
    const row = document.querySelector<HTMLElement>(`[data-hpa-row="${id}"]`);
    expect(row).not.toBeNull();
    return row!;
  });

describe('HpaPage — HPAs on this cluster (UI v3 plan §2 "HPA")', () => {
  it("joins the agent's live HPA with XamOps's decision: replicas now vs min–max, CPU now vs target, the first flag", async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage();
    const row = await rowOf('team-b/php-apache');
    expect(row).toHaveTextContent('Deployment php-apache');
    expect(row).toHaveTextContent('target 50% of request');
    expect(row).toHaveTextContent('64%');
    expect(row).toHaveTextContent('5 of 1–5');
    expect(within(row).getByRole('img', { name: 'now 5 of 1–5, XamOps suggests 1–8' })).toBeInTheDocument();
    expect(row.querySelector('[data-tone="warn"]')).toHaveTextContent('At its maximum');
    expect(row).toHaveTextContent('Works with XamOps');
    const kpis = document.querySelector<HTMLElement>('[data-hpa-kpis]')!;
    expect([...kpis.querySelectorAll('[data-metric-cell]')].map((k) => k.textContent)).toEqual([
      expect.stringMatching(/^HPAs1php-apache/),
      expect.stringMatching(/^At their maximum1php-apache/),
      expect.stringMatching(/^At their minimum0/),
      expect.stringMatching(/^Conflicts with XamOps0/),
    ]);
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      'php-apache is at its maximum of 5 replicas, with CPU at 64% against a 50% target, so it would add pods if it could.',
    );
    // The decision and its gated Apply are main's, once per HPA, under its row.
    await waitFor(() => expect(screen.getByRole('button', { name: /Apply php-apache/i })).toBeEnabled());
    expect(document.querySelector('[data-hpa-row-decision="team-b/php-apache"] [data-hpa-panel]')).not.toBeNull();
  });

  it('shows a live value the agent did not report as a dash naming the agent, never 0', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage(HPA_PAGE, null);
    const row = await rowOf('team-b/php-apache');
    expect(within(row).getAllByText('from the XamOps agent').length).toBeGreaterThanOrEqual(4);
    expect(row.querySelector('svg[data-range-bar]')).toBeNull();
    const kpis = document.querySelector<HTMLElement>('[data-hpa-kpis]')!;
    expect(kpis.querySelectorAll('[data-metric-cell]')[1]).toHaveTextContent('At their maximum—from the XamOps agent');
  });

  it('counts no HPAs it never checked: a dash and "Not checked" when neither the agent nor endpoint 1 answered', async () => {
    mockedRead.mockResolvedValue({ kind: 'refused', code: { kind: 'known', code: 'CLUSTER_ACCESS_NOT_CONFIGURED', label: 'x' }, message: 'No access entry.' });
    hpaPage(HPA_PAGE, null);
    expect(await screen.findByText("XamOps can't sign in to this cluster")).toBeInTheDocument();
    const cards = [...document.querySelectorAll<HTMLElement>('[data-hpa-kpis] [data-metric-cell]')];
    expect(cards[0]).toHaveTextContent('HPAs—Not checked: neither the XamOps agent nor the Workload autoscaler answered');
    expect(cards[0]).not.toHaveTextContent(/None on this cluster/);
    expect(cards).toHaveLength(4);
  });

  it('notes "HPA suggestions are off" only while the horizontal engine evaluated nothing', async () => {
    const off = phpApache({ horizontal: { state: 'NOT_EVALUATED', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: [] } });
    resolveWith({ engine, gateReasons: [], workloads: [off] });
    hpaPage();
    expect(await screen.findByText(/HPA suggestions are off in this environment: XamOps doesn't evaluate HPA settings here/)).toBeInTheDocument();
    cleanup();
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage();
    await rowOf('team-b/php-apache');
    expect(screen.queryByText(/HPA suggestions are off in this environment: XamOps/)).toBeNull();
  });

  it('submits a test run from the joined row through the same gate', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedApply.mockResolvedValue({ kind: 'submitted', correlationId: 'cid', state: 'ACCEPTED', dryRun: true, applied: { minReplicas: null, maxReplicas: 8, targetUtilizationPct: null, dryRun: true } });
    hpaPage();
    await rowOf('team-b/php-apache');
    await waitFor(() => expect(screen.getByRole('button', { name: /Apply php-apache/i })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /Apply php-apache/i }));
    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(1));
    expect(mockedApply.mock.calls[0][4]).toEqual({ maxReplicas: 8, dryRun: true });
    expect(await screen.findByText(/Test run passed/i)).toBeInTheDocument();
  });

  it('links each HPA to its detail with ?hpa=<namespace>/<name>, and draws no history a backend without it can\'t serve', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage();
    await rowOf('team-b/php-apache');
    const href = new URL(screen.getByRole('link', { name: 'Details for php-apache' }).getAttribute('href')!, 'http://test');
    expect(href.searchParams.get('hpa')).toBe('team-b/php-apache');
    expect(href.searchParams.get('sub')).toBe('hpa');
    // An untyped 404 (no history endpoint): the history cards and the "Last scaled" column aren't rendered.
    await waitFor(() => expect(mockedHistory).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByRole('columnheader', { name: 'Last scaled' })).toBeNull());
    expect(screen.queryByRole('heading', { name: 'Replicas and CPU use, together' })).toBeNull();
    expect(document.querySelector('[data-empty="history-pending"]')).toBeNull();
  });
});

// ─── Phase 2 (FEAT-006): the HPA history, from kube-state-metrics ─────────────

const H_START = Date.UTC(2026, 9, 14, 0, 0, 0) / 1000;
const STEP = 1800;
/** php-apache's history: 5 of 1–5 throughout, CPU 64% against 50%, at its maximum all but its first hour. */
function phpHistory(over: Partial<HpaHistoryResponseWire> = {}): HpaHistoryResponseWire {
  const times = Array.from({ length: 145 }, (_, i) => H_START + i * STEP);
  const series = (key: string, label: string, unit: string, value: (i: number) => number | null) => ({
    key, label, unit, points: times.map((t, i) => [t, value(i)] as [number, number | null]), query: `max(kube_horizontalpodautoscaler_${key}{namespace="team-b",horizontalpodautoscaler="php-apache"})`,
  });
  const iso = (sec: number) => new Date(sec * 1000).toISOString().replace('.000Z', 'Z');
  return {
    schemaVersion: 1, namespace: 'team-b', name: 'php-apache', window: '72h', stepSeconds: STEP,
    start: iso(times[0]), end: iso(times[times.length - 1]),
    source: { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' },
    series: [
      series('currentReplicas', 'Replicas running', 'replicas', (i) => (i < 2 ? 4 : 5)),
      series('minReplicas', 'Minimum', 'replicas', () => 1),
      series('maxReplicas', 'Maximum', 'replicas', () => 5),
      // One step nothing measured: a gap, never 0.
      series('cpuUtilization', 'CPU used', 'percent', (i) => (i === 10 ? null : 64)),
      series('cpuTarget', 'Target', 'percent', () => 50),
    ],
    atMax: {
      share: 0.9861, minutesAtMax: 4260, minutesMeasured: 4320,
      runs: [{ from: iso(times[2]), to: null }], runsTruncated: false, since: iso(times[2]), stepSeconds: 60,
      query: 'max(current) >= bool max(max)',
    },
    changes: [{ at: iso(times[2]), from: 4, to: 5 }],
    changesTruncated: false,
    firstSampleAt: iso(times[0]),
    unavailable: null,
    ...over,
  };
}
const historyOk = (wire: HpaHistoryResponseWire = phpHistory()) => ({ kind: 'ok', view: adaptHpaHistory(wire) });
const card = (title: string) => screen.getByRole('heading', { name: title }).closest('section') as HTMLElement;

describe('HpaPage — the HPA history (FEAT-004 → FEAT-006)', () => {
  it('reads each HPA once and draws replicas and CPU against the target, with the time at its maximum', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedHistory.mockImplementation(async () => historyOk());
    hpaPage();
    await rowOf('team-b/php-apache');
    await screen.findByRole('heading', { name: 'Replicas and CPU use, together' });
    expect(mockedHistory).toHaveBeenCalledTimes(1);
    expect(mockedHistory.mock.calls[0].slice(0, 5)).toEqual(['c1', { namespace: 'team-b', name: 'php-apache' }, '123456789012', 'ap-south-1', '72h']);

    const replicas = card('Replicas and CPU use, together');
    expect(replicas.querySelector('[data-chart-source]')).toHaveTextContent('kube_horizontalpodautoscaler_status_current_replicas');
    expect(replicas.querySelector('[data-chart-source]')).toHaveTextContent('step 30 min, 3 days.');
    const rows = [...replicas.querySelectorAll('tbody tr')];
    expect(rows).toHaveLength(145);
    expect([...rows[0].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['4', '1', '5', '64%', '50%']);
    // The step Prometheus didn't measure is a dash, never 0%.
    expect([...rows[10].querySelectorAll('td')].map((td) => td.textContent)).toEqual(['5', '1', '5', '—', '50%']);

    const strip = card('Time at its maximum, last 3 days');
    expect(strip).toHaveTextContent('98.61% of the last 3 days · below it for 1 h');
    expect(strip).toHaveTextContent('At its maximum: 71 h');
    expect(strip.querySelector('[data-time-strip]')).not.toBeNull();
    expect(strip.querySelector('[data-chart-source]')).toHaveTextContent('>= bool kube_horizontalpodautoscaler_spec_max_replicas, step 1 min, 3 days.');

    // The table's "Last scaled", from the same read.
    expect(screen.getByRole('columnheader', { name: 'Last scaled' })).toBeInTheDocument();
    expect(document.querySelector('[data-hpa-last-scaled="team-b/php-apache"]')).toHaveTextContent('1 change in 3 days');
  });

  it('reads the list\'s HPAs with one request, not one per HPA, and gives each row its own history', async () => {
    const worker = phpApache({ ref: { namespace: 'prod', kind: 'Deployment', name: 'worker' }, hpa: { present: true, name: 'worker-hpa' } });
    resolveWith({ engine, gateReasons: [], workloads: [phpApache(), worker] });
    mockedHistories.mockImplementation(async () => ({
      'team-b/php-apache': historyOk(),
      'prod/worker-hpa': historyOk(phpHistory({ namespace: 'prod', name: 'worker-hpa', changes: [] })),
    }));
    hpaPage();
    await rowOf('team-b/php-apache');
    await waitFor(() => expect(document.querySelector('[data-hpa-last-scaled="team-b/php-apache"]')).toHaveTextContent('1 change in 3 days'));
    expect(document.querySelector('[data-hpa-last-scaled="prod/worker-hpa"]')).toHaveTextContent('Not in 3 days');
    expect(mockedHistories).toHaveBeenCalledTimes(1);
    expect(mockedHistories.mock.calls[0].slice(0, 5)).toEqual(['c1',
      [{ namespace: 'prod', name: 'worker-hpa' }, { namespace: 'team-b', name: 'php-apache' }], '123456789012', 'ap-south-1', '72h']);
    // The one-HPA read is only ever the one the page started while the agent's HPA alone was known,
    // and it was dropped (aborted) for the list's read.
    for (const call of mockedHistory.mock.calls) expect((call[5] as AbortSignal).aborted).toBe(true);
  });

  it('flags an HPA that never scaled in the window', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedHistory.mockImplementation(async () => historyOk(phpHistory({ changes: [] })));
    hpaPage();
    const row = await rowOf('team-b/php-apache');
    await waitFor(() => expect(document.querySelector('[data-hpa-last-scaled="team-b/php-apache"]')).toHaveTextContent('Not in 3 days'));
    expect(row).toHaveTextContent('Never scales: no change in 3 days');
  });

  it('does not call an HPA held at its maximum the whole window one that never scales (live xamops-test-1)', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    const base = phpHistory();
    const pinned = phpHistory({
      changes: [],
      atMax: { ...base.atMax!, share: 1, minutesAtMax: 4321, minutesMeasured: 4321, runs: [{ from: base.start!, to: null }], since: base.start! },
    });
    mockedHistory.mockImplementation(async () => historyOk(pinned));
    hpaPage();
    const row = await rowOf('team-b/php-apache');
    await waitFor(() => expect(document.querySelector('[data-hpa-last-scaled="team-b/php-apache"]')).toHaveTextContent('held at its maximum the whole window'));
    expect(row).not.toHaveTextContent('Never scales');
  });

  it('says why in words when Prometheus has no data, and offers Retry when the read failed', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedHistory.mockImplementation(async () => historyOk(phpHistory({ series: [], atMax: null, unavailable: { code: 'METRICS_TIMEOUT', message: 'Prometheus timed out.' } })));
    hpaPage();
    await rowOf('team-b/php-apache');
    await waitFor(() => expect(card('Replicas and CPU use, together').querySelector('[data-empty="unavailable"]')).toHaveTextContent('Prometheus in the cluster took too long to answer.'));
    cleanup();

    mockedHistory.mockImplementation(async () => ({ kind: 'error', message: 'HTTP 502' }));
    hpaPage();
    await rowOf('team-b/php-apache');
    const failed = await waitFor(() => {
      const el = card('Time at its maximum, last 3 days').querySelector<HTMLElement>('[data-empty="error"]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(failed).toHaveTextContent('HTTP 502');
    mockedHistory.mockImplementation(async () => historyOk());
    fireEvent.click(within(failed).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(card('Time at its maximum, last 3 days')).toHaveTextContent('98.61% of the last 3 days'));
  });

  it('marks history shorter than the window as partial, with "Prometheus keeps … here"', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    const late = phpHistory();
    mockedHistory.mockImplementation(async () => historyOk({ ...late, firstSampleAt: new Date((H_START + 48 * STEP) * 1000).toISOString() }));
    hpaPage();
    await rowOf('team-b/php-apache');
    await waitFor(() => expect(card('Replicas and CPU use, together').querySelector('[data-chart-partial]')).toHaveTextContent('Prometheus keeps 2 days here.'));
  });

  it('shows the detail\'s history and every replica change', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    mockedHistory.mockImplementation(async () => historyOk());
    hpaPage(`${HPA_PAGE}&hpa=${encodeURIComponent('team-b/php-apache')}`);
    await waitFor(() => expect(document.querySelector('[data-hpa-detail="team-b/php-apache"]')).not.toBeNull());
    await screen.findByRole('heading', { name: 'Replicas and CPU use, together' });
    const changes = card('Replica changes, last 3 days');
    expect(changes).toHaveTextContent('4 → 5 replicas');
    expect(mockedHistory).toHaveBeenCalledTimes(1);
  });
});

describe('HpaDetail — ?hpa=<namespace>/<name>', () => {
  it('opens one HPA: tiles, the at-limit banner, conditions in plain words, works-with checks and the same Apply', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage(`${HPA_PAGE}&hpa=${encodeURIComponent('team-b/php-apache')}`);
    const detail = await waitFor(() => {
      const el = document.querySelector<HTMLElement>('[data-hpa-detail="team-b/php-apache"]');
      expect(el).not.toBeNull();
      return el!;
    });
    const tile = (label: string) => detail.querySelector<HTMLElement>(`[data-hpa-tile="${label}"]`)!;
    expect(tile('Replicas now')).toHaveTextContent('5');
    expect(tile('Minimum–maximum')).toHaveTextContent('1–5');
    expect(tile('Scales on')).toHaveTextContent('CPUtarget 50% of request');
    expect(tile('CPU now')).toHaveTextContent('64%');
    expect(tile('The HPA wants')).toHaveTextContent('about 7');
    expect(within(tile('The HPA wants')).getByRole('tooltip', { hidden: true })).toHaveTextContent('Its own formula: 5 × 64% ÷ 50%, rounded up.');
    expect(detail.querySelector('[data-hpa-at-limit]')).toHaveTextContent('it wants about 7, so it would add pods if it could.');
    expect(detail.querySelector('[data-hpa-condition="ScalingLimited"]')).toHaveTextContent('Held at a limit: yes, the maximum');
    expect(detail.querySelector('[data-works-check="cpu"]')).toHaveTextContent('Workload autoscaler leaves CPU alone');
    await waitFor(() => expect(within(detail).getByRole('button', { name: /Apply php-apache/i })).toBeEnabled());
    expect(screen.getByText(/raising the maximum to 8/i)).toBeInTheDocument();
  });

  it('shows "The HPA wants" only when the agent reported its CPU target, its reading and its replicas', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    const { currentCpuPct: _omit, ...noReading } = PHP_AGENT;
    hpaPage(`${HPA_PAGE}&hpa=${encodeURIComponent('team-b/php-apache')}`, agentData([noReading]));
    const detail = await waitFor(() => {
      const el = document.querySelector<HTMLElement>('[data-hpa-detail]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(detail.querySelector('[data-hpa-tile="CPU now"]')).toHaveTextContent('—');
    expect(detail.querySelector('[data-hpa-tile="The HPA wants"]')).toBeNull();
  });

  it('removes a malformed ?hpa= with replace and a notice', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    const router = hpaPage(`${HPA_PAGE}&hpa=${encodeURIComponent('Team-B/<x>')}`);
    expect(await screen.findByText(MALFORMED_HPA_NOTICE)).toBeInTheDocument();
    await waitFor(() => expect(currentSearch().has('hpa')).toBe(false));
    expect(router.state.historyAction).toBe('REPLACE');
    expect(currentSearch().get('sub')).toBe('hpa');
    await rowOf('team-b/php-apache');
  });

  it("keeps the list with a notice for an HPA that isn't on the cluster", async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage(`${HPA_PAGE}&hpa=${encodeURIComponent('team-b/gone')}`);
    expect(await screen.findByText(missingHpaNotice('team-b/gone'))).toBeInTheDocument();
    await rowOf('team-b/php-apache');
    expect(document.querySelector('[data-hpa-detail]')).toBeNull();
  });
});
