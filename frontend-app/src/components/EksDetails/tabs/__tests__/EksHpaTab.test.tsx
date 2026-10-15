import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import EksHpaTab from '../EksHpaTab';
import { adaptHpaPage } from '../../../../api/hpaAdapter';
import { fetchHpaPage, applyHpaRecommendation } from '../../../../api/hpaApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../types/verticalRightsizer';

vi.mock('../../../../api/hpaApi', () => ({
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

const offVpa = (name: string): VerticalWorkloadWire => ({
  ref: { namespace: 'prod-sim', kind: 'Deployment', name },
  policy: null,
  replicas: 2,
  hpa: { present: false, name: null },
  gitopsManaged: null,
  skipReason: null,
  findings: [],
  containers: [
    {
      name: 'app',
      qosClass: 'Burstable',
      current: { cpuRequestM: 100, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
      recommended: null,
      reasons: [],
      upstreamVpa: { cpuRequestM: 78, memRequestMi: 250, source: 'vpa', observedAt: null },
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: null,
  horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: [] },
});

const resolveWith = (wire: VerticalEngineResponseWire) =>
  mockedRead.mockResolvedValue({ kind: 'ok', view: adaptHpaPage({ writeGatesChecked: true, ...wire }) });

const renderTab = () => render(<EksHpaTab clusterName="c1" accountId="123456789012" region="ap-south-1" />);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('EksHpaTab — HPA decision + Apply gating', () => {
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

describe('EksHpaTab — VPA coexistence view', () => {
  it('shows 9 Off-mode VPAs side-by-side with no conflict and no Apply', async () => {
    const names = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    resolveWith({ engine, gateReasons: [], workloads: names.map(offVpa) });
    renderTab();
    expect(await screen.findByText(/Kubernetes VPA coexistence/i)).toBeInTheDocument();
    const offCells = await screen.findAllByText(/Off doesn't change pods/i);
    expect(offCells).toHaveLength(9);
    // No HPA Apply button appears for VPA-only workloads (none have an HPA / recommend-new).
    expect(screen.queryByRole('button', { name: /Apply/i })).not.toBeInTheDocument();
  });

  it('renders a native VPA target as a dash when a field is unset (never a fabricated 0)', async () => {
    const w = offVpa('x');
    w.containers![0].upstreamVpa = { cpuRequestM: 78, memRequestMi: null, source: 'vpa', observedAt: null };
    resolveWith({ engine, gateReasons: [], workloads: [w] });
    renderTab();
    expect(await screen.findByText(/78m CPU \/ — memory/)).toBeInTheDocument();
  });
});
