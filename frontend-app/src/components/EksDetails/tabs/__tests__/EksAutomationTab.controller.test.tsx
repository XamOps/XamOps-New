/**
 * EksAutomationTab's two optional props (UI v3 FEAT-002): `controller` lets a host page share its one
 * endpoint-1 read, and `onlyWorkloadId` shows one workload's card. Main's own cases (rendered with
 * neither prop) live unchanged in EksAutomationTab.test.tsx and verticalWriteGateContract.test.tsx.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EksAutomationTab from '../EksAutomationTab';
import { adaptEngineResponse } from '../../../../api/verticalRightsizerAdapter';
import { fetchVerticalEngine } from '../../../../api/verticalRightsizerApi';
import type { UseVerticalRightsizerState } from '../../../../hooks/useVerticalRightsizer';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../types/verticalRightsizer';

vi.mock('../../../../api/verticalRightsizerApi', () => ({
  fetchVerticalEngine: vi.fn(),
  applyVerticalRecommendation: vi.fn(),
  setVerticalWorkloadMode: vi.fn(),
  verticalReadUrl: vi.fn(),
  verticalApplyUrl: vi.fn(),
  verticalPolicyUrl: vi.fn(),
}));

const mockedRead = fetchVerticalEngine as unknown as ReturnType<typeof vi.fn>;

const workload = (name: string): VerticalWorkloadWire => ({
  ref: { namespace: 'team-a', kind: 'Deployment', name },
  policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
  replicas: 2,
  hpa: { present: false, name: null },
  gitopsManaged: false,
  skipReason: null,
  findings: [],
  containers: [{
    name: 'api', qosClass: 'Burstable',
    current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: null, memLimitMi: null },
    recommended: { cpuRequestM: 25, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
    reasons: [], upstreamVpa: null,
  }],
  lastAction: null,
  autoApplyEligible: false,
  provenance: null,
  horizontal: null,
});

const BODY: VerticalEngineResponseWire = {
  engine: {
    enabled: true, ready: true, clusterAccess: 'CONFIGURED', executionPath: 'DIRECT',
    executionPathDetail: { direct: { available: true, reason: null }, agent: null },
    metricsSource: { reachable: true, name: 'prometheus-proxy' },
    lastCycle: { at: '2026-10-14T13:00:00Z', evaluated: 2, error: null },
  },
  gateReasons: [],
  writeGatesChecked: true,
  workloads: [workload('overprovisioned-api'), workload('second-api')],
};

function controller(over: Partial<UseVerticalRightsizerState> = {}): UseVerticalRightsizerState {
  return {
    loading: false,
    result: { kind: 'ok', view: adaptEngineResponse(BODY) },
    reload: vi.fn(),
    applyingIds: new Set(),
    applyResults: {},
    modeChangingIds: new Set(),
    modeResults: {},
    callerRefusal: null,
    apply: vi.fn().mockResolvedValue(null),
    setMode: vi.fn().mockResolvedValue(null),
    ...over,
  };
}

const renderTab = (props: { controller?: UseVerticalRightsizerState; onlyWorkloadId?: string }) =>
  render(<EksAutomationTab clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" {...props} />);

describe('EksAutomationTab with a host controller (UI v3 FEAT-002)', () => {
  beforeEach(() => mockedRead.mockReset());

  it("reads nothing itself and renders the host's read, without its own Now banner or How it works", () => {
    renderTab({ controller: controller() });
    expect(mockedRead).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Apply second-api' })).toBeInTheDocument();
    expect(document.querySelector('[data-now-banner]')).toBeNull();
    expect(document.querySelector('[data-how-it-works]')).toBeNull();
    // Its own status block and test-run switch stay.
    expect(screen.getByRole('heading', { name: 'Workload rightsizing' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Test run only (nothing is sent)' })).toBeChecked();
  });

  it("applies and refreshes through the host's state", () => {
    const host = controller();
    renderTab({ controller: host });
    fireEvent.click(screen.getByRole('button', { name: 'Apply overprovisioned-api' }));
    expect(host.apply).toHaveBeenCalledWith(expect.objectContaining({ id: 'team-a/Deployment/overprovisioned-api' }), true);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(host.reload).toHaveBeenCalledTimes(1);
  });

  it("keeps the host's caller refusal greying every Apply", () => {
    renderTab({ controller: controller({ callerRefusal: { code: 'DEMO', detail: null } }) });
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Apply second-api' })).toBeDisabled();
  });

  it('shows only the one workload with onlyWorkloadId', () => {
    renderTab({ controller: controller(), onlyWorkloadId: 'team-a/Deployment/second-api' });
    expect(screen.getByRole('button', { name: 'Apply second-api' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Apply overprovisioned-api' })).toBeNull();
  });

  it("shows the host's read failure as the tab always has", () => {
    renderTab({ controller: controller({ result: { kind: 'error', message: 'HTTP 500' } }) });
    expect(screen.getByText("Couldn't load Workload rightsizing")).toBeInTheDocument();
    expect(mockedRead).not.toHaveBeenCalled();
  });

  it('with neither prop, reads endpoint 1 itself and keeps its Now banner (main unchanged)', async () => {
    mockedRead.mockResolvedValue({ kind: 'ok', view: adaptEngineResponse(BODY) });
    renderTab({});
    await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(mockedRead).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[data-now-banner]')).not.toBeNull();
  });
});
