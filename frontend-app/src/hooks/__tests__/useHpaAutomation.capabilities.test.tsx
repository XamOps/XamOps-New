import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

const { fetchHpaPage, applyHpaRecommendation } = vi.hoisted(() => ({
  fetchHpaPage: vi.fn(),
  applyHpaRecommendation: vi.fn(),
}));
vi.mock('../../api/hpaApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/hpaApi')>();
  return { ...actual, fetchHpaPage, applyHpaRecommendation };
});

import { adaptHpaPage } from '../../api/hpaAdapter';
import { K8sCapabilitiesProvider } from '../useK8sCapabilities';
import { READINESS_KEYS, type CapabilitiesDto } from '../../types/k8sAutomation';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../types/verticalRightsizer';
import type { HpaReadResult } from '../../types/hpaAutomation';
import useHpaAutomation from '../useHpaAutomation';

const CLUSTER = 'prod-eks-cluster';
const ACCOUNT = '123456789012';
const REGION = 'ap-south-1';

function readiness(hpaReady: boolean): CapabilitiesDto['readiness'] {
  return Object.fromEntries(
    READINESS_KEYS.map((key) => [
      key,
      key === 'hpa-apply'
        ? { ready: hpaReady, reason: hpaReady ? null : 'CONTROLLER_NOT_READY', message: hpaReady ? null : 'off' }
        : { ready: false, reason: 'CONTROLLER_NOT_READY', message: 'off' },
    ]),
  );
}

function capabilities({ readWrite, hpaReady }: { readWrite: boolean; hpaReady: boolean }): CapabilitiesDto {
  const connection = { accessLevel: readWrite ? 'READ_WRITE' : 'READ_ONLY', status: 'CONNECTED' } as const;
  const writeGate = { allowed: readWrite, reasons: readWrite ? [] : ['READ_ONLY_CONNECTION'] };
  return {
    schemaVersion: 1,
    accountId: ACCOUNT,
    generatedAt: new Date().toISOString(),
    connection,
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate,
    readiness: readiness(hpaReady),
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

const engine: VerticalEngineResponseWire['engine'] = {
  enabled: true,
  ready: true,
  clusterAccess: 'CONFIGURED',
  executionPath: 'DIRECT',
  executionPathDetail: {
    direct: { available: true, reason: null },
    agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
  },
  metricsSource: { reachable: true, name: 'prometheus-proxy' },
  lastCycle: { at: '2026-10-13T10:00:00Z', evaluated: 1, error: null },
};

const phpApache = (): VerticalWorkloadWire => ({
  ref: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' },
  policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
  replicas: 5,
  hpa: { present: true, name: 'php-apache' },
  gitopsManaged: false,
  skipReason: null,
  findings: [],
  containers: [
    {
      name: 'php-apache',
      qosClass: 'Burstable',
      current: { cpuRequestM: 200, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
      recommended: null,
      reasons: [],
      upstreamVpa: { cpuRequestM: 203, memRequestMi: 250, source: 'vpa', observedAt: null },
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
});

const okRead = (): HpaReadResult => ({
  kind: 'ok',
  view: adaptHpaPage({ engine, gateReasons: [], workloads: [phpApache()], writeGatesChecked: true }),
});

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <K8sCapabilitiesProvider accountId={ACCOUNT}>{children}</K8sCapabilitiesProvider>
);

async function hook(dto: CapabilitiesDto) {
  getCapabilities.mockResolvedValue(dto);
  fetchHpaPage.mockResolvedValue(okRead());
  const rendered = renderHook(() => useHpaAutomation({ clusterName: CLUSTER, accountId: ACCOUNT, region: REGION }), {
    wrapper: Wrapper,
  });
  // Wait for the one read to settle so result.kind === 'ok'.
  await waitFor(() => expect(rendered.result.current.result?.kind).toBe('ok'));
  return rendered;
}

describe('useHpaAutomation — pre-submit guard reads can(hpa.apply) (FEAT-002)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    fetchHpaPage.mockReset();
    applyHpaRecommendation.mockReset();
    applyHpaRecommendation.mockResolvedValue({ kind: 'submitted', correlationId: 'cid', state: 'ACCEPTED', dryRun: true, applied: null });
  });
  afterEach(() => vi.restoreAllMocks());

  it('read-only / unpermitted: apply() returns null WITHOUT calling applyHpaRecommendation', async () => {
    const rendered = await hook(capabilities({ readWrite: false, hpaReady: true }));
    const workload = (rendered.result.current.result as { kind: 'ok'; view: { hpaWorkloads: unknown[] } }).view.hpaWorkloads[0];
    let reply: unknown;
    await act(async () => {
      reply = await rendered.result.current.apply(workload as never, true);
    });
    expect(reply).toBeNull();
    expect(applyHpaRecommendation).not.toHaveBeenCalled();
  });

  it('read-write + hpa-apply ready: apply() proceeds and calls applyHpaRecommendation', async () => {
    const rendered = await hook(capabilities({ readWrite: true, hpaReady: true }));
    const workload = (rendered.result.current.result as { kind: 'ok'; view: { hpaWorkloads: unknown[] } }).view.hpaWorkloads[0];
    await act(async () => {
      await rendered.result.current.apply(workload as never, true);
    });
    expect(applyHpaRecommendation).toHaveBeenCalledTimes(1);
  });
});
