import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import EksAutomationTab from '../EksAutomationTab';
import {
  adaptEngineResponse,
  DASH,
  formatInstant,
  manualApplyGate,
  reasonSentence,
  workloadStatus,
} from '../../../../api/verticalRightsizerAdapter';
import { applyVerticalRecommendation, fetchVerticalEngine } from '../../../../api/verticalRightsizerApi';
import { formatIST } from '../../../../utils/timeUtils';
import type {
  VerticalEngineResponseWire,
  VerticalResourcesWire,
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

/** Every fixture here is a current backend, which states that it checked the caller's write gates. */
const resolveWith = (wire: VerticalEngineResponseWire) =>
  mockedRead.mockResolvedValue({ kind: 'ok', view: adaptEngineResponse({ writeGatesChecked: true, ...wire }) });

const renderTab = () =>
  render(<EksAutomationTab clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" />);

/** Glossary 7.1, the reason shown at the control for a read-only connection. */
const READ_ONLY_SENTENCE =
  'View only: this AWS account is connected read-only. Applying changes needs read-write access.';

const container = (current: VerticalResourcesWire, recommended: VerticalResourcesWire | null, reasons: string[]) => ({
  name: 'c',
  qosClass: 'Burstable',
  current,
  recommended,
  reasons,
  upstreamVpa: null,
});

describe('EksAutomationTab', () => {
  beforeEach(() => {
    mockedRead.mockReset();
    mockedApply.mockReset();
  });

  it('disables Apply and states the blocking reason when applying is off in this environment', async () => {
    resolveWith({ engine: engine(), gateReasons: ['CONTROLLER_NOT_READY'], workloads: [workload()] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Applying is off in this environment');
    // The reason is readable without hovering, not hidden in a tooltip only.
    expect(screen.getAllByText('Applying is off in this environment').length).toBeGreaterThan(0);
  });

  it('enables a one-time Apply under RECOMMEND although autoApplyEligible is false', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload({ autoApplyEligible: false })] });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeEnabled();
    // autoApplyEligible is shown for what it is, not used as the gate.
    expect(screen.getByText('Automatic changes Off')).toBeInTheDocument();
    expect(screen.getByText('Mode Suggest only')).toBeInTheDocument();
  });

  it('names the feature, says what it does, and uses the glossary words', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    const { container: page } = renderTab();

    expect(await screen.findByRole('heading', { name: 'Workload rightsizing' })).toBeInTheDocument();
    expect(screen.getByText(/nothing changes in the cluster until you press Apply/)).toBeInTheDocument();
    expect(screen.getByText('Can be applied')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(screen.getByText('Directly to the cluster API')).toBeInTheDocument();
    expect(screen.getByText('Prometheus in your cluster')).toBeInTheDocument();
    expect(screen.getByLabelText('Status: Over-provisioned')).toBeInTheDocument();
    expect(screen.getByText('Could free 1.95 vCPU and 1.51 GiB memory')).toBeInTheDocument();
    // A reason chip shows the short text; its sentence and code sit in the tooltip.
    expect(screen.getByText('Set to the minimum')).toHaveAttribute(
      'title',
      'Set to the minimum XamOps allows (25m CPU, 250Mi memory). Usage is lower, but XamOps never goes below this. Code: FLOOR_CLAMPED',
    );
    // And the sentence is readable without hovering.
    expect(screen.getByText('Why this suggestion')).toBeInTheDocument();
    // Engineering fields are behind the disclosure, phrased plainly.
    expect(screen.getByText('How this was calculated')).toBeInTheDocument();
    expect(
      screen.getByText(
        'CPU: the 95th percentile of usage over the last 24 hours, measured as 5-minute rates, from the busiest pod, plus 25% headroom.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('XamOps defaults (not saved for this workload)')).toBeInTheDocument();
    // Codes never appear as visible text.
    expect(page.textContent).not.toMatch(/FLOOR_CLAMPED|RECOMMEND|DIRECT|CONFIGURED|DEFAULTS|prometheus-proxy/);
  });

  describe('workload status words (glossary section 2)', () => {
    const statusOf = (overrides: Partial<VerticalWorkloadWire>) =>
      workloadStatus(adaptEngineResponse({ engine: engine(), gateReasons: [], workloads: [workload(overrides)] }).workloads[0]);

    it.each([
      ['a floored decrease', {}, 'Over-provisioned'],
      [
        'nothing big enough to change',
        { containers: [container({ cpuRequestM: 80, memRequestMi: 64, cpuLimitM: 200, memLimitMi: 128 }, null, ['WITHIN_HYSTERESIS'])] },
        'Right-sized',
      ],
      [
        'a throttled raise',
        {
          findings: ['THROTTLED'],
          containers: [
            container(
              { cpuRequestM: 10, memRequestMi: 32, cpuLimitM: 50, memLimitMi: 64 },
              { cpuRequestM: 63, memRequestMi: 32, cpuLimitM: 200, memLimitMi: 64 },
              ['BELOW_FLOOR_NO_RAISE', 'LIMIT_MOVED', 'DEMAND_CENSORED_BY_LIMIT'],
            ),
          ],
        },
        'Needs more CPU (throttled)',
      ],
      [
        'requests XamOps would be inventing',
        {
          containers: [
            container(
              { cpuRequestM: null, memRequestMi: null, cpuLimitM: null, memLimitMi: null },
              { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: null, memLimitMi: null },
              ['FLOOR_CLAMPED', 'REQUESTS_UNSET'],
            ),
          ],
        },
        'Left alone: no requests set',
      ],
      [
        'an HPA on CPU',
        {
          hpa: { present: true, name: 'php-apache' },
          containers: [
            container({ cpuRequestM: 200, memRequestMi: 64, cpuLimitM: 500, memLimitMi: 128 }, null, [
              'HPA_CPU_MANAGED',
              'BELOW_FLOOR_NO_RAISE',
              'WITHIN_HYSTERESIS',
            ]),
          ],
        },
        'Left alone: an HPA scales it',
      ],
      ['a single replica', { skipReason: 'SINGLE_REPLICA', containers: [] }, 'Skipped: only 1 replica'],
      ['no history', { skipReason: 'INSUFFICIENT_HISTORY', containers: [] }, 'Not enough data yet'],
      ['rightsizing switched off', { skipReason: 'POLICY_OFF', containers: [] }, 'Off'],
      [
        'a recent apply',
        { containers: [container({ cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 }, null, ['COOLDOWN'])] },
        'Left alone: changed recently',
      ],
    ] as [string, Partial<VerticalWorkloadWire>, string][])('%s reads "%s"', (_name, overrides, expected) => {
      expect(statusOf(overrides).status).toBe(expected);
    });

    it('explains a throttled raise with the reason that drove it', () => {
      const status = statusOf({
        findings: ['THROTTLED'],
        containers: [
          container(
            { cpuRequestM: 10, memRequestMi: 32, cpuLimitM: 50, memLimitMi: 64 },
            { cpuRequestM: 63, memRequestMi: 32, cpuLimitM: 200, memLimitMi: 64 },
            ['BELOW_FLOOR_NO_RAISE', 'LIMIT_MOVED', 'DEMAND_CENSORED_BY_LIMIT'],
          ),
        ],
      });
      expect(status.detail).toBe(
        'Hits its CPU limit most of the time, so real demand may be higher than measured. XamOps sized it from the limit instead.',
      );
    });

    it('names the HPA and drops "already the right size" when memory has its own reason', () => {
      const status = statusOf({
        hpa: { present: true, name: 'php-apache' },
        containers: [
          container({ cpuRequestM: 200, memRequestMi: 64, cpuLimitM: 500, memLimitMi: 128 }, null, [
            'HPA_CPU_MANAGED',
            'BELOW_FLOOR_NO_RAISE',
          ]),
        ],
      });
      expect(status.detail).toBe('CPU is left to the HPA php-apache.');
    });

    it('turns reason codes into sentences, naming the resource, and shows unknown codes as Other', () => {
      expect(reasonSentence({ kind: 'known', code: 'HPA_CPU_MANAGED', label: 'CPU left to the HPA' })).toBe(
        'CPU left alone: an HPA scales this workload on CPU, and changing the request would change how many replicas it runs.',
      );
      const view = adaptEngineResponse({
        engine: engine(),
        gateReasons: [],
        workloads: [
          workload({
            containers: [
              container(
                { cpuRequestM: 50, memRequestMi: 48, cpuLimitM: 150, memLimitMi: 128 },
                { cpuRequestM: 25, memRequestMi: 48, cpuLimitM: 75, memLimitMi: 128 },
                ['FLOOR_CLAMPED', 'BELOW_FLOOR_NO_RAISE'],
              ),
            ],
          }),
        ],
      });
      const [floor, below] = view.workloads[0].containers[0].reasons;
      expect(reasonSentence(floor, view.workloads[0].containers[0], view.workloads[0])).toBe(
        'CPU set to the minimum XamOps allows (25m).',
      );
      expect(reasonSentence(below, view.workloads[0].containers[0], view.workloads[0])).toBe(
        'Memory is already below the minimum (250Mi), so it is left as is.',
      );
      expect(reasonSentence({ kind: 'unknown', raw: 'SOMETHING_NEW' })).toBe('Other: SOMETHING_NEW');
    });
  });

  it('shows the last analysis and the window in IST, with the raw UTC value in the tooltip', async () => {
    const lastCycle = '2026-10-12T07:23:19.015387285Z';
    resolveWith({
      engine: engine({ lastCycle: { at: lastCycle, evaluated: 9, error: null } }),
      gateReasons: [],
      workloads: [workload()],
    });
    renderTab();

    const cycle = await screen.findByTitle(lastCycle);
    // Nanoseconds are cut to milliseconds before parsing; 07:23 UTC is 12:53 IST.
    expect(cycle.textContent).toBe(`${formatIST('2026-10-12T07:23:19.015Z')} · 9 workloads`);
    expect(cycle.textContent).toMatch(/12:53 PM IST · 9 workloads$/);
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
    expect(button).toHaveAttribute('title', READ_ONLY_SENTENCE);
    expect(screen.getByText('Why changes are off')).toBeInTheDocument();
  });

  it('never enables Apply on a payload that did not check the caller (a backend older than the frontend)', async () => {
    // UAT, 2026-10-12: the frontend went live seven minutes before the backend, whose endpoint 1
    // sent no write gates at all. "No gate" from a server that never looked enabled Apply for a user
    // without the automation key, and every click came back 403 K8S_NO_USER_PERMISSION.
    mockedRead.mockResolvedValue({
      kind: 'ok',
      view: adaptEngineResponse({ engine: engine(), gateReasons: [], workloads: [workload()] }),
    });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "Couldn't check whether you can change automation.");
    expect(mockedApply).not.toHaveBeenCalled();
  });

  it.each([
    ['TAB_GRANT', "View only: you don't have permission to change automation.",
      'Ask your XamOps administrator to allow automation changes for your user.'],
    ['IMPERSONATION', "View only while you're signed in as another user.",
      'Return to your own session to change automation.'],
    ['ROLE', 'View only: your role can see automation but not change it.', null],
    ['NO_PRINCIPAL', 'Sign in again to change automation.', null],
  ])('words a NO_USER_PERMISSION gate by its %s detail, with the next step (glossary 7.2)', async (detail, reason, next) => {
    resolveWith({
      engine: engine(),
      gateReasons: ['NO_USER_PERMISSION'],
      gateDetails: { NO_USER_PERMISSION: detail },
      workloads: [workload()],
    });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', reason);
    expect(screen.getByText(reason)).toBeInTheDocument();
    if (next) expect(screen.getByText(next)).toBeInTheDocument();
  });

  it('after a caller-level 403, shows the reason, greys every Apply and reads the gates again', async () => {
    const second = workload({ ref: { namespace: 'team-a', kind: 'Deployment', name: 'idle-batch-listener' } });
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload(), second] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'NO_USER_PERMISSION', label: 'No permission to change automation' },
      message: "You don't have permission to change automation. Ask your XamOps administrator to allow automation changes for your user.",
      detail: 'TAB_GRANT',
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "View only: you don't have permission to change automation. Ask your XamOps administrator to allow automation changes for your user.",
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply idle-batch-listener' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Apply idle-batch-listener' })).toHaveAttribute(
      'title',
      "View only: you don't have permission to change automation.",
    );
    await waitFor(() => expect(mockedRead).toHaveBeenCalledTimes(2));
    expect(mockedApply).toHaveBeenCalledTimes(1);
    // The automatic re-read said "permitted" here, and Apply still stays greyed: the server has
    // already refused this caller once. Only the user's Refresh hands the decision back to endpoint 1.
    expect(screen.getByRole('button', { name: 'Apply idle-batch-listener' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply idle-batch-listener' })).toBeEnabled());
  });

  describe('manual apply gate', () => {
    const gateFor = (wire: VerticalEngineResponseWire) => {
      const view = adaptEngineResponse({ writeGatesChecked: true, ...wire });
      return manualApplyGate(view, view.workloads[0]);
    };
    const cases: { name: string; wire: VerticalEngineResponseWire; reason: string | null }[] = [
      {
        name: 'RECOMMEND + an actionable suggestion is enabled',
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
        reason: 'No change suggested',
      },
      {
        name: 'a suggestion equal to current is nothing to apply',
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
        reason: 'No change suggested',
      },
      {
        name: 'a read-only connection disables',
        wire: { engine: engine(), gateReasons: ['READ_ONLY_CONNECTION'], workloads: [workload()] },
        reason: READ_ONLY_SENTENCE,
      },
      {
        name: 'no permission with no detail disables, in the TAB_GRANT words (glossary 7.2)',
        wire: { engine: engine(), gateReasons: ['NO_USER_PERMISSION'], workloads: [workload()] },
        reason: "View only: you don't have permission to change automation.",
      },
      {
        name: 'a Login-As session disables, in its own words',
        wire: {
          engine: engine(),
          gateReasons: ['NO_USER_PERMISSION'],
          gateDetails: { NO_USER_PERMISSION: 'IMPERSONATION' },
          workloads: [workload()],
        },
        reason: "View only while you're signed in as another user.",
      },
      {
        name: 'demo disables, ahead of the connection',
        wire: { engine: engine(), gateReasons: ['READ_ONLY_CONNECTION', 'DEMO'], workloads: [workload()] },
        reason: "Demo workspace. Changes aren't saved.",
      },
      {
        name: 'a controller that is not ready disables',
        wire: { engine: engine({ ready: false }), gateReasons: [], workloads: [workload()] },
        reason: 'Applying is off in this environment',
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
        reason: 'No cluster access for XamOps',
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
                  recommended: { cpuRequestM: 60, memRequestMi: 400, cpuLimitM: 60, memLimitMi: 400 },
                  reasons: ['COOLDOWN'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'Changed recently',
      },
      {
        name: 'an OOM-driven memory-only raise inside the cooldown is enabled, as endpoint 3 accepts it (SF1)',
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
                  recommended: { cpuRequestM: 25, memRequestMi: 313, cpuLimitM: 25, memLimitMi: 313 },
                  reasons: ['COOLDOWN', 'OOM_RISK'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: null,
      },
      {
        name: 'a memory decrease inside the cooldown still disables',
        wire: {
          engine: engine(),
          gateReasons: [],
          workloads: [
            workload({
              containers: [
                {
                  name: 'api',
                  qosClass: 'Guaranteed',
                  current: { cpuRequestM: 25, memRequestMi: 600, cpuLimitM: 25, memLimitMi: 600 },
                  recommended: { cpuRequestM: 25, memRequestMi: 300, cpuLimitM: 25, memLimitMi: 300 },
                  reasons: ['COOLDOWN'],
                  upstreamVpa: null,
                },
              ],
            }),
          ],
        },
        reason: 'Changed recently',
      },
      {
        name: 'a GitOps-managed workload disables',
        wire: { engine: engine(), gateReasons: [], workloads: [workload({ gitopsManaged: true })] },
        reason: 'Managed by Argo CD or Flux',
      },
      {
        name: 'a skipped workload disables with its skip reason',
        wire: { engine: engine(), gateReasons: [], workloads: [workload({ skipReason: 'POLICY_OFF' })] },
        reason: 'Rightsizing off',
      },
      {
        name: 'requests XamOps would be inventing disable',
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
        reason: 'No requests set',
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

  it('confirms a real resize with before and after per container, then submits it', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({ kind: 'writes-not-deployed' });
    renderTab();

    fireEvent.click(await screen.findByRole('checkbox', { name: /Test run only/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(mockedApply).not.toHaveBeenCalled();
    expect(dialog.textContent).toContain('Resize overprovisioned-api now?');
    expect(dialog.textContent).toContain(
      'api: CPU request 1000m → 25m · Memory request 1 GiB → 250Mi · CPU limit 1000m → 25m · Memory limit 1 GiB → 250Mi',
    );
    expect(dialog.textContent).toContain('XamOps resizes the running pods in place: one pod first as a check');
    expect(dialog.textContent).toContain(
      "The Deployment itself isn't changed, so pods created later (a rollout, a rescheduled pod) start at the old size.",
    );
    expect(dialog.textContent).toContain("XamOps won't change this workload again for 60 minutes.");

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

  it('sends nothing when a real resize is cancelled at the confirmation', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    renderTab();

    fireEvent.click(await screen.findByRole('checkbox', { name: /Test run only/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(mockedApply).not.toHaveBeenCalled();
  });

  it('renders "Not set" for a request the manifest leaves unset, and never a 0', async () => {
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
    const { container: page } = renderTab();

    await screen.findByText('no-requests-sidecar-app');
    const row = page.querySelector('tbody tr')!;
    const cells = Array.from(row.querySelectorAll('td')).map((td) => td.textContent ?? '');

    // Container, then four resource cells, then the Kubernetes VPA cell.
    expect(cells[1]).toBe('Not set');
    expect(cells[2]).toBe('Not set');
    expect(cells[3]).toBe('Not set');
    expect(cells[4]).toBe('Not set');
    // No VPA target renders a dash, not 0, with the reason in the tooltip.
    expect(cells[5]).toBe(DASH);
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

    expect(await screen.findByText("Some numbers couldn't be shown")).toBeInTheDocument();
    expect(screen.getByText(/XamOps sent 0 for .*cpuRequestM, which Kubernetes doesn't allow/)).toBeInTheDocument();
  });

  it('renders the matching empty state, not a table of zeros, when nothing was analysed', async () => {
    resolveWith({ engine: engine({ enabled: false, ready: false }), gateReasons: ['NO_METRICS_SOURCE'], workloads: [] });
    const { container: page } = renderTab();

    expect(await screen.findByText('No usage data for this cluster')).toBeInTheDocument();
    expect(page.querySelector('table')).toBeNull();
  });

  it('explains an unregistered cluster as a setup step rather than a crash', async () => {
    mockedRead.mockResolvedValue({ kind: 'cluster-not-registered', message: 'Install the XamOps agent first.' });
    renderTab();

    expect(await screen.findByText("This cluster isn't connected to XamOps yet")).toBeInTheDocument();
    expect(screen.getByText(/Install the XamOps agent/)).toBeInTheDocument();
  });

  it('explains missing cluster access with its remedy', async () => {
    mockedRead.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'CLUSTER_ACCESS_NOT_CONFIGURED', label: 'No cluster access for XamOps' },
      message: 'No access entry.',
    });
    renderTab();

    expect(await screen.findByText("XamOps can't sign in to this cluster")).toBeInTheDocument();
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
    const { container: page } = renderTab();

    await screen.findByText('unschedulable-giant');
    expect(screen.getByLabelText('Status: Not enough data yet')).toBeInTheDocument();
    expect(screen.getByText(/XamOps needs at least half of the last 24 hours of usage/)).toBeInTheDocument();
    expect(page.querySelector('table')).toBeNull();
    expect(screen.getByText('No usage was measured for this window.')).toBeInTheDocument();
  });

  it('submits a test run and reports an untyped 404 as "not available yet"', async () => {
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

    expect(await screen.findByText(/Applying isn't available in this environment yet/)).toBeInTheDocument();
    expect(screen.queryByText(/Applied/)).toBeNull();
  });

  it('reports a submission with no outcome as submitted, never as applied', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee12-3456',
      state: 'ACCEPTED',
      dryRun: true,
      result: null,
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    expect(await screen.findByText(/Submitted · Test run/)).toBeInTheDocument();
    expect(screen.getByText('Reference c0ffee12')).toHaveAttribute('title', 'c0ffee12-3456');
    expect(screen.getByText('No result reported yet.')).toBeInTheDocument();
  });

  it('renders the restart-free proof for a confirmed resize', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
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

    expect(await screen.findByText('Applied. Confirmed in every pod. No restart.')).toBeInTheDocument();
    expect(screen.getByText('In place, no restart · proven in every pod')).toBeInTheDocument();
    expect(screen.getByTestId('pod-proof-summary')).toHaveTextContent(
      /^overprovisioned-api-5d9f-abcde: same pod · same container · restarts 0 → 0 · ready$/,
    );
    // Review-2 SF7: what the pod ran before and runs now, from its status.
    expect(
      screen.getByText(
        'api: CPU request 1000m → 25m · memory request 1 GiB → 250Mi · CPU limit 1000m → 25m · memory limit 1 GiB → 250Mi',
      ),
    ).toBeInTheDocument();
  });

  describe('the status block and the copy say only what the payload backs (review-2 SF6, items 9-11)', () => {
    const changesValue = () => screen.getByText('Changes').nextElementSibling;

    it.each([
      [['NO_USER_PERMISSION'], { NO_USER_PERMISSION: 'TAB_GRANT' }, 'View only'],
      [['READ_ONLY_CONNECTION'], {}, 'View only'],
      [['DEMO'], { DEMO: 'DEMO' }, 'View only'],
      [[], {}, 'Can be applied'],
    ])('"Changes" with gates %j reads %s', async (gateReasons, gateDetails, value) => {
      resolveWith({ engine: engine(), gateReasons, gateDetails, workloads: [workload()] });
      renderTab();

      await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
      expect(changesValue()).toHaveTextContent(new RegExp(`^${value}$`));
    });

    it('"Changes" reads Not checked when the server did not check the caller', async () => {
      mockedRead.mockResolvedValue({
        kind: 'ok',
        view: adaptEngineResponse({ engine: engine(), gateReasons: [], workloads: [workload()] }),
      });
      renderTab();

      await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
      expect(changesValue()).toHaveTextContent(/^Not checked$/);
      expect(changesValue()).toHaveAttribute('title', "Couldn't check whether you can change automation.");
    });

    it('an empty cluster with only a write gate still reads "No workloads to analyse" (item 9)', async () => {
      resolveWith({ engine: engine(), gateReasons: ['READ_ONLY_CONNECTION'], workloads: [] });
      renderTab();

      expect(await screen.findByText('No workloads to analyse')).toBeInTheDocument();
      expect(screen.queryByText('View only: this account is connected read-only')).toBeNull();
    });

    it('an unknown replica count is not 1: "Could free" is per pod (item 10)', () => {
      const status = workloadStatus(
        adaptEngineResponse({ engine: engine(), gateReasons: [], workloads: [workload({ replicas: null })] }).workloads[0],
      );
      // 1000m → 25m and 1024Mi → 250Mi on one pod, as the two-replica case above shows doubled.
      expect(status.detail).toBe('Could free up to 0.97 vCPU and 774Mi memory per pod');
    });

    it('"How this was calculated" names no usage source it was not given, and no saved choice (item 11)', async () => {
      resolveWith({
        engine: engine(),
        gateReasons: [],
        workloads: [
          workload({
            provenance: {
              percentile: 95,
              windowHours: 24,
              windowStart: null,
              windowEnd: null,
              sampleCoverage: null,
              usageSource: null,
              policySource: 'WORKLOAD_ROW',
            },
          }),
        ],
      });
      renderTab();

      fireEvent.click(await screen.findByText('How this was calculated'));
      expect(screen.getByText(`Usage source: ${DASH}`)).toBeInTheDocument();
      expect(screen.queryByText(/Usage from Prometheus/)).toBeNull();
      expect(
        screen.getByText('Settings recorded for this workload when a change was applied: the defaults in force then'),
      ).toBeInTheDocument();
      expect(screen.queryByText('Saved for this workload')).toBeNull();
    });
  });

  it('flags a container whose pods disagree beside "now" (review-2 SF2)', async () => {
    const base = workload();
    resolveWith({
      engine: engine(),
      gateReasons: [],
      workloads: [workload({ containers: [{ ...base.containers![0], podsDisagree: true }] })],
    });
    renderTab();

    const chip = await screen.findByText('Pods differ');
    expect(chip).toHaveAttribute('title', expect.stringContaining('new pods start at'));
  });

  it('says "every running pod (N of M)" and lists the pods it left out (review-2 SF3)', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    const resized = (name: string) => ({
      podName: name,
      uidBefore: `uid-${name}`,
      uidAfter: `uid-${name}`,
      samePod: true,
      ready: true,
      containers: [
        {
          name: 'api',
          containerIdBefore: 'c1',
          containerIdAfter: 'c1',
          restartCountBefore: 0,
          restartCountAfter: 0,
          observedBefore: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
          observedAfter: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
        },
      ],
    });
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
        containers: [{ name: 'api', state: 'APPLIED', detail: null, requested: null, restartExpected: false }],
        pods: [resized('api-1'), resized('api-2')],
        skippedPods: [{ podName: 'api-3', phase: 'Pending', reason: 'NOT_RUNNING' }],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Applied. Confirmed in every running pod (2 of 3). No restart.')).toBeInTheDocument();
    expect(screen.getByText('In place, no restart · proven in every running pod (2 of 3)')).toBeInTheDocument();
    expect(screen.getByText('api-3: not running (Pending)')).toBeInTheDocument();
    expect(screen.queryByText(/Confirmed in every pod/)).toBeNull();
  });

  it('a partial outcome shows which pod changed and which did not (review-2 SF7)', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    const proofOf = (name: string, after: { cpuRequestM: number; memRequestMi: number; cpuLimitM: number; memLimitMi: number }) => ({
      podName: name,
      uidBefore: `uid-${name}`,
      uidAfter: `uid-${name}`,
      samePod: true,
      ready: true,
      containers: [
        {
          name: 'api',
          containerIdBefore: 'c1',
          containerIdAfter: 'c1',
          restartCountBefore: 0,
          restartCountAfter: 0,
          observedBefore: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
          observedAfter: after,
        },
      ],
    });
    mockedApply.mockResolvedValue({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: false,
      result: {
        method: 'IN_PLACE',
        terminal: 'PARTIALLY_APPLIED',
        detail: 'The canary pod was resized and confirmed, but pod api-2 was not.',
        restartFree: false,
        containers: [{ name: 'api', state: 'FAILED', detail: null, requested: null, restartExpected: false }],
        pods: [
          proofOf('api-1', { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 }),
          proofOf('api-2', { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 }),
        ],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Partly applied: it stopped at the first pod that failed.')).toBeInTheDocument();
    expect(screen.queryByText(/The pods below say which changed/)).toBeNull();
    expect(
      screen.getByText(
        'api: CPU request 1000m → 25m · memory request 1 GiB → 250Mi · CPU limit 1000m → 25m · memory limit 1 GiB → 250Mi',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('api: no change')).toBeInTheDocument();
  });

  it('renders a deferred outcome as a wait for room, never as a success', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
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
        containers: [{ name: 'api', state: 'DEFERRED', detail: null, requested: null, restartExpected: false }],
        pods: [],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(
      await screen.findByText("Not applied: the node doesn't have room right now. Kubernetes may apply it later."),
    ).toBeInTheDocument();
    expect(screen.getByText('In place · no restart-free proof')).toBeInTheDocument();
    expect(screen.getByText('api: Waiting for room on the node')).toBeInTheDocument();
    expect(screen.queryByText(/Applied\. Confirmed/)).toBeNull();
  });

  it('keeps a card greyed while its own apply runs, even when another card finishes first (review-2 SF5)', async () => {
    const second = workload({ ref: { namespace: 'team-a', kind: 'Deployment', name: 'idle-batch-listener' } });
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload(), second] });
    let finishFirst: (value: unknown) => void = () => undefined;
    const testRun = { kind: 'submitted', correlationId: 'c', state: 'ACCEPTED', dryRun: true, result: null };
    mockedApply
      .mockImplementationOnce(() => new Promise((resolve) => { finishFirst = resolve; }))
      .mockResolvedValueOnce(testRun);
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: 'Apply idle-batch-listener' }));
    await waitFor(() => expect(mockedApply).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply idle-batch-listener' })).toBeEnabled());

    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' }))
      .toBeDisabled();
    finishFirst(testRun);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeEnabled());
  });

  it('explains a 409 from endpoint 3: another change to the workload is still running', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'APPLY_IN_PROGRESS', label: 'Another change is running' },
      message: 'Another change to team-a/overprovisioned-api is still being applied.',
      detail: null,
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Not applied: another change to this workload is still running. Wait for it to finish, then refresh.',
    );
    // Not a caller-level refusal: the other cards are not greyed by it.
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeEnabled();
  });

  describe('a declined resize that XamOps put back (review-1 S7)', () => {
    const declined = (terminal: string, reverted: boolean, insufficient: string | null, revertState: string) => ({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: false,
      result: {
        method: 'IN_PLACE',
        terminal,
        detail: `The kubelet declined it (${terminal}). XamOps put pod prod-sim/throttled-encoder-8b999cc87-mcw8q back.`,
        restartFree: false,
        containers: [{ name: 'api', state: terminal, detail: null, requested: null, restartExpected: false }],
        pods: [
          {
            podName: 'throttled-encoder-8b999cc87-mcw8q',
            uidBefore: 'u1',
            uidAfter: 'u1',
            samePod: true,
            ready: true,
            changed: false,
            revert: { state: revertState, detail: 'XamOps put the pod back.' },
            containers: [
              {
                name: 'api',
                containerIdBefore: 'c1',
                containerIdAfter: 'c1',
                restartCountBefore: 0,
                restartCountAfter: 0,
                observedBefore: { cpuRequestM: 10, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 128 },
                observedAfter: { cpuRequestM: 10, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 128 },
              },
            ],
          },
        ],
        reverted,
        insufficient,
      },
    });

    const applyAndRead = async () => {
      fireEvent.click(await screen.findByRole('checkbox', { name: /Test run only/ }));
      fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Resize pods' }));
    };

    it('DEFERRED for CPU says the pod was put back and what would let it through', async () => {
      resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
      mockedApply.mockResolvedValue(declined('DEFERRED', true, 'cpu', 'REVERTED'));
      renderTab();
      await applyAndRead();

      expect(
        await screen.findByText(
          'The node has no free CPU for this increase, so XamOps put the pod back as it was. Free room on that node, for example by applying an over-provisioned workload there, or add a node.',
        ),
      ).toBeInTheDocument();
      expect(screen.getByText('api: Not applied: put back as it was')).toBeInTheDocument();
      expect(screen.getByText(/put back as it was$/, { selector: 'span' })).toBeInTheDocument();
      expect(screen.queryByText(/Nothing was changed/)).toBeNull();
      expect(screen.queryByText(/Kubernetes may apply it later/)).toBeNull();
    });

    it('INFEASIBLE for memory says it can never fit, and that the pod was put back', async () => {
      resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
      mockedApply.mockResolvedValue(declined('INFEASIBLE', true, 'memory', 'REVERTED'));
      renderTab();
      await applyAndRead();

      expect(
        await screen.findByText(
          'This size can never fit on the node: it has less memory in total than the pod would ask for, so XamOps put the pod back as it was. Choose a smaller size, or run the workload on a larger node.',
        ),
      ).toBeInTheDocument();
      expect(screen.queryByText(/Nothing was changed/)).toBeNull();
    });

    it('a pod that could not be proved back says the new size may still be applied', async () => {
      resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
      mockedApply.mockResolvedValue(declined('DEFERRED', false, 'cpu', 'NOT_CONFIRMED'));
      renderTab();
      await applyAndRead();

      expect(
        await screen.findByText(
          "Not applied: the node doesn't have room right now. XamOps tried to put the pod back as it was and couldn't confirm it, so the new size may still be applied later. Check the pod before trying again.",
        ),
      ).toBeInTheDocument();
      expect(screen.getByText(/sent back, but it didn't confirm$/, { selector: 'span' })).toBeInTheDocument();
    });
  });

  it('renders a test run as a rehearsal, not as a change', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
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
        containers: [{ name: 'api', state: 'DRY_RUN', detail: null, requested: null, restartExpected: false }],
        pods: [],
      },
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByText('Test run passed. Nothing was sent to the cluster.')).toBeInTheDocument();
    expect(screen.queryByText(/Applied\. Confirmed/)).toBeNull();
  });

  it('renders an unknown outcome as Other instead of guessing', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
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

    expect(await screen.findByText('Other: SOMETHING_NEW')).toBeInTheDocument();
  });

  it('explains a refused apply in plain words, with the server message in the tooltip', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'known', code: 'READ_ONLY_CONNECTION', label: 'Read-only connection' },
      message: 'This AWS account is connected read-only.',
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    const text = await screen.findByRole('alert');
    expect(text).toHaveTextContent(READ_ONLY_SENTENCE);
    expect(text).toHaveAttribute('title', 'This AWS account is connected read-only.');
    // A caller-level refusal holds for the whole page, so the control now says the same thing.
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled();
  });

  it('explains a POLICY_OFF refusal from endpoint 3 in plain words (review-2 item 15)', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'unknown', raw: 'K8S_INVALID_POLICY' },
      message: 'POLICY_OFF: rightsizing is off for team-a/overprovisioned-api, so XamOps does not change it.',
      detail: null,
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not applied: rightsizing is off for this workload.');
  });

  it('explains a cooldown refusal from endpoint 3 in plain words', async () => {
    resolveWith({ engine: engine(), gateReasons: [], workloads: [workload()] });
    mockedApply.mockResolvedValue({
      kind: 'refused',
      code: { kind: 'unknown', raw: 'K8S_INVALID_POLICY' },
      message: 'COOLDOWN: a change was applied to team-a/overprovisioned-api at 2026-10-12T07:00:00Z.',
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Apply overprovisioned-api' }));

    expect(
      await screen.findByText('Not applied: this workload was changed less than 60 minutes ago. Nothing was sent.'),
    ).toBeInTheDocument();
  });
});
