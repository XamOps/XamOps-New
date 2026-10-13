import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { CONTRACT_BLOCK_MESSAGE } from '../../../../../api/verticalRecommendationsAdapter';
import { DEMO_READINESS_MESSAGES, demoCapabilities } from '../../../../../demo/k8sAutomationMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import { OVERLAY_TEXT } from '../../../../CloudK8s/shared/AccessBanner';
import { NOT_TURNED_ON_TEXT, OverviewPanel, TODAY_ROWS, WORKLOAD_AUTOSCALER_LINK } from '../OverviewPanel';

const READ_ONLY_TEXT = 'Read-only connection. Automation changes need read-write access.';
const DEMO_TEXT = "Demo workspace. Changes aren't saved.";

function fixture(accountId: 'demo-aws-001' | 'demo-aws-002', edit?: (dto: CapabilitiesDto) => void): CapabilitiesDto {
  const dto = demoCapabilities(accountId)!;
  edit?.(dto);
  return dto;
}

async function renderPanel(dto: CapabilitiesDto, cluster = 'prod-eks-cluster', workloadAutoscalerHref?: string) {
  getCapabilities.mockResolvedValue(dto);
  render(
    <MemoryRouter initialEntries={[`/eks-details?name=${cluster}&tab=automation&sub=overview`]}>
      <K8sCapabilitiesProvider accountId={dto.accountId} demo>
        <OverviewPanel clusterName={cluster} workloadAutoscalerHref={workloadAutoscalerHref} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  // The Access block replaces "Checking automation access…" once capabilities load.
  await screen.findAllByText(/^(Read-write|Read-only)$/);
  return screen.getByRole('table', { name: 'What automation can do on this cluster today' });
}

const rowOf = (table: HTMLElement, id: string) => {
  const row = table.querySelector<HTMLElement>(`[data-row="${id}"]`);
  if (!row) throw new Error(`no row ${id}`);
  return row;
};

/** The Access block's value for one term. */
const accessValue = (term: string) => {
  const dt = [...document.querySelectorAll('dt')].find((el) => el.textContent === term);
  if (!dt?.nextElementSibling) throw new Error(`no access row ${term}`);
  return dt.nextElementSibling as HTMLElement;
};

describe('Automation › Overview (§9.1)', () => {
  beforeEach(() => getCapabilities.mockReset());
  afterEach(() => vi.restoreAllMocks());

  it('lists every §9.1 capability in order', async () => {
    const table = await renderPanel(fixture('demo-aws-001'));
    const labels = [...table.querySelectorAll('tbody th')].map((th) => th.textContent);
    expect(labels).toEqual(TODAY_ROWS.map((r) => r.label));
  });

  it('read-write fixture: each row carries its readiness reason, and nothing else blocks it', async () => {
    const table = await renderPanel(fixture('demo-aws-001'));

    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).toHaveTextContent('Recommendations');
    // NIT 8: the workload row reads can('vertical.apply')'s primary message, the contract block.
    expect(workload).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);

    expect(rowOf(table, 'node-consolidation')).toHaveTextContent(DEMO_READINESS_MESSAGES['evictor-live']);
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent('Analysis not yet reliable');
    expect(rowOf(table, 'node-optimizer')).toHaveTextContent(DEMO_READINESS_MESSAGES['node-optimizer-actions']);
    expect(rowOf(table, 'rebalancing')).toHaveTextContent(DEMO_READINESS_MESSAGES.rebalancer);
    expect(rowOf(table, 'hpa-manager')).toHaveTextContent(DEMO_READINESS_MESSAGES['hpa-apply']);
    expect(rowOf(table, 'scale-out')).toHaveTextContent(DEMO_READINESS_MESSAGES['scale-out']);
    expect(rowOf(table, 'node-templates')).toHaveTextContent(DEMO_READINESS_MESSAGES['node-templates']);
    expect(rowOf(table, 'vpa-baseline')).toHaveTextContent(DEMO_READINESS_MESSAGES['vpa-apply']);
    // No readiness key: only the demo session holds it.
    expect(rowOf(table, 'spot-interruption')).toHaveTextContent(DEMO_TEXT);

    expect(within(table).queryByText(/Also needs/)).toBeNull();
    expect(within(table).queryByText(READ_ONLY_TEXT)).toBeNull();
    // Every blocked cell is marked by a lock as well as its words (Iconify draws it after mount).
    await waitFor(() => expect(table.querySelectorAll('[data-gated-lock]')).toHaveLength(TODAY_ROWS.length));

    expect(accessValue('Connection')).toHaveTextContent('Read-write');
    expect(accessValue('Your permission')).toHaveTextContent('You can change automation');
    expect(accessValue('XamOps agent')).toHaveTextContent('Connected');
    expect(accessValue('XamOps agent')).toHaveTextContent('data 4 min old');

    const overlays = [...document.querySelectorAll('[data-overlay]')].map((el) => el.getAttribute('data-overlay'));
    expect(overlays).toEqual(['demo']);
  });

  it('read-only fixture: the read-only banner, and the connection named wherever it blocks', async () => {
    const user = userEvent.setup();
    const table = await renderPanel(fixture('demo-aws-002'));

    expect(screen.getByText(OVERLAY_TEXT['read-only'])).toBeInTheDocument();

    const connection = accessValue('Connection');
    expect(connection).toHaveTextContent('Read-only');
    expect(connection).toHaveTextContent(READ_ONLY_TEXT);
    const toggle = within(connection).getByRole('button', { name: 'Get read-write access' });
    const explainer = document.getElementById(toggle.getAttribute('aria-controls')!)!;
    // Collapsed for real: no display class that would override the hidden attribute.
    expect(explainer).toHaveAttribute('hidden');
    expect(explainer).toHaveClass('hidden');
    expect(explainer).not.toHaveClass('block');
    await user.click(toggle);
    expect(explainer).not.toHaveAttribute('hidden');
    expect(explainer).toHaveClass('block');
    expect(within(connection).getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/tickets');

    // Readiness ranks above the connection, so it stays primary and the connection is listed after it.
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent(DEMO_READINESS_MESSAGES['evictor-live']);
    expect(rowOf(table, 'node-consolidation')).toHaveTextContent('Also needs: read-write access');
    expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    expect(rowOf(table, 'workload-rightsizing')).toHaveTextContent('Also needs: read-write access');
    // Where nothing ranks higher, the connection is the reason.
    expect(rowOf(table, 'spot-interruption')).toHaveTextContent(READ_ONLY_TEXT);
    expect(rowOf(table, 'spot-interruption')).not.toHaveTextContent(DEMO_TEXT);
  });

  it('reads "Not turned on in this environment" for workloads while the vertical engine is off (V7)', async () => {
    const table = await renderPanel(fixture('demo-aws-001', (dto) => { dto.verticalEngine.enabled = false; }));
    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).toHaveTextContent(NOT_TURNED_ON_TEXT);
    expect(workload).not.toHaveTextContent('Recommendations');
    expect(workload).not.toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
  });

  it('points the workloads row at the hosted Workload autoscaler, not the contract block (review S2)', async () => {
    const href = '/eks-details?name=prod-eks-cluster&tab=automation&sub=workload-autoscaling';
    const table = await renderPanel(fixture('demo-aws-001'), 'prod-eks-cluster', href);
    const workload = rowOf(table, 'workload-rightsizing');
    expect(workload).not.toHaveTextContent(CONTRACT_BLOCK_MESSAGE);
    expect(workload).toHaveTextContent('Decided per workload in Automation › Workload autoscaler');
    expect(within(workload).getByRole('link', { name: WORKLOAD_AUTOSCALER_LINK })).toHaveAttribute('href', href);
    // The node rows keep their own capability.
    expect(rowOf(table, 'node-consolidation').querySelector('a')).toBeNull();
  });

  it.each([
    ['TAB_GRANT', 'View only', 'Ask your XamOps administrator to allow automation changes for your user.'],
    ['ROLE', "View only: your role can't change automation", null],
    ['IMPERSONATION', 'View only while signed in as another user', null],
  ])('names the %s permission state', async (detail, label, remedy) => {
    await renderPanel(fixture('demo-aws-001', (dto) => {
      dto.user = { canChangeAutomation: false, reasons: ['NO_USER_PERMISSION'], detail };
    }));
    const permission = accessValue('Your permission');
    expect(permission).toHaveTextContent(label);
    if (remedy) expect(permission).toHaveTextContent(remedy);
  });

  it('shows a cluster capabilities does not list as not monitored, with the setup path', async () => {
    await renderPanel(fixture('demo-aws-001'), 'staging-eks-cluster');
    const agent = accessValue('XamOps agent');
    expect(agent).toHaveTextContent('Not installed');
    expect(within(agent).getByRole('link', { name: 'Set up monitoring' })).toBeInTheDocument();
  });

  it('makes no automation-agent or vertical-engine request', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    await renderPanel(fixture('demo-aws-001'));
    expect(getCapabilities).toHaveBeenCalledTimes(1);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  describe('v3 re-skin (FEAT-003)', () => {
    it('adds the Now banner, the How-it-works flow and the Workload-autoscaler feature mark', async () => {
      await renderPanel(fixture('demo-aws-001'));
      expect(document.querySelector('[data-now-banner]')).not.toBeNull();
      expect(screen.getByText('How the Workload autoscaler works')).toBeInTheDocument();
      // The feature accent reaches the page only through FeatureMark, paired with its name.
      const marks = [...document.querySelectorAll('[data-feature="workload"]')];
      expect(marks.length).toBeGreaterThan(0);
    });

    it('shows the honest "couldn\'t read workloads" clause, since this page reads no endpoint', async () => {
      await renderPanel(fixture('demo-aws-001'));
      const banner = document.querySelector('[data-now-banner]')!;
      // No endpoint-1 read on this page, so a count is never invented.
      expect(banner.textContent).toContain("couldn't read its workloads");
      expect(banner.textContent).not.toMatch(/\$\d|\/mo/);
    });
  });
});
