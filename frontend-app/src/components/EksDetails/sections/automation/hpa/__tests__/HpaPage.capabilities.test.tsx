import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import { K8sCapabilitiesProvider } from '../../../../../../hooks/useK8sCapabilities';
import { READINESS_KEYS, type CapabilitiesDto } from '../../../../../../types/k8sAutomation';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';
import HpaPage from '../HpaPage';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const CLUSTER = 'prod-eks-cluster';
const ACCOUNT = '123456789012';
const REGION = 'ap-south-1';
const NOT_ENABLED_NOTE = 'Applying is off in this environment';

// ── Capabilities fixtures ────────────────────────────────────────────────────

/** All eight readiness keys, with hpa-apply controllable (FEAT-001 derives it from the connection). */
function readiness(hpaReady: boolean): CapabilitiesDto['readiness'] {
  return Object.fromEntries(
    READINESS_KEYS.map((key) => [
      key,
      key === 'hpa-apply'
        ? { ready: hpaReady, reason: hpaReady ? null : 'CONTROLLER_NOT_READY', message: hpaReady ? null : 'Automated HPA changes are disabled until the HPA manager passes safety validation.' }
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

// ── Endpoint-1 read fixture (one CHANGE HPA, every write gate checked and passing) ────

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

const json = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => JSON.stringify(body) }) as unknown as Response;

/**
 * Serves endpoint 1 (the HPA read reuses the vertical endpoint); anything else fails the test.
 * `gateReasons` mirrors what the server sends on this connection: a read-only connection carries
 * READ_ONLY_CONNECTION, so the Apply gate names it specifically instead of the readiness note.
 */
function serveRead(gateReasons: string[] = []) {
  mockedFetch.mockImplementation(async (url: string) => {
    if (!String(url).startsWith('/api/eks/automation/vertical/')) throw new Error(`unexpected request ${url}`);
    return json(200, { engine, gateReasons, workloads: [phpApache()], writeGatesChecked: true });
  });
}

const READ_ONLY_REASON = 'View only: this AWS account is connected read-only. Applying changes needs read-write access.';

async function renderPage(dto: CapabilitiesDto, search = '') {
  getCapabilities.mockResolvedValue(dto);
  const view = render(
    <MemoryRouter initialEntries={[`/eks-details?name=${CLUSTER}&tab=automation&sub=hpa${search}`]}>
      <K8sCapabilitiesProvider accountId={ACCOUNT}>
        <HpaPage clusterName={CLUSTER} accountId={ACCOUNT} region={REGION} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  // The Apply affordance for php-apache mounts only after endpoint 1 and capabilities resolve.
  await screen.findByRole('button', { name: 'Apply php-apache' });
  return view;
}

const applyButton = () => screen.getByRole('button', { name: 'Apply php-apache' });

describe('Automation › HPA — one source of truth: can(hpa.apply) (FEAT-002)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    mockedFetch.mockReset();
    serveRead();
  });
  afterEach(() => vi.restoreAllMocks());

  describe('list surface', () => {
    it('read-write + hpa-apply ready: Apply is ENABLED and the "off in this environment" note is ABSENT', async () => {
      await renderPage(capabilities({ readWrite: true, hpaReady: true }));
      expect(applyButton()).toBeEnabled();
      expect(screen.queryByText(NOT_ENABLED_NOTE)).toBeNull();
      // The header "Changes" cell matches the button: it can be applied.
      const changes = screen.getByText('Changes').closest('div')!;
      expect(changes).toHaveTextContent('Can be applied');
    });

    it('read-only connection: Apply is DISABLED with the read-only reason, not the readiness note', async () => {
      serveRead(['READ_ONLY_CONNECTION']);
      await renderPage(capabilities({ readWrite: false, hpaReady: true }));
      expect(applyButton()).toBeDisabled();
      // The write gate ranks above readiness, so the connection is the specific reason shown.
      expect(screen.getByText(READ_ONLY_REASON)).toBeInTheDocument();
      expect(screen.queryByText(NOT_ENABLED_NOTE)).toBeNull();
    });

    it('read-write but hpa-apply NOT ready: Apply is DISABLED with the "off in this environment" note, and Changes reads Suggestions only', async () => {
      await renderPage(capabilities({ readWrite: true, hpaReady: false }));
      expect(applyButton()).toBeDisabled();
      expect(screen.getByText(NOT_ENABLED_NOTE)).toBeInTheDocument();
      const changes = screen.getByText('Changes').closest('div')!;
      expect(changes).toHaveTextContent('Suggestions only');
    });
  });

  describe('detail surface (?hpa=)', () => {
    const detail = '&hpa=team-b%2Fphp-apache';

    it('read-write + ready: Apply ENABLED and the note ABSENT on the detail page too', async () => {
      await renderPage(capabilities({ readWrite: true, hpaReady: true }), detail);
      await waitFor(() => expect(document.querySelector('[data-hpa-detail]')).not.toBeNull());
      expect(applyButton()).toBeEnabled();
      expect(screen.queryByText(NOT_ENABLED_NOTE)).toBeNull();
    });

    it('read-only: Apply DISABLED with the correct reason on the detail page too', async () => {
      serveRead(['READ_ONLY_CONNECTION']);
      await renderPage(capabilities({ readWrite: false, hpaReady: true }), detail);
      await waitFor(() => expect(document.querySelector('[data-hpa-detail]')).not.toBeNull());
      expect(applyButton()).toBeDisabled();
      expect(screen.getByText(READ_ONLY_REASON)).toBeInTheDocument();
    });
  });
});
