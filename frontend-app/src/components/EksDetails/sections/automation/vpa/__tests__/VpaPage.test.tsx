import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import VpaPage from '../VpaPage';
import { adaptHpaPage } from '../../../../../../api/hpaAdapter';
import { adaptEngineResponse } from '../../../../../../api/verticalRightsizerAdapter';
import { fetchAutomationRead } from '../../../../../../api/automationReadApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../../../../../types/verticalRightsizer';

// The page reads endpoint 1 once for both views (useAutomationRead), so the read is mocked there.
vi.mock('../../../../../../api/automationReadApi', () => ({
  fetchAutomationRead: vi.fn(),
}));

const mockedRead = fetchAutomationRead as unknown as ReturnType<typeof vi.fn>;

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
  mockedRead.mockResolvedValue({
    kind: 'ok',
    view: { vertical: adaptEngineResponse({ writeGatesChecked: true, ...wire }), hpa: adaptHpaPage({ writeGatesChecked: true, ...wire }) },
  });

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

// ─── UI v3 (FEAT-003): one read, the per-pod comparison, the conflict chips ──────────────────

const withReasons = (name: string, reasons: string[], over: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire => ({
  ...offVpa(name),
  horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons },
  ...over,
});

describe('VpaPage — the v3 page (UI v3 plan §2 "VPA")', () => {
  it('reads endpoint 1 once for both views and says what the VPAs do', async () => {
    resolveWith({ engine, gateReasons: [], workloads: ['a', 'b'].map(offVpa) });
    renderTab();
    await screen.findByText(/Kubernetes VPA coexistence/i);
    expect(mockedRead).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent('2 analysed workloads have a Kubernetes VPA, all Off.');
    expect(document.querySelector('[data-vpa-explain]')).toHaveTextContent(
      "XamOps's own engine is the vertical autoscaler. Kubernetes VPAs here are only compared with it: XamOps never edits them, and stands down for a workload whose VPA changes pods.",
    );
    // The components aren't read yet: "Not checked", never a guess.
    expect(document.querySelector('[data-vpa-components] [data-tone="off"]')).toHaveTextContent('Not checked');
    const kpis = [...document.querySelectorAll('[data-vpa-kpis] [data-metric-cell]')].map((k) => k.textContent);
    expect(kpis[0]).toMatch(/^VPAs on analysed workloads2All in Off mode/);
    expect(kpis[1]).toMatch(/^Changing pods0/);
    expect(kpis[2]).toMatch(/^Conflicts or unreadable0/);
    expect(kpis[3]).toMatch(/^CPU: VPA agrees0 of 2/);
  });

  it('draws CPU per pod on a log scale, now, XamOps and the VPA hatched, with the numbers in its table', async () => {
    const w = offVpa('enc');
    w.containers![0].recommended = { cpuRequestM: 63, memRequestMi: 256, cpuLimitM: null, memLimitMi: null };
    w.containers![0].upstreamVpa = { cpuRequestM: 70, memRequestMi: 250, source: 'vpa', observedAt: null };
    resolveWith({ engine, gateReasons: [], workloads: [w] });
    renderTab();
    const card = (await screen.findByRole('heading', { name: 'CPU per pod: now, XamOps, Kubernetes VPA' })).closest('section')!;
    expect(card.querySelector('[data-bars]')!.getAttribute('data-bars-scale')).toBe('log');
    const row = within(card).getByRole('rowheader', { name: 'enc', hidden: true }).closest('tr')!;
    expect([...row.querySelectorAll('td')].map((td) => td.textContent)).toEqual(['100m', '63m', '70m']);
    // The legend names the hatched series beside its hatched swatch.
    expect(card.querySelector('[data-series-legend]')).toHaveTextContent('Request nowXamOps suggestsKubernetes VPA suggests');
    expect(card.querySelector('[data-series-legend] rect[fill^="url(#k8s-legend-hatch-suggested-"]')).not.toBeNull();
    // 70m against XamOps's 63m: within 20%.
    expect(document.querySelector('[data-vpa-row="prod-sim/Deployment/enc"]')).toHaveTextContent('CPU: within 20% of XamOps');
    fireEvent.click(within(card).getByRole('button', { name: 'Memory' }));
    expect(await screen.findByRole('heading', { name: 'Memory per pod: now, XamOps, Kubernetes VPA' })).toBeInTheDocument();
  });

  it('colours the conflict chips: a VPA that changes pods is bad, one XamOps cannot read warns, Off is information', async () => {
    resolveWith({
      engine,
      gateReasons: [],
      workloads: [withReasons('managed', ['VPA_MANAGED']), withReasons('blind', ['VPA_NOT_READABLE']), offVpa('quiet')],
    });
    renderTab();
    await screen.findByText(/Kubernetes VPA coexistence/i);
    const chip = (name: string) => document.querySelector(`[data-vpa-row="prod-sim/Deployment/${name}"] td:last-child [data-tone]`)!;
    expect([chip('managed').getAttribute('data-tone'), chip('managed').textContent]).toEqual(['bad', 'XamOps stands down — the VPA owns the requests']);
    expect([chip('blind').getAttribute('data-tone'), chip('blind').textContent]).toEqual(['warn', "XamOps can't read the VPA, so it leaves requests alone"]);
    expect([chip('quiet').getAttribute('data-tone'), chip('quiet').textContent]).toEqual(['info', "None — Off doesn't change pods"]);
    // Every chip carries its icon (the icon mounts after the chip), never colour alone.
    await waitFor(() => {
      for (const c of [chip('managed'), chip('blind'), chip('quiet')]) expect(c.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    });
    const kpis = [...document.querySelectorAll('[data-vpa-kpis] [data-metric-cell]')];
    // Not "Conflicts with XamOps" (VPA-managed only, on the HPA page): this card adds the unreadable ones.
    expect(kpis[2]).toHaveTextContent('Conflicts or unreadable21 a VPA changes, 1 XamOps can\'t read');
  });

  it('an unreadable VPA alone warns on the KPI card, as its chip does, and is no conflict', async () => {
    resolveWith({ engine, gateReasons: [], workloads: [withReasons('blind', ['VPA_NOT_READABLE']), offVpa('quiet')] });
    renderTab();
    await screen.findByText(/Kubernetes VPA coexistence/i);
    const chip = document.querySelector('[data-vpa-row="prod-sim/Deployment/blind"] td:last-child [data-tone]')!;
    const kpis = [...document.querySelectorAll('[data-vpa-kpis] [data-metric-cell]')];
    expect(kpis[1]).toHaveTextContent('Changing pods0');
    expect(kpis[2]).toHaveTextContent("Conflicts or unreadable10 a VPA changes, 1 XamOps can't read");
    // The conflict chip in the table still carries the warn tone (colour-for-meaning survives there).
    expect(chip.getAttribute('data-tone')).toBe('warn');
  });

  it("says a VPA has no suggestion, and why only when no pod runs to measure", async () => {
    const idle = withReasons('idle', ['VPA_MANAGED'], { replicas: 0 });
    idle.containers![0].upstreamVpa = null;
    resolveWith({ engine, gateReasons: [], workloads: [idle] });
    renderTab();
    await screen.findByText(/Kubernetes VPA coexistence/i);
    expect(document.querySelector('[data-vpa-row="prod-sim/Deployment/idle"]')).toHaveTextContent('No VPA suggestion: no running pod to measure');
  });

  it('keeps the read states: a refusal shows the gate, with no table', async () => {
    mockedRead.mockResolvedValue({ kind: 'refused', code: { kind: 'known', code: 'CLUSTER_ACCESS_NOT_CONFIGURED', label: 'x' }, message: 'No access entry.' });
    renderTab();
    expect(await screen.findByText("XamOps can't sign in to this cluster")).toBeInTheDocument();
    expect(document.querySelector('[data-now-banner]')).toHaveTextContent("XamOps couldn't read the Kubernetes VPAs just now.");
    expect(screen.queryByText(/Kubernetes VPA coexistence/i)).toBeNull();
  });
});
