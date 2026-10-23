import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import RootCausePanel from '../RootCausePanel';
import { RCA_DEPENDENCIES_NOTE, RCA_EMPTY_STATE } from '../../rcaCopy';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const json = (status: number, body: unknown): Response =>
  ({
    status,
    ok: status >= 200 && status < 300,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  }) as unknown as Response;

const CAUSE = {
  kind: 'REPEATED_FAILED',
  title: 'Repeated apply failure',
  confidence: 0.85,
  workload: { namespace: 'prod-sim', kind: 'Deployment', name: 'throttled-encoder' },
  occurrences: 5,
  windowHours: 24,
  remediation: '5 FAILED applies in 24h on prod-sim/throttled-encoder. Check the executor.',
  evidence: [
    { at: '2026-10-18T00:00:00Z', outcome: 'FAILED', detail: 'node reported insufficient cpu', correlationId: 'c1' },
  ],
};

const DEPENDENCIES = [
  { kind: 'OOM_EVICTION', needs: 'Kubernetes Warning events persisted server-side' },
  { kind: 'HPA_FLAP', needs: 'persisted HPA replica-change history' },
];

const body = (causes: unknown[], actionRows = 5) => ({
  generatedAt: '2026-10-18T00:00:00Z',
  windowHours: 24,
  analyzed: { actionRows, workloads: 1 },
  causes,
  dependencies: DEPENDENCIES,
});

const panel = () =>
  render(<RootCausePanel clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" />);

describe('RootCausePanel (FEAT-002)', () => {
  beforeEach(() => mockedFetch.mockReset());
  afterEach(() => vi.restoreAllMocks());

  it('renders each cause with its kind chip, score, workload, remediation and evidence', async () => {
    mockedFetch.mockResolvedValue(json(200, body([CAUSE])));
    const user = userEvent.setup();
    panel();

    await waitFor(() => expect(document.querySelector('[data-rca-cause]')).not.toBeNull());

    const card = document.querySelector<HTMLElement>('[data-rca-cause="REPEATED_FAILED"]')!;
    expect(within(card).getByText('Repeated apply failure')).toBeInTheDocument();
    expect(within(card).getByText('0.85 · strong evidence')).toBeInTheDocument();
    expect(within(card).getByText('prod-sim/throttled-encoder')).toBeInTheDocument();
    expect(card).toHaveTextContent('5 FAILED applies in 24h');

    // The evidence is disclosed on demand.
    await user.click(within(card).getByText(/recorded occurrences/i));
    expect(card).toHaveTextContent('node reported insufficient cpu');
  });

  it('summarises counts by kind in a KpiCard row', async () => {
    mockedFetch.mockResolvedValue(json(200, body([CAUSE, { ...CAUSE, workload: { ...CAUSE.workload, name: 'other' } }])));
    panel();
    await waitFor(() => expect(document.querySelector('[data-rca-kpis]')).not.toBeNull());
    const kpi = document.querySelector('[data-rca-kpis] [data-metric-cell]')!;
    expect(kpi).toHaveTextContent('Repeated apply failure');
    expect(kpi).toHaveTextContent('2');
  });

  it('shows the honest empty state and the dependencies note when there are no causes', async () => {
    mockedFetch.mockResolvedValue(json(200, body([], 0)));
    panel();
    expect(await screen.findByText(RCA_EMPTY_STATE)).toBeInTheDocument();
    expect(screen.getByText(RCA_DEPENDENCIES_NOTE)).toBeInTheDocument();
    expect(document.querySelector('[data-rca-cause]')).toBeNull();
  });

  it('renders a read failure with Retry on a refusal', async () => {
    mockedFetch.mockResolvedValue(json(403, { code: 'K8S_CLUSTER_UNREACHABLE', message: 'The API server did not answer.' }));
    panel();
    await waitFor(() => expect(document.querySelector('[data-read-failure]')).not.toBeNull());
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(document.querySelector('[data-rca-cause]')).toBeNull();
  });

  it('renders the unregistered-cluster state as a read failure, not an empty success', async () => {
    mockedFetch.mockResolvedValue(json(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not here.' }));
    panel();
    await waitFor(() => expect(document.querySelector('[data-read-failure="cluster-not-registered"]')).not.toBeNull());
    expect(screen.queryByText(RCA_EMPTY_STATE)).toBeNull();
  });
});
