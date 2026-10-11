import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import EksAutomationTab from '../EksAutomationTab';
import { adaptEngineResponse, DASH } from '../../../../api/verticalRightsizerAdapter';
import { applyVerticalRecommendation, fetchVerticalEngine } from '../../../../api/verticalRightsizerApi';
import type {
  VerticalEngineResponseWire,
  VerticalWorkloadWire,
} from '../../../../types/verticalRightsizer';

vi.mock('../../../../api/verticalRightsizerApi', () => ({
  fetchVerticalEngine: vi.fn(),
  applyVerticalRecommendation: vi.fn(),
  verticalReadUrl: vi.fn(),
  verticalApplyUrl: vi.fn(),
}));

const mockedRead = fetchVerticalEngine as unknown as ReturnType<typeof vi.fn>;
const mockedApply = applyVerticalRecommendation as unknown as ReturnType<typeof vi.fn>;

const engine = (overrides: Partial<VerticalEngineResponseWire['engine']> = {}): VerticalEngineResponseWire['engine'] => ({
  enabled: true,
  ready: true,
  clusterAccess: 'CONFIGURED',
  executionPath: 'DIRECT',
  executionPathDetail: {
    direct: { available: true, reason: null },
    agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
  },
  metricsSource: { reachable: true, name: 'prometheus-proxy' },
  lastCycle: { at: '2026-10-11T10:00:00Z', evaluated: 1, error: null },
  ...overrides,
});

const workload = (overrides: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire => ({
  ref: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
  policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
  replicas: 2,
  hpa: { present: false, name: null },
  gitopsManaged: null,
  skipReason: null,
  findings: [],
  containers: [
    {
      name: 'api',
      qosClass: 'Guaranteed',
      current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
      recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
      reasons: ['FLOOR_CLAMPED'],
      upstreamVpa: { cpuRequestM: 30, memRequestMi: 250, source: 'vpa', observedAt: null },
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: {
    percentile: 95,
    windowHours: 24,
    windowStart: '2026-10-10T02:00:00Z',
    windowEnd: '2026-10-11T02:00:00Z',
    sampleCoverage: 0.94,
    usageSource: 'prometheus-proxy',
    policySource: 'DEFAULTS',
  },
  horizontal: null,
  ...overrides,
});

const resolveWith = (wire: VerticalEngineResponseWire) =>
  mockedRead.mockResolvedValue({ kind: 'ok', view: adaptEngineResponse(wire) });

const renderTab = () =>
  render(<EksAutomationTab clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" />);

describe('EksAutomationTab', () => {
  beforeEach(() => {
    mockedRead.mockReset();
    mockedApply.mockReset();
  });

  it('disables Apply and states the blocking reason when autoApplyEligible is false', async () => {
    resolveWith({ engine: engine(), gateReasons: ['CONTROLLER_NOT_READY'], workloads: [workload()] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Automation not enabled');
    // The reason is readable without hovering, not hidden in a tooltip only.
    expect(screen.getAllByText('Automation not enabled').length).toBeGreaterThan(0);
  });

  it('renders a dash for every unset resource field and never a 0', async () => {
    resolveWith({
      engine: engine(),
      gateReasons: [],
      workloads: [
        workload({
          ref: { namespace: 'dev-sim', kind: 'Deployment', name: 'no-requests-sidecar-app' },
          containers: [
            {
              name: 'app',
              qosClass: 'BestEffort',
              current: { cpuRequestM: null, memRequestMi: null, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: ['REQUESTS_UNSET'],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    const { container } = renderTab();

    await screen.findByText('no-requests-sidecar-app');
    const row = container.querySelector('tbody tr')!;
    const cells = Array.from(row.querySelectorAll('td')).map((td) => td.textContent ?? '');

    // Container, QoS, then four resource cells, then the VPA cell.
    expect(cells[2]).toBe(DASH);
    expect(cells[3]).toBe(DASH);
    expect(cells[4]).toBe(DASH);
    expect(cells[5]).toBe(DASH);
    // upstreamVpa null renders two dashes, not 0.
    expect(cells[6]).toBe(`${DASH} / ${DASH}`);
    expect(row.textContent).not.toMatch(/\b0\b/);
  });

  it('reports an unusable 0 in the payload instead of drawing it as a number', async () => {
    resolveWith({
      engine: engine(),
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'api',
              qosClass: 'Burstable',
              current: { cpuRequestM: 0, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: [],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    renderTab();

    expect(await screen.findByText(/Unusable values in the payload/)).toBeInTheDocument();
    expect(screen.getByText(/cpuRequestM was 0/)).toBeInTheDocument();
  });

  it('renders the matching empty state, not a table of zeros, when nothing was evaluated', async () => {
    resolveWith({ engine: engine({ enabled: false, ready: false }), gateReasons: ['NO_METRICS_SOURCE'], workloads: [] });
    const { container } = renderTab();

    expect(await screen.findByText('No usage history source in this cluster')).toBeInTheDocument();
    expect(container.querySelector('table')).toBeNull();
  });

  it('explains an unregistered cluster as a setup step rather than a crash', async () => {
    mockedRead.mockResolvedValue({ kind: 'cluster-not-registered', message: 'Install the XamOps agent first.' });
    renderTab();

    expect(
      await screen.findByText('This cluster is not registered with XamOps in this environment'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Install the XamOps agent/)).toBeInTheDocument();
  });

  it('explains missing cluster access with its remedy', async () => {
    mockedRead.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'CLUSTER_ACCESS_NOT_CONFIGURED', label: 'Cluster access not configured' },
      message: 'No access entry.',
    });
    renderTab();

    expect(await screen.findByText('This cluster has not granted XamOps access')).toBeInTheDocument();
    expect(screen.getByText(/EKS access entry/)).toBeInTheDocument();
  });

  it('renders a not-measured-yet workload with no container rows and no numbers', async () => {
    resolveWith({
      engine: engine(),
      gateReasons: [],
      workloads: [
        workload({
          ref: { namespace: 'team-a', kind: 'Deployment', name: 'unschedulable-giant' },
          skipReason: 'INSUFFICIENT_HISTORY',
          containers: [],
          provenance: {
            percentile: 95,
            windowHours: 24,
            windowStart: null,
            windowEnd: null,
            sampleCoverage: null,
            usageSource: 'prometheus-proxy',
            policySource: 'DEFAULTS',
          },
        }),
      ],
    });
    const { container } = renderTab();

    await screen.findByText('unschedulable-giant');
    expect(screen.getByText(/Not measured yet/)).toBeInTheDocument();
    expect(container.querySelector('table')).toBeNull();
    expect(screen.getByText(`Coverage ${DASH}`)).toBeInTheDocument();
  });

  it('submits the apply and reports a 404 as "not deployed yet"', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({ kind: 'writes-not-deployed' });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(1));
    const [, ref, , , request] = mockedApply.mock.calls[0];
    expect(ref).toEqual({ namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' });
    expect(request).toEqual({
      containers: [{ name: 'api', cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 }],
      applyMethod: 'IN_PLACE',
      dryRun: true,
    });

    expect(await screen.findByText(/Apply is not deployed in this environment yet/)).toBeInTheDocument();
    expect(screen.queryByText(/Applied/)).toBeNull();
  });

  it('reports a submission as submitted, never as applied', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: true,
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    expect(await screen.findByText(/Submitted/)).toBeInTheDocument();
    expect(screen.getByText(/dry run/)).toBeInTheDocument();
  });
});
