import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cleanup, render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HpaPage, { MALFORMED_HPA_NOTICE, missingHpaNotice } from '../HpaPage';
import { currentSearch, renderAt } from '../../workload/__tests__/fixtures';
import type { UseEksDashboardReturn } from '../../../../../../hooks/useEksDashboard';
import { adaptHpaPage } from '../../../../../../api/hpaAdapter';
import { fetchHpaPage, applyHpaRecommendation } from '../../../../../../api/hpaApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';

vi.mock('../../../../../../api/hpaApi', () => ({
  fetchHpaPage: vi.fn(),
  applyHpaRecommendation: vi.fn(),
  hpaApplyUrl: vi.fn(),
}));

const mockedRead = fetchHpaPage as unknown as ReturnType<typeof vi.fn>;
const mockedApply = applyHpaRecommendation as unknown as ReturnType<typeof vi.fn>;

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
const renderTab = () => render(<MemoryRouter><HpaPage clusterName="c1" accountId="123456789012" region="ap-south-1" /></MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
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
    expect(apply).toBeEnabled();
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
  renderAt(url, <HpaPage clusterName="c1" accountId="123456789012" region="ap-south-1" eksData={eksData ?? undefined} />);
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
    expect([...kpis.querySelectorAll('[data-kpi-tone]')].map((k) => k.textContent)).toEqual([
      expect.stringMatching(/^HPAs1php-apache/),
      expect.stringMatching(/^At their maximum1php-apache/),
      expect.stringMatching(/^At their minimum0/),
      expect.stringMatching(/^Conflicts with XamOps0/),
    ]);
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent(
      'php-apache is at its maximum of 5 replicas, with CPU at 64% against a 50% target, so it would add pods if it could.',
    );
    // The decision and its gated Apply are main's, once per HPA, under its row.
    expect(screen.getByRole('button', { name: /Apply php-apache/i })).toBeEnabled();
    expect(document.querySelector('[data-hpa-row-decision="team-b/php-apache"] [data-hpa-panel]')).not.toBeNull();
  });

  it('shows a live value the agent did not report as a dash naming the agent, never 0', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage(HPA_PAGE, null);
    const row = await rowOf('team-b/php-apache');
    expect(within(row).getAllByText('from the XamOps agent').length).toBeGreaterThanOrEqual(4);
    expect(row.querySelector('svg[data-range-bar]')).toBeNull();
    const kpis = document.querySelector<HTMLElement>('[data-hpa-kpis]')!;
    expect(kpis.querySelectorAll('[data-kpi-tone]')[1]).toHaveTextContent('At their maximum—from the XamOps agent');
  });

  it('counts no HPAs it never checked: a dash and "Not checked" when neither the agent nor endpoint 1 answered', async () => {
    mockedRead.mockResolvedValue({ kind: 'refused', code: { kind: 'known', code: 'CLUSTER_ACCESS_NOT_CONFIGURED', label: 'x' }, message: 'No access entry.' });
    hpaPage(HPA_PAGE, null);
    expect(await screen.findByText("XamOps can't sign in to this cluster")).toBeInTheDocument();
    const cards = [...document.querySelectorAll<HTMLElement>('[data-hpa-kpis] [data-kpi-tone]')];
    expect(cards[0]).toHaveTextContent('HPAs—Not checked: neither the XamOps agent nor the Workload autoscaler answered');
    expect(cards[0].getAttribute('data-kpi-tone')).toBe('off');
    expect(cards[0]).not.toHaveTextContent(/None on this cluster|0/);
    expect(cards.map((c) => c.getAttribute('data-kpi-tone'))).toEqual(['off', 'off', 'off', 'off']);
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
    fireEvent.click(screen.getByRole('button', { name: /Apply php-apache/i }));
    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(1));
    expect(mockedApply.mock.calls[0][4]).toEqual({ maxReplicas: 8, dryRun: true });
    expect(await screen.findByText(/Test run passed/i)).toBeInTheDocument();
  });

  it('links each HPA to its detail with ?hpa=<namespace>/<name>, and keeps the history charts honest', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [phpApache()] });
    hpaPage();
    await rowOf('team-b/php-apache');
    const href = new URL(screen.getByRole('link', { name: 'Details for php-apache' }).getAttribute('href')!, 'http://test');
    expect(href.searchParams.get('hpa')).toBe('team-b/php-apache');
    expect(href.searchParams.get('sub')).toBe('hpa');
    const pending = [...document.querySelectorAll('[data-empty="history-pending"]')];
    expect(pending).toHaveLength(2);
    for (const p of pending) expect(p).toHaveTextContent('kube-state-metrics');
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
    expect(within(detail).getByRole('button', { name: /Apply php-apache/i })).toBeEnabled();
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
