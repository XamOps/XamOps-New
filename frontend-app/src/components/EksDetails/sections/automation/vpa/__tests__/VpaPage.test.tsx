import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import VpaPage from '../VpaPage';
import { adaptHpaPage } from '../../../../../../api/hpaAdapter';
import { fetchHpaPage } from '../../../../../../api/hpaApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';

vi.mock('../../../../../../api/hpaApi', () => ({
  fetchHpaPage: vi.fn(),
  applyHpaRecommendation: vi.fn(),
  hpaApplyUrl: vi.fn(),
}));

const mockedRead = fetchHpaPage as unknown as ReturnType<typeof vi.fn>;

const engine: VerticalEngineResponseWire['engine'] = {
  enabled: true,
  ready: true,
  clusterAccess: 'CONFIGURED',
  executionPath: 'DIRECT',
  executionPathDetail: { direct: { available: true, reason: null }, agent: { available: false, reason: null, lastSeenAt: null } },
  metricsSource: { reachable: true, name: 'prometheus-proxy' },
  lastCycle: { at: '2026-10-13T10:00:00Z', evaluated: 2, error: null },
};

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

const renderTab = () => render(<VpaPage clusterName="c1" accountId="123456789012" region="ap-south-1" />);

beforeEach(() => {
  vi.clearAllMocks();
});

// Moved verbatim from main's EksHpaTab.test.tsx ("EksHpaTab — VPA coexistence view") when the
// combined Pod autoscaler page was split; only the rendered component and the import paths changed.
describe('VpaPage — VPA coexistence view', () => {
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
