import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getVerticalStatus } = vi.hoisted(() => ({ getCapabilities: vi.fn(), getVerticalStatus: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../../../../api/verticalRecommendationsApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/verticalRecommendationsApi')>();
  return { ...actual, getVerticalStatus };
});

import { K8sApiError } from '../../../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { useEksSectionRoute } from '../../../../../hooks/useEksSectionRoute';
import { clearVerticalCacheForTests } from '../../../../../hooks/useVerticalRecommendations';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { demoCapabilities, demoVerticalStatus } from '../../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../../demo/xamopsMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import WorkloadsPanel, { NOT_AVAILABLE, WORKLOADS_CAPTION } from '../WorkloadsPanel';

const ACCOUNT = 'demo-aws-001';
const PROD = 'prod-eks-cluster';

function Harness({ dashboard }: { dashboard: unknown }) {
  const route = useEksSectionRoute();
  return (
    <WorkloadsPanel
      clusterName={PROD} accountId={ACCOUNT} region="us-east-1"
      eksData={{ dashboard } as unknown as UseEksDashboardReturn}
      route={route} onOpenGuide={() => {}}
    />
  );
}

function renderPanel({ caps, engine = demoVerticalStatus(PROD), dashboard = demoEksDashboard }: {
  caps?: CapabilitiesDto; engine?: unknown; dashboard?: unknown;
} = {}) {
  getCapabilities.mockResolvedValue(caps ?? demoCapabilities(ACCOUNT));
  if (engine instanceof Error) getVerticalStatus.mockRejectedValue(engine);
  else getVerticalStatus.mockResolvedValue(engine);
  render(
    <MemoryRouter initialEntries={[`/eks-details?name=${PROD}&accountIds=${ACCOUNT}&tab=workloads&sub=workloads`]}>
      <K8sCapabilitiesProvider accountId={ACCOUNT} demo>
        <Harness dashboard={dashboard} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  return screen.getByRole('table', { name: WORKLOADS_CAPTION });
}

/** The workload name in a row: the first line of its first cell. */
const nameOf = (tr: Element) => tr.querySelector('td span > span')?.textContent ?? null;

/** The cell under `header` in the row of workload `name`. */
function cell(table: HTMLElement, name: string, header: string): HTMLElement {
  const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent?.trim());
  const index = headers.findIndex((h) => h?.startsWith(header));
  const row = [...table.querySelectorAll('tbody tr')].find((tr) => nameOf(tr) === name);
  if (!row || index < 0) throw new Error(`no cell ${name} / ${header}`);
  return row.querySelectorAll('td')[index] as HTMLElement;
}

beforeEach(() => {
  getCapabilities.mockReset();
  getVerticalStatus.mockReset();
  clearVerticalCacheForTests();
});
afterEach(() => vi.restoreAllMocks());

describe('Workloads › Workloads (§10.4)', () => {
  it('counts workloads by kind and lists each with requests summed from its pods', async () => {
    const table = renderPanel();
    expect(document.querySelector('[data-count="Deployment"]')).toHaveTextContent('7');
    expect(document.querySelector('[data-count="StatefulSet"]')).toHaveTextContent('2');
    expect(document.querySelector('[data-count="DaemonSet"]')).toHaveTextContent('0');
    // api-server: three pods of 256m and 512Mi.
    expect(cell(table, 'api-server', 'CPU requests')).toHaveTextContent('768m');
    expect(cell(table, 'api-server', 'Memory requests')).toHaveTextContent('1.5 GiB');
    expect(cell(table, 'api-server', 'Replicas')).toHaveTextContent('3 / 3');
    // worker-staging sets no requests: said so, never 0.
    expect(cell(table, 'worker-staging', 'CPU requests')).toHaveTextContent('Unset');
    await waitFor(() => expect(cell(table, 'api-server', 'Mode')).toHaveTextContent('Recommend'));
  });

  it('shows CPU used only where the top-20 pods cover the workload, and efficiency only with both', async () => {
    const table = renderPanel();
    // Every api-server pod is in topCpuPods: 320 + 295 + 280.
    expect(cell(table, 'api-server', 'CPU used')).toHaveTextContent('895m');
    expect(cell(table, 'api-server', 'Efficiency')).toHaveTextContent('117%');
    // frontend's pods aren't in the top 20.
    expect(cell(table, 'frontend', 'CPU used')).toHaveTextContent('—');
    expect(cell(table, 'frontend', 'Efficiency')).toHaveTextContent('—');
    await waitFor(() => expect(getVerticalStatus).toHaveBeenCalled());
  });

  it('joins the engine on namespace, kind and name, with findings and skip reasons', async () => {
    const table = renderPanel();
    // Per pod, as the engine recommends: 256m → 400m (the requests column sums all three pods).
    await waitFor(() => expect(cell(table, 'api-server', 'Recommendation')).toHaveTextContent('Per pod: CPU 256m → 400m'));
    expect(cell(table, 'api-server', 'Recommendation')).toHaveTextContent('CPU throttled');
    expect(cell(table, 'grafana', 'Recommendation')).toHaveTextContent('No change recommended');
    expect(cell(table, 'coredns', 'Recommendation')).toHaveTextContent('Skipped: locked by annotation');
    expect(cell(table, 'prometheus', 'Recommendation')).toHaveTextContent('Off · not evaluated');
    expect(cell(table, 'prometheus', 'Mode')).toHaveTextContent('Off');
    expect(cell(table, 'worker-staging', 'Recommendation')).toHaveTextContent('Per pod: CPU unset → 60m · Memory unset → 250 MiB');
    expect(cell(table, 'worker-staging', 'Recommendation')).toHaveTextContent('Requests unset');
  });

  it('says "—" for a workload the engine has no entry for', async () => {
    const engine = demoVerticalStatus(PROD)!;
    engine.workloads = engine.workloads.filter((w) => w.ref.name !== 'frontend');
    const table = renderPanel({ engine });
    await waitFor(() => expect(cell(table, 'api-server', 'Mode')).toHaveTextContent('Recommend'));
    expect(cell(table, 'frontend', 'Recommendation')).toHaveTextContent('—');
    expect(cell(table, 'frontend', 'Mode')).toHaveTextContent('—');
  });

  it.each([
    ['the engine is switched off (V7)', { caps: (() => { const c = demoCapabilities(ACCOUNT)!; c.verticalEngine.enabled = false; return c; })() }, "Recommendations aren't turned on in this environment."],
    ['the engine is not deployed (V6)', { engine: new K8sApiError({ status: 404 }) }, "The vertical rightsizer isn't available in this environment."],
  ])('says Not available when %s', async (_label, opts, line) => {
    const table = renderPanel(opts as never);
    expect(await screen.findByText(line)).toBeInTheDocument();
    expect(cell(table, 'api-server', 'Recommendation')).toHaveTextContent(NOT_AVAILABLE);
  });

  it('offers Retry when the engine read fails', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const user = userEvent.setup();
    renderPanel({ engine: new K8sApiError({ status: 500 }) });
    expect(await screen.findByText("Couldn't load recommendations.")).toBeInTheDocument();
    getVerticalStatus.mockResolvedValue(demoVerticalStatus(PROD));
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByText("Couldn't load recommendations.")).toBeNull());
  });

  it('searches and filters by kind, namespace and status', async () => {
    const user = userEvent.setup();
    const table = renderPanel();
    const names = () => [...table.querySelectorAll('tbody tr')].map(nameOf);
    await user.type(screen.getByRole('searchbox'), 'worker');
    expect(names()).toEqual(expect.arrayContaining(['worker', 'worker-staging']));
    expect(names()).toHaveLength(2);
    await user.clear(screen.getByRole('searchbox'));
    await user.click(within(screen.getByRole('group', { name: 'Kind' })).getByRole('button', { name: 'StatefulSet' }));
    expect(names().sort()).toEqual(['alertmanager', 'prometheus']);
    await waitFor(() => expect(getVerticalStatus).toHaveBeenCalled());
  });
});
