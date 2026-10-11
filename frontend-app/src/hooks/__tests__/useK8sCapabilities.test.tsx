import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import { CONTRACT_BLOCK_MESSAGE, toVerticalEngineView } from '../../api/verticalRightsizerAdapter';
import {
  K8sCapabilitiesProvider,
  LOADING_CAPABILITY,
  RETIRED_RIGHTSIZER_MESSAGE,
  computeCapability,
  rankGates,
  useK8sCapabilities,
  type CapabilityInput,
} from '../useK8sCapabilities';
import {
  ACTION_IDS,
  READINESS_KEYS,
  REASON_CODES,
  type ActionId,
  type CapabilitiesDto,
  type Gate,
  type ReasonCode,
} from '../../types/k8sAutomation';
import type { VerticalStatusWire } from '../../types/verticalRightsizer';

// ── Fixtures ─────────────────────────────────────────────────────────────────

const MESSAGES: Record<string, string> = {
  'vertical-rightsizing': 'Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. Recommendations stay visible.',
  'hpa-apply': 'Automated HPA changes are disabled until the HPA manager passes safety validation.',
  'vpa-apply': "XamOps doesn't create or update VPA objects yet.",
  'evictor-live': 'Live consolidation is disabled until pod-fit simulation is available.',
  rebalancer: 'Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly.',
  'node-optimizer-actions': 'Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation.',
  'scale-out': "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does.",
  'node-templates': "Not available: the automation agent doesn't use node templates yet.",
};

interface CapsOptions {
  writeReasons?: string[];
  detail?: string | null;
  clusterWriteReasons?: string[];
  ready?: string[];
  automationAgent?: 'CONNECTED' | 'STALE' | 'NOT_INSTALLED' | 'UNKNOWN';
  agent?: 'CONNECTED' | 'STALE' | 'NOT_INSTALLED' | 'UNKNOWN';
}

function caps(o: CapsOptions = {}): CapabilitiesDto {
  const writeReasons = o.writeReasons ?? [];
  const clusterReasons = o.clusterWriteReasons ?? writeReasons;
  const userReasons = writeReasons.filter((r) => r === 'DEMO' || r === 'NO_USER_PERMISSION');
  return {
    schemaVersion: 1,
    accountId: '111111111111',
    generatedAt: '2026-10-10T09:30:00Z',
    connection: { accessLevel: writeReasons.includes('READ_ONLY_CONNECTION') ? 'READ_ONLY' : 'READ_WRITE', status: 'CONNECTED' },
    user: { canChangeAutomation: userReasons.length === 0, reasons: userReasons, detail: o.detail ?? null },
    writeGate: { allowed: writeReasons.length === 0, reasons: writeReasons },
    readiness: Object.fromEntries(READINESS_KEYS.map((key) => {
      const ready = (o.ready ?? []).includes(key);
      return [key, ready
        ? { ready: true, reason: null, message: null }
        : { ready: false, reason: key === 'evictor-live' ? 'ANALYSE_ONLY' : 'CONTROLLER_NOT_READY', message: MESSAGES[key] }];
    })),
    verticalEngine: { enabled: true },
    clusters: [{
      clusterName: 'prod-eks',
      registered: true,
      connection: { accessLevel: 'READ_WRITE', status: 'CONNECTED' },
      writeGate: { allowed: clusterReasons.length === 0, reasons: clusterReasons },
      dataSourceMode: 'agent',
      agent: { status: o.agent ?? 'CONNECTED', lastDataAt: '2026-10-10T09:26:41Z', version: 'v2.1.0' },
      automationAgent: { status: o.automationAgent ?? 'CONNECTED', lastSeenAt: '2026-10-10T09:29:20Z', version: '1.0.0-autoscaler' },
      nodeAutomation: { enabled: true, mode: 'full', evictorAnalyseOnly: true, derivedState: 'ANALYSIS' },
      dataSources: [],
    }],
  };
}

const can = (actionId: ActionId, data: CapabilitiesDto | null, extra: Partial<CapabilityInput> = {}) =>
  computeCapability({ actionId, status: 'ready', data, provider: 'AWS', demo: false, cluster: 'prod-eks', ...extra });

const reasons = (c: ReturnType<typeof rankGates>) => [c.gate?.reason, ...c.also.map((g) => g.reason)];

