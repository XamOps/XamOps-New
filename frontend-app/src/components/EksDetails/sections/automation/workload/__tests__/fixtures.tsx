/**
 * Shared fixtures for the Workload autoscaler page tests: endpoint 1 as the backend serialises it,
 * the apply replies the executor sends (applied, deferred and put back, refused), and a router
 * harness that shows the location, so a test can assert what the page did to the URL.
 */
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router-dom';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import type { VerticalEngineResponseWire, VerticalResourcesWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';

export const CLUSTER = 'xamops-test-1';
export const ACCOUNT = '102227938259';
export const REGION = 'ap-south-1';
export const PAGE = `/eks-details?name=${CLUSTER}&accountIds=${ACCOUNT}&region=${REGION}&tab=automation&sub=workload-autoscaler`;
export const WORKLOADS_HREF = PAGE;
export const POLICIES_HREF = `/eks-details?name=${CLUSTER}&accountIds=${ACCOUNT}&region=${REGION}&tab=automation&sub=workload-autoscaler-policies`;

export const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
export const res = (cpuRequestM: number | null, memRequestMi: number | null, cpuLimitM: number | null = null, memLimitMi: number | null = null): VerticalResourcesWire =>
  ({ cpuRequestM, memRequestMi, cpuLimitM, memLimitMi });

export function wire(name: string, namespace: string, over: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire {
  return {
    ref: { namespace, kind: 'Deployment', name },
    policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: false,
    skipReason: null,
    findings: [],
    containers: [],
    lastAction: null,
    autoApplyEligible: false,
    provenance: { percentile: 95, windowHours: 24, windowStart: null, windowEnd: null, sampleCoverage: 0.94, usageSource: 'prometheus-proxy', policySource: 'DEFAULTS' },
    horizontal: null,
    ...over,
  };
}

export const OVERPROVISIONED = wire('overprovisioned-api', 'team-a', {
  containers: [{ name: 'api', qosClass: 'Burstable', current: res(1000, 1024, 1000, 1024), recommended: res(25, 256, 25, 256), reasons: ['LIMIT_MOVED'], upstreamVpa: null }],
});
export const THROTTLED = wire('throttled-encoder', 'prod-sim', {
  findings: ['THROTTLED'],
  containers: [{ name: 'encoder', qosClass: 'Burstable', current: res(10, 64, 50, 128), recommended: res(63, 64, 200, 128), reasons: ['DEMAND_CENSORED_BY_LIMIT'], upstreamVpa: null }],
});
export const IDLE = wire('idle-batch-listener', 'dev-sim', {
  replicas: 3,
  containers: [{ name: 'sleeper', qosClass: 'Burstable', current: res(25, 128, 50, 256), recommended: null, reasons: ['COOLDOWN'], upstreamVpa: null }],
  lastAction: { at: iso(17 * 60_000), dryRun: false, outcome: 'APPLIED' },
});
export const SOLO = wire('solo', 'team-b', {
  replicas: 1,
  skipReason: 'SINGLE_REPLICA',
  containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: null }],
});

export function endpointOne(workloads: VerticalWorkloadWire[] = [OVERPROVISIONED, THROTTLED, IDLE, SOLO], over: Partial<VerticalEngineResponseWire> = {}): VerticalEngineResponseWire {
  return {
    engine: {
      enabled: true,
      ready: true,
      clusterAccess: 'CONFIGURED',
      executionPath: 'DIRECT',
      executionPathDetail: { direct: { available: true, reason: null }, agent: null },
      metricsSource: { reachable: true, name: 'prometheus-proxy' },
      lastCycle: { at: iso(5 * 60_000), evaluated: workloads.length, error: null },
    },
    gateReasons: [],
    gateDetails: {},
    writeGatesChecked: true,
    workloads,
    ...over,
  };
}

const pod = (name: string, before: VerticalResourcesWire, after: VerticalResourcesWire, extra: Record<string, unknown> = {}) => ({
  podName: name,
  uidBefore: `${name}-uid`,
  uidAfter: `${name}-uid`,
  samePod: true,
  ready: true,
  containers: [{
    name: 'api', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: 0,
    observedBefore: before, observedAfter: after,
  }],
  ...extra,
});

/** The executor's reply for a confirmed resize of both overprovisioned-api pods. */
export const APPLIED_REPLY = {
  correlationId: '7b8d4cda-0000-4000-8000-000000000000',
  state: 'ACCEPTED',
  dryRun: false,
  result: {
    method: 'IN_PLACE',
    terminal: 'APPLIED',
    detail: null,
    restartFree: true,
    containers: [{ name: 'api', state: 'APPLIED', detail: null, requested: res(25, 256, 25, 256), restartExpected: false }],
    pods: [
      pod('overprovisioned-api-6f7d8-2bddb', res(1000, 1024, 1000, 1024), res(25, 256, 25, 256), { changed: true }),
      pod('overprovisioned-api-6f7d8-jm7jv', res(1000, 1024, 1000, 1024), res(25, 256, 25, 256), { changed: true }),
    ],
  },
};

/** throttled-encoder's deferral: the kubelet declined the test pod for CPU, and XamOps put it back. */
export const DEFERRED_REPLY = {
  correlationId: 'd3f3rr3d-0000-4000-8000-000000000000',
  state: 'ACCEPTED',
  dryRun: false,
  result: {
    method: 'IN_PLACE',
    terminal: 'DEFERRED',
    detail: 'OutOfcpu: requested 63, used 1900, capacity 1930',
    restartFree: true,
    containers: [{ name: 'encoder', state: 'DEFERRED', detail: null, requested: res(63, 64, 200, 128), restartExpected: false }],
    pods: [
      pod('throttled-encoder-8b999cc87-mcw8q', res(10, 64, 50, 128), res(10, 64, 50, 128), {
        changed: false,
        revert: { state: 'REVERTED', detail: 'XamOps put the pod back.' },
      }),
    ],
    reverted: true,
    insufficient: 'cpu',
  },
};

/** What the demo build answers for any write: refused before anything is sent. */
export const DEMO_REFUSAL = { success: false, code: 'K8S_DEMO', message: "Demo workspace. Changes aren't saved.", field: null, detail: null };

export const json = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => (body === null ? '' : JSON.stringify(body)) }) as unknown as Response;

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

/** Renders `element` at `url` under a memory router, with the current location shown. */
export type TestRouter = ReturnType<typeof createMemoryRouter>;

export function renderAt(url: string, element: ReactElement): TestRouter {
  const router = createMemoryRouter(
    [{ path: '/eks-details', element: <>{element}<LocationProbe /></> }],
    { initialEntries: [url] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

export const currentSearch = (): URLSearchParams => {
  const text = document.querySelector('[data-testid="location"]')?.textContent ?? '';
  return new URL(text, 'http://test').searchParams;
};
