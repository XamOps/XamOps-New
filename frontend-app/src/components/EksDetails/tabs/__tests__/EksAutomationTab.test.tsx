import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import EksAutomationTab from '../EksAutomationTab';
import {
  adaptEngineResponse,
  DASH,
  formatInstant,
  manualApplyGate,
} from '../../../../api/verticalRightsizerAdapter';
import { formatIST } from '../../../../utils/timeUtils';
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

  it('disables Apply and states the blocking reason when the controller is not ready', async () => {
    resolveWith({ engine: engine(), gateReasons: ['CONTROLLER_NOT_READY'], workloads: [workload()] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Automation not enabled');
    // The reason is readable without hovering, not hidden in a tooltip only.
    expect(screen.getAllByText('Automation not enabled').length).toBeGreaterThan(0);
  });

  it('enables a one-time Apply under RECOMMEND although autoApplyEligible is false', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: false })] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeEnabled();
    // autoApplyEligible is shown for what it is, not used as the gate.
    expect(screen.getByText('Automatic apply off')).toBeInTheDocument();
    expect(screen.getByText('Mode RECOMMEND')).toBeInTheDocument();
  });

  it('shows the last cycle and the window in IST, with the raw UTC value in the tooltip', async () => {
    const lastCycle = '2026-10-12T07:23:19.015387285Z';
    resolveWith({
      engine: engine({ lastCycle: { at: lastCycle, evaluated: 9, error: null } }),
      gateReasons: [],
      workloads: [workload()],
    });
    renderTab();

    const cycle = await screen.findByTitle(lastCycle);
    // Nanoseconds are cut to milliseconds before parsing; 07:23 UTC is 12:53 IST.
    expect(cycle.textContent).toBe(`${formatIST('2026-10-12T07:23:19.015Z')} · 9`);
    expect(cycle.textContent).toMatch(/12:53 PM IST · 9$/);
    expect(screen.getByTitle('2026-10-10T02:00:00Z').textContent).toBe(formatIST('2026-10-10T02:00:00Z'));
    expect(screen.getByTitle('2026-10-11T02:00:00Z').textContent).toBe(formatIST('2026-10-11T02:00:00Z'));
    expect(screen.queryByText(/2026-10-1\dT/)).toBeNull();
  });

  it('shows an unparseable timestamp verbatim rather than guessing', () => {
    expect(formatInstant('not-a-time')).toBe('not-a-time');
    expect(formatInstant(null)).toBe(DASH);
  });

  it('greys Apply with the reason for a read-only connection', async () => {
    resolveWith({ engine: engine(), gateReasons: ['READ_ONLY_CONNECTION'], workloads: [workload()] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Read-only connection');
  });

  describe('manual apply gate', () => {
    const gateFor = (wire: VerticalEngineResponseWire) => {
      const view = adaptEngineResponse(wire);
      return manualApplyGate(view, view.workloads[0]);
    };
    const cases: { name: string; wire: VerticalEngineResponseWire; reason: string | null }[] = [
      {
        name: 'RECOMMEND + an actionable recommendation is enabled',
        wire: { engine: engine(), gateReasons: [], workloads: [workload()] },
        reason: null,
      },
      {
        name: 'autoApplyEligible false alone does not disable',
        wire: { engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: false })] },
        reason: null,
      },
      {
        name: 'autoApplyEligible true does not enable a row with nothing to apply',
        wire: {
          engine: engine(),
          gateReasons: [],
          workloads: [
            workload({
              autoApplyEligible: true,
              containers: [
                {
                  name: 'api',
                  qosClass: 'Guaranteed',
                  current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
                  recommended: null,
                  reasons: ['WITHIN_HYSTERESIS'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'No change is recommended.',
      },
      {
        name: 'a recommendation equal to current is nothing to apply',
        wire: {
          engine: engine(),
          gateReasons: [],
          workloads: [
            workload({
              containers: [
                {
                  name: 'api',
                  qosClass: 'Guaranteed',
                  current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
                  recommended: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
                  reasons: ['HPA_CPU_MANAGED'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'No change is recommended.',
      },
      {
        name: 'a read-only connection disables',
        wire: { engine: engine(), gateReasons: ['READ_ONLY_CONNECTION'], workloads: [workload()] },
        reason: 'Read-only connection',
      },
      {
        name: 'no permission (including a Login-As session) disables',
        wire: { engine: engine(), gateReasons: ['NO_USER_PERMISSION'], workloads: [workload()] },
        reason: 'No permission',
      },
      {
        name: 'demo disables, ahead of the connection',
        wire: { engine: engine(), gateReasons: ['READ_ONLY_CONNECTION', 'DEMO'], workloads: [workload()] },
        reason: 'Demo mode',
      },
      {
        name: 'a controller that is not ready disables',
        wire: { engine: engine({ ready: false }), gateReasons: [], workloads: [workload()] },
        reason: 'Automation not enabled',
      },
      {
        name: 'no execution path disables, with the path reason',
        wire: {
          engine: engine({
            executionPath: 'NONE',
            executionPathDetail: {
              direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
              agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
            },
          }),
          gateReasons: ['CLUSTER_ACCESS_NOT_CONFIGURED'],
          workloads: [workload()],
        },
        reason: 'Cluster access not configured',
      },
      {
        name: 'a workload in cooldown disables',
        wire: {
          engine: engine(),
          gateReasons: [],
          workloads: [
            workload({
              containers: [
                {
                  name: 'api',
                  qosClass: 'Guaranteed',
                  current: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
                  recommended: { cpuRequestM: 25, memRequestMi: 400, cpuLimitM: 25, memLimitMi: 400 },
                  reasons: ['COOLDOWN', 'OOM_RISK'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'Waiting out cooldown',
      },
      {
        name: 'a GitOps-managed workload disables',
        wire: { engine: engine(), gateReasons: [], workloads: [workload({ gitopsManaged: true })] },
        reason: 'GitOps managed',
      },
      {
        name: 'a skipped workload disables with its skip reason',
        wire: { engine: engine(), gateReasons: [], workloads: [workload({ skipReason: 'POLICY_OFF' })] },
        reason: 'Policy off',
      },
      {
        name: 'requests the engine would be inventing disable',
        wire: {
          engine: engine(),
          gateReasons: [],
          workloads: [
            workload({
              containers: [
                {
                  name: 'app',
                  qosClass: 'BestEffort',
                  current: { cpuRequestM: null, memRequestMi: null, cpuLimitM: null, memLimitMi: null },
                  recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: null, memLimitMi: null },
                  reasons: ['FLOOR_CLAMPED', 'REQUESTS_UNSET'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'Requests unset',
      },
    ];

    it.each(cases)('$name', ({ wire, reason }) => {
      const gate = gateFor(wire);
      expect(gate.reason).toBe(reason);
      expect(gate.enabled).toBe(reason === null);
    });

    it('builds the apply body as the diff of recommended against current, memory in MiB', () => {
      const gate = gateFor({
        engine: engine(),
        gateReasons: [],
        workloads: [
          workload({
            containers: [
              {
                name: 'php',
                qosClass: 'Burstable',
                current: { cpuRequestM: 200, memRequestMi: 512, cpuLimitM: 500, memLimitMi: 1024 },
                recommended: { cpuRequestM: 200, memRequestMi: 300, cpuLimitM: 500, memLimitMi: 600 },
                reasons: ['HPA_CPU_MANAGED'],
                upstreamVpa: null,
              },
            ],
          }),
        ],
      });
      expect(gate.enabled).toBe(true);
      expect(gate.changes).toEqual([{ name: 'php', memRequestMi: 300, memLimitMi: 600 }]);
    });
  });

  it('confirms a real apply with before and after per container, then submits it', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({ kind: 'writes-not-deployed' });
    renderTab();

    fireEvent.click(await screen.findByRole('checkbox'));
    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(mockedApply).not.toHaveBeenCalled();
    expect(dialog.textContent).toContain('CPU request 1000m → 25m');
    expect(dialog.textContent).toContain('Memory request 1024Mi → 250Mi');
    expect(dialog.textContent).toContain('CPU limit 1000m → 25m');
    expect(dialog.textContent).toContain('Memory limit 1024Mi → 250Mi');
    expect(dialog.textContent).toContain('resized in place. No restart is expected.');
    expect(dialog.textContent).toContain(
      'The Deployment spec is not changed, so a rollout or a rescheduled pod returns to the spec values.',
    );
    expect(dialog.textContent).toContain('A cooldown starts.');

    fireEvent.click(screen.getByRole('button', { name: 'Resize pods' }));
    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(1));
    const [, , , , request] = mockedApply.mock.calls[0];
    expect(request).toEqual({
      containers: [{ name: 'api', cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 }],
      applyMethod: 'IN_PLACE',
      dryRun: false,
    });
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('sends nothing when a real apply is cancelled at the confirmation', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    renderTab();

    fireEvent.click(await screen.findByRole('checkbox'));
    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(mockedApply).not.toHaveBeenCalled();
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

  it('reports a submission with no outcome as submitted, never as applied', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: true,
      result: null,
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    expect(await screen.findByText(/Submitted/)).toBeInTheDocument();
    expect(screen.getByText(/dry run/)).toBeInTheDocument();
    expect(screen.getByText(/The outcome arrives in the actions feed/)).toBeInTheDocument();
  });

  it('renders the restart-free proof for a confirmed apply', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: false,
      result: {
        method: 'IN_PLACE',
        terminal: 'APPLIED',
        detail: null,
        restartFree: true,
        containers: [
          {
            name: 'api',
            state: 'APPLIED',
            detail: null,
            requested: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
            restartExpected: false,
          },
        ],
        pods: [
          {
            podName: 'overprovisioned-api-5d9f-abcde',
            uidBefore: 'uid-1',
            uidAfter: 'uid-1',
            samePod: true,
            ready: true,
            containers: [
              {
                name: 'api',
                containerIdBefore: 'containerd://c1',
                containerIdAfter: 'containerd://c1',
                restartCountBefore: 0,
                restartCountAfter: 0,
                observedBefore: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
                observedAfter: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
              },
            ],
          },
        ],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Applied and confirmed in the pods')).toBeInTheDocument();
    expect(screen.getByText(/restart-free, proved per pod/)).toBeInTheDocument();
    expect(screen.getByText(/same pod/)).toBeInTheDocument();
    expect(screen.getByText(/same container/)).toBeInTheDocument();
    expect(screen.getByText(/restarts 0/)).toBeInTheDocument();
  });

  it('renders a Deferred outcome as waiting for capacity, never as a success', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: false,
      result: {
        method: 'IN_PLACE',
        terminal: 'DEFERRED',
        detail: 'The kubelet deferred the resize and will retry when there is capacity.',
        restartFree: false,
        containers: [
          { name: 'api', state: 'DEFERRED', detail: null, requested: null, restartExpected: false },
        ],
        pods: [],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Waiting for node capacity — not applied')).toBeInTheDocument();
    expect(screen.getByText(/restart-free not claimed/)).toBeInTheDocument();
    expect(screen.queryByText(/Applied and confirmed/)).toBeNull();
    expect(screen.queryByText(/^Applied$/)).toBeNull();
  });

  it('renders a dry run as a rehearsal, not as a change', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: true,
      result: {
        method: 'IN_PLACE',
        terminal: 'DRY_RUN',
        detail: 'Dry run: no patch was sent to the cluster, so nothing was applied.',
        restartFree: false,
        containers: [
          { name: 'api', state: 'DRY_RUN', detail: null, requested: null, restartExpected: false },
        ],
        pods: [],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Dry run — nothing was sent to the cluster')).toBeInTheDocument();
    expect(screen.queryByText(/Applied and confirmed/)).toBeNull();
  });

  it('renders an unknown outcome verbatim instead of guessing', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: false,
      result: {
        method: 'IN_PLACE',
        terminal: 'SOMETHING_NEW',
        detail: null,
        restartFree: false,
        containers: [],
        pods: [],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('The engine reported SOMETHING_NEW')).toBeInTheDocument();
  });

  it('explains a refused apply with the gate code the server sent', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: true })] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'READ_ONLY_CONNECTION', label: 'Read-only connection' },
      message: 'This AWS account is connected read-only.',
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText(/Refused/)).toBeInTheDocument();
    expect(screen.getByText(/Read-only connection/)).toBeInTheDocument();
  });
});