function engine(over: Partial<VerticalStatusWire['engine']> = {}, gateReasons: string[] = []) {
  return toVerticalEngineView({
    engine: {
      enabled: true, ready: false, clusterAccess: 'NOT_CONFIGURED', executionPath: 'AGENT',
      executionPathDetail: {
        direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
        agent: { available: true, reason: null, lastSeenAt: null },
      },
      metricsSource: { reachable: true, name: 'in-cluster' },
      lastCycle: { at: null, evaluated: null, error: null },
      ...over,
    },
    gateReasons,
    workloads: [],
  });
}

const VERTICAL: ActionId[] = ['vertical.policy.recommend', 'vertical.policy.auto', 'vertical.method.persist', 'vertical.apply'];

// ── rankGates: §6.4 ──────────────────────────────────────────────────────────

describe('rankGates (§6.4 precedence)', () => {
  it('allows when there is no gate', () => {
    expect(rankGates([])).toEqual({ allowed: true, editable: true, gate: null, also: [] });
  });

  it('blocks on each reason in isolation; DEMO alone stays editable', () => {
    for (const reason of REASON_CODES) {
      const c = rankGates([{ reason }]);
      expect(c.allowed).toBe(false);
      expect(c.gate?.reason).toBe(reason);
      expect(c.also).toEqual([]);
      expect(c.editable).toBe(reason === 'DEMO');
    }
  });

  const ORDER: ReasonCode[][] = [
    ['PROVIDER_UNSUPPORTED'],
    ['CONTROLLER_NOT_READY', 'ANALYSE_ONLY', 'GITOPS_MANAGED'],
    ['NO_USER_PERMISSION'],
    ['READ_ONLY_CONNECTION'],
    ['CONNECTION_NOT_VERIFIED'],
    ['UNKNOWN'],
    ['AGENT_NOT_INSTALLED', 'NO_METRICS_SOURCE'],
    ['INSUFFICIENT_HISTORY'],
  ];

  it('puts the harder-to-remove reason first for every pair that can co-occur', () => {
    for (let i = 0; i < ORDER.length; i++) {
      for (let j = i + 1; j < ORDER.length; j++) {
        for (const higher of ORDER[i]) {
          for (const lower of ORDER[j]) {
            const c = rankGates([{ reason: lower }, { reason: higher }]);
            expect(reasons(c), `${higher} before ${lower}`).toEqual([higher, lower]);
            expect(c.editable).toBe(false);
          }
        }
      }
    }
  });

  it('keeps insertion order inside one rank', () => {
    expect(reasons(rankGates([{ reason: 'ANALYSE_ONLY' }, { reason: 'CONTROLLER_NOT_READY' }])))
      .toEqual(['ANALYSE_ONLY', 'CONTROLLER_NOT_READY']);
  });

  it('never makes the direct-path codes primary', () => {
    const c = rankGates([{ reason: 'CLUSTER_UNREACHABLE' }, { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH' }]);
    expect(c.gate?.reason).toBe('AGENT_NOT_INSTALLED');
  });

  it('makes DEMO primary only when alone, and otherwise last in also', () => {
    const alone = rankGates([{ reason: 'DEMO' }]);
    expect(alone).toEqual({ allowed: false, editable: true, gate: { reason: 'DEMO' }, also: [] });

    const withAnalyse = rankGates([{ reason: 'DEMO' }, { reason: 'ANALYSE_ONLY' }]);
    expect(withAnalyse.gate?.reason).toBe('ANALYSE_ONLY');
    expect(withAnalyse.also.map((g) => g.reason)).toEqual(['DEMO']);
    expect(withAnalyse.editable).toBe(false);

    const withReadOnly = rankGates([{ reason: 'DEMO' }, { reason: 'READ_ONLY_CONNECTION' }, { reason: 'UNKNOWN' }]);
    expect(reasons(withReadOnly)).toEqual(['READ_ONLY_CONNECTION', 'UNKNOWN', 'DEMO']);
    expect(withReadOnly.editable).toBe(false);
  });

  it('de-duplicates by code, keeping the first occurrence and its message', () => {
    const c = rankGates([
      { reason: 'CONTROLLER_NOT_READY', message: 'first' },
      { reason: 'READ_ONLY_CONNECTION' },
      { reason: 'CONTROLLER_NOT_READY', message: 'second' },
    ]);
    expect(c.gate).toEqual({ reason: 'CONTROLLER_NOT_READY', message: 'first' });
    expect(c.also).toEqual([{ reason: 'READ_ONLY_CONNECTION' }]);
  });
});

// ── computeCapability ────────────────────────────────────────────────────────

describe('computeCapability', () => {
  it('blocks without a reason line while loading', () => {
    for (const id of ACTION_IDS) {
      expect(computeCapability({ actionId: id, status: 'loading', data: null })).toBe(LOADING_CAPABILITY);
    }
    expect(LOADING_CAPABILITY).toEqual({ allowed: false, editable: false, gate: null, also: [] });
  });

  it('allows a permitted read-write user on a connected cluster', () => {
    expect(can('automation.enable', caps())).toEqual({ allowed: true, editable: true, gate: null, also: [] });
    expect(can('guardrails.edit', caps()).allowed).toBe(true);
  });

  it('maps each server write gate, NO_USER_PERMISSION with its detail', () => {
    expect(can('automation.enable', caps({ writeReasons: ['READ_ONLY_CONNECTION'] })).gate).toEqual({ reason: 'READ_ONLY_CONNECTION' });
    expect(can('automation.enable', caps({ writeReasons: ['CONNECTION_NOT_VERIFIED'] })).gate).toEqual({ reason: 'CONNECTION_NOT_VERIFIED' });
    for (const detail of ['TAB_GRANT', 'ROLE', 'IMPERSONATION'] as const) {
      expect(can('automation.enable', caps({ writeReasons: ['NO_USER_PERMISSION'], detail })).gate)
        .toEqual({ reason: 'NO_USER_PERMISSION', detail });
    }
    expect(can('automation.enable', caps({ writeReasons: ['DEMO'] })).gate).toEqual({ reason: 'DEMO' });
    expect(can('automation.enable', caps({ writeReasons: ['K8S_SOMETHING_NEW'] })).gate)
      .toEqual({ reason: 'UNKNOWN', raw: 'K8S_SOMETHING_NEW' });
  });

  it('adds readiness per action with the server message', () => {
    expect(can('evictor.live', caps()).gate).toEqual({ reason: 'ANALYSE_ONLY', message: MESSAGES['evictor-live'] });
    expect(can('rebalance.trigger', caps()).gate).toEqual({ reason: 'CONTROLLER_NOT_READY', message: MESSAGES.rebalancer });
    expect(can('rebalance.schedule', caps()).gate?.message).toBe(MESSAGES.rebalancer);
    expect(can('scaleOut.settings', caps()).gate?.message).toBe(MESSAGES['scale-out']);
    expect(can('nodeTemplates.edit', caps()).gate?.message).toBe(MESSAGES['node-templates']);
    expect(can('nodeOptimizer.actions', caps()).gate?.message).toBe(MESSAGES['node-optimizer-actions']);
    expect(can('hpa.apply', caps()).gate?.message).toBe(MESSAGES['hpa-apply']);
    expect(can('evictor.settings', caps()).allowed).toBe(true);
    expect(can('nodeOptimizer.settings', caps()).allowed).toBe(true);

    const allReady = caps({ ready: [...READINESS_KEYS] });
    expect(can('evictor.live', allReady).allowed).toBe(true);
    expect(can('agentRightsizer.edit', allReady).gate).toEqual({ reason: 'CONTROLLER_NOT_READY', message: RETIRED_RIGHTSIZER_MESSAGE });
  });

  it('needs the agents for node-automation controls only', () => {
    const noAutomation = caps({ automationAgent: 'NOT_INSTALLED' });
    expect(can('automation.enable', noAutomation).gate).toEqual({ reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' });
    expect(can('evictor.live', noAutomation).also).toEqual([{ reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' }]);
    expect(can('rebalance.trigger', noAutomation).also).toEqual([]);

    expect(can('automation.scope', caps({ agent: 'NOT_INSTALLED', automationAgent: 'NOT_INSTALLED' })).gate)
      .toEqual({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' });
    expect(can('guardrails.edit', caps(), { cluster: 'unregistered-eks' }).gate)
      .toEqual({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' });
    expect(can('automation.enable', caps({ automationAgent: 'STALE' })).allowed).toBe(true);
    expect(can('automation.enable', caps({ automationAgent: 'UNKNOWN' })).gate).toEqual({ reason: 'UNKNOWN' });
  });

  it("uses the cluster's own writeGate over the top level", () => {
    const clusterAllowed = caps({ writeReasons: ['READ_ONLY_CONNECTION'], clusterWriteReasons: [] });
    expect(can('automation.enable', clusterAllowed).allowed).toBe(true);
    expect(can('automation.enable', clusterAllowed, { cluster: 'unlisted' }).gate?.reason).toBe('READ_ONLY_CONNECTION');

    const clusterBlocked = caps({ writeReasons: [], clusterWriteReasons: ['READ_ONLY_CONNECTION'] });
    expect(can('automation.enable', clusterBlocked).gate?.reason).toBe('READ_ONLY_CONNECTION');
  });

  it('fails closed when capabilities failed', () => {
    for (const id of ACTION_IDS) {
      const c = computeCapability({ actionId: id, status: 'error', data: null, cluster: 'prod-eks' });
      expect(c.allowed).toBe(false);
      expect(c.editable).toBe(false);
      expect(reasons(c)).toContain('UNKNOWN');
    }
    expect(computeCapability({ actionId: 'automation.enable', status: 'error', data: null }).gate).toEqual({ reason: 'UNKNOWN' });
    expect(computeCapability({ actionId: 'automation.enable', status: 'error', data: null, demo: true }).also)
      .toEqual([{ reason: 'DEMO' }]);
  });

  it('marks non-AWS providers unsupported, above everything else', () => {
    const c = can('evictor.live', caps({ writeReasons: ['READ_ONLY_CONNECTION'] }), { provider: 'GCP' });
    expect(reasons(c)).toEqual(['PROVIDER_UNSUPPORTED', 'ANALYSE_ONLY', 'READ_ONLY_CONNECTION']);
    expect(computeCapability({ actionId: 'automation.enable', status: 'ready', data: null, provider: 'Azure' }).gate)
      .toEqual({ reason: 'PROVIDER_UNSUPPORTED' });
  });

  it('applies the DEMO rule (§6.4) on both demo accounts', () => {
    const readWrite = can('automation.enable', caps(), { demo: true });
    expect(readWrite).toEqual({ allowed: false, editable: true, gate: { reason: 'DEMO' }, also: [] });

    const live = can('evictor.live', caps(), { demo: true });
    expect(live.gate?.reason).toBe('ANALYSE_ONLY');
    expect(live.also).toEqual([{ reason: 'DEMO' }]);

    const readOnly = can('automation.enable', caps({ writeReasons: ['READ_ONLY_CONNECTION'] }), { demo: true });
    expect(readOnly.gate?.reason).toBe('READ_ONLY_CONNECTION');
    expect(readOnly.editable).toBe(false);
    expect(readOnly.also[readOnly.also.length - 1]).toEqual({ reason: 'DEMO' });
  });

  it('reads like §6.4 for a read-only customer', () => {
    const ro = caps({ writeReasons: ['READ_ONLY_CONNECTION'] });
    expect(reasons(can('evictor.live', ro))).toEqual(['ANALYSE_ONLY', 'READ_ONLY_CONNECTION']);
    expect(can('automation.enable', ro).gate?.reason).toBe('READ_ONLY_CONNECTION');

    const recommend = can('vertical.policy.recommend', ro, { vertical: { view: engine() } });
    expect(recommend.gate).toEqual({ reason: 'CONTROLLER_NOT_READY', message: CONTRACT_BLOCK_MESSAGE });
    expect(recommend.also.map((g) => g.reason)).toEqual(['READ_ONLY_CONNECTION']);

    const none = engine({
      executionPath: 'NONE',
      executionPathDetail: {
        direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
        agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
      },
    });
    const auto = can('vertical.policy.auto', ro, { vertical: { view: none } });
    expect(auto.gate?.message).toBe(CONTRACT_BLOCK_MESSAGE);
    expect(auto.also).toEqual([
      { reason: 'READ_ONLY_CONNECTION' },
      { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
    ]);
  });
});

// ── Vertical union (§6.2 step 2, §6.3, D16) ──────────────────────────────────

describe('computeCapability for vertical actions', () => {
  it('unions the engine gateReasons with capabilities, so the stricter answer wins', () => {
    const c = can('vertical.apply', caps(), { vertical: { view: engine({}, ['READ_ONLY_CONNECTION']) } });
    expect(c.also.map((g) => g.reason)).toContain('READ_ONLY_CONNECTION');
  });

  it('keeps one CONTROLLER_NOT_READY carrying the contract message', () => {
    for (const id of VERTICAL) {
      const c = can(id, caps(), { vertical: { view: engine({}, ['CONTROLLER_NOT_READY']) } });
      expect(c.gate).toEqual({ reason: 'CONTROLLER_NOT_READY', message: CONTRACT_BLOCK_MESSAGE });
      expect(reasons(c).filter((r) => r === 'CONTROLLER_NOT_READY')).toHaveLength(1);
    }
  });

  it('reports no execution path on Auto and apply only', () => {
    const none = engine({
      executionPath: 'NONE',
      executionPathDetail: {
        direct: { available: false, reason: 'CLUSTER_UNREACHABLE' },
        agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
      },
    });
    const pathGate: Gate = { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_UNREACHABLE' };
    expect(can('vertical.policy.auto', caps(), { vertical: { view: none } }).also).toContainEqual(pathGate);
    expect(can('vertical.apply', caps(), { vertical: { view: none } }).also).toContainEqual(pathGate);
    expect(can('vertical.policy.recommend', caps(), { vertical: { view: none } }).also).not.toContainEqual(pathGate);
    expect(can('vertical.method.persist', caps(), { vertical: { view: none } }).also).not.toContainEqual(pathGate);
  });

  it('never gates a working agent path on an unreachable direct path, and fails closed on a missing path', () => {
    const agent = engine({ executionPathDetail: {
      direct: { available: false, reason: 'CLUSTER_UNREACHABLE' },
      agent: { available: true, reason: null, lastSeenAt: null },
    } }, ['CLUSTER_UNREACHABLE']);
    expect(reasons(can('vertical.apply', caps({ ready: ['vertical-rightsizing'] }), { vertical: { view: agent } })))
      .toEqual(['CONTROLLER_NOT_READY']);

    const wire = engine();
    const missing = { ...wire, executionPath: { kind: 'unknown', raw: 'missing' } as const };
    expect(can('vertical.apply', caps(), { vertical: { view: missing } }).also).toContainEqual({ reason: 'UNKNOWN', raw: 'missing' });
  });

  it('scopes metrics and history away from Recommend, and sends engine and unknown codes to every write', () => {
    const view = engine({}, ['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY', 'CONTROLLER_NOT_READY', 'FUTURE_GATE']);
    const recommend = reasons(can('vertical.policy.recommend', caps(), { vertical: { view } }));
    expect(recommend).not.toContain('NO_METRICS_SOURCE');
    expect(recommend).not.toContain('INSUFFICIENT_HISTORY');
    for (const id of VERTICAL) {
      const c = can(id, caps(), { vertical: { view } });
      expect(reasons(c)).toContain('CONTROLLER_NOT_READY');
      expect([c.gate, ...c.also]).toContainEqual({ reason: 'UNKNOWN', raw: 'FUTURE_GATE' });
    }
    expect(reasons(can('vertical.apply', caps(), { vertical: { view } })))
      .toEqual(expect.arrayContaining(['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY']));
  });

  it('blocks every vertical write with UNKNOWN when the engine status failed', () => {
    for (const id of VERTICAL) {
      expect(reasons(can(id, caps(), { vertical: { failed: true } }))).toContain('UNKNOWN');
    }
  });
});

// ── Provider ─────────────────────────────────────────────────────────────────

function Probe() {
  const caps = useK8sCapabilities();
  const c = caps.can('automation.enable', { cluster: 'prod-eks' });
  return (
    <div>
      <span data-testid="status">{caps.status}</span>
      <span data-testid="no-access">{String(caps.noAccess)}</span>
      <span data-testid="engine">{String(caps.verticalEngineEnabled)}</span>
      <span data-testid="gate">{c.gate?.reason ?? (c.allowed ? 'allowed' : 'blocked')}</span>
      <span data-testid="cluster">{caps.forCluster('prod-eks')?.clusterName ?? 'none'}</span>
      <button type="button" onClick={caps.refetch}>Retry</button>
      <button type="button" onClick={() => caps.onRefusal(new K8sApiError({ status: 403, code: 'K8S_READ_ONLY_CONNECTION' }))}>gate</button>
      <button type="button" onClick={() => caps.onRefusal(new K8sApiError({ status: 400, code: 'K8S_INVALID_POLICY' }))}>invalid</button>
    </div>
  );
}

describe('K8sCapabilitiesProvider', () => {
  beforeEach(() => getCapabilities.mockReset());

  it('loads on mount and answers can() from the response', async () => {
    getCapabilities.mockResolvedValue(caps());
    render(<K8sCapabilitiesProvider accountId="111111111111" demo={false}><Probe /></K8sCapabilitiesProvider>);
    expect(screen.getByTestId('status').textContent).toBe('loading');
    expect(screen.getByTestId('gate').textContent).toBe('blocked');
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    expect(screen.getByTestId('gate').textContent).toBe('allowed');
    expect(screen.getByTestId('engine').textContent).toBe('true');
    expect(screen.getByTestId('cluster').textContent).toBe('prod-eks');
    expect(getCapabilities).toHaveBeenCalledWith('111111111111', expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it('fails closed on an error and retries', async () => {
    getCapabilities.mockRejectedValueOnce(new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }));
    getCapabilities.mockResolvedValueOnce(caps());
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<K8sCapabilitiesProvider accountId="111111111111" demo={false}><Probe /></K8sCapabilitiesProvider>);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('error'));
    expect(screen.getByTestId('gate').textContent).toBe('UNKNOWN');
    expect(screen.getByTestId('no-access').textContent).toBe('false');
    expect(screen.getByTestId('engine').textContent).toBe('false');
    await act(async () => screen.getByText('Retry').click());
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    expect(getCapabilities).toHaveBeenCalledTimes(2);
  });

  it('reports no access on a 403', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (const code of ['K8S_NO_ACCOUNT_ACCESS', null]) {
      getCapabilities.mockRejectedValueOnce(new K8sApiError({ status: 403, code }));
      const { unmount } = render(<K8sCapabilitiesProvider accountId="2" demo={false}><Probe /></K8sCapabilitiesProvider>);
      await waitFor(() => expect(screen.getByTestId('no-access').textContent).toBe('true'));
      unmount();
    }
  });

  it('refetches after a refusal whose code is a gate, not after other errors', async () => {
    getCapabilities.mockResolvedValue(caps());
    render(<K8sCapabilitiesProvider accountId="111111111111" demo={false}><Probe /></K8sCapabilitiesProvider>);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    await act(async () => screen.getByText('invalid').click());
    expect(getCapabilities).toHaveBeenCalledTimes(1);
    await act(async () => screen.getByText('gate').click());
    await waitFor(() => expect(getCapabilities).toHaveBeenCalledTimes(2));
  });

  it('makes no request for a non-AWS account', () => {
    render(<K8sCapabilitiesProvider accountId="proj-1" provider="GCP" demo={false}><Probe /></K8sCapabilitiesProvider>);
    expect(getCapabilities).not.toHaveBeenCalled();
    expect(screen.getByTestId('gate').textContent).toBe('PROVIDER_UNSUPPORTED');
  });

  it('adds DEMO in a demo session', async () => {
    getCapabilities.mockResolvedValue(caps());
    render(<K8sCapabilitiesProvider accountId="demo-aws-001" demo><Probe /></K8sCapabilitiesProvider>);
    await waitFor(() => expect(screen.getByTestId('gate').textContent).toBe('DEMO'));
  });

  it('reloads when the account changes', async () => {
    getCapabilities.mockResolvedValue(caps());
    const { rerender } = render(<K8sCapabilitiesProvider accountId="1" demo={false}><Probe /></K8sCapabilitiesProvider>);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    rerender(<K8sCapabilitiesProvider accountId="2" demo={false}><Probe /></K8sCapabilitiesProvider>);
    await waitFor(() => expect(getCapabilities).toHaveBeenLastCalledWith('2', expect.anything()));
  });

  it('refuses to run outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow('useK8sCapabilities must be used inside K8sCapabilitiesProvider');
  });
});
