import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { installFetch, renderWithProviders, row } from './helpers';

vi.mock('../../../contexts/AccountContext', () => ({
  useAccounts: () => ({
    accounts: [
      { id: '123456789012', dbId: 7, name: 'Prod AWS', provider: 'AWS', status: 'CONNECTED' },
      { id: 'gcp-analytics', dbId: 11, name: 'GCP Analytics', provider: 'GCP', status: 'CONNECTED' },
    ],
    selectedAccountIds: ['123456789012'],
  }),
}));

import AutoSpottingPage from '../AutoSpottingPage';

afterEach(() => vi.unstubAllGlobals());

const config = {
  shellEnabled: true,
  dryRun: false,
  engagedSwitches: [],
  providers: {
    AWS: { enabled: true, conversionsEnabled: false, allowlistOnly: true, v2MutationsEnabled: false, beta: true },
    GCP: { enabled: true, conversionsEnabled: false, allowlistOnly: true, v2MutationsEnabled: false, beta: true },
  },
};

const mig = row({
  id: null,
  provider: 'GCP',
  groupKey: 'gcp-analytics/us-central1/web-mig',
  location: 'us-central1',
  name: 'web-mig',
  instanceType: 'n2-standard-4',
});

const gke = row({
  id: null,
  provider: 'GCP',
  groupKey: 'gcp-analytics/us-central1-b/gke-pool-1-grp',
  location: 'us-central1-b',
  name: 'gke-pool-1-grp',
  instanceType: 'e2-standard-4',
  enableAllowed: false,
  enableBlockedBy: 'GCP_GKE_NODE_POOL',
  blockers: [{ id: 'b-GCP_GKE_NODE_POOL', code: 'GCP_GKE_NODE_POOL', text: 'GKE manages this group.' }],
});

describe('AutoSpottingPage with GCP preselected', () => {
  it('reads the GCP project with the GCP adapter: MIG noun, machine type column and official icon', async () => {
    const { calls, fn } = installFetch([
      { match: /\/v2\/config$/, responses: [{ body: config }] },
      { match: /\/accounts\/11\/groups$/, responses: [{ body: { items: [mig, gke], partial: false, failedLocations: [], asOf: '2026-11-19T00:00:00Z' } }] },
    ]);
    const { container } = renderWithProviders(<AutoSpottingPage preselect="GCP" />, '/gcp-autospotting');
    expect(await screen.findByText('web-mig')).toBeInTheDocument();
    expect(screen.getByText('gke-pool-1-grp')).toBeInTheDocument();
    expect(screen.getByText('Machine type')).toBeInTheDocument();
    expect(screen.getByText('GCP Analytics')).toBeInTheDocument();
    expect(container.querySelector('img[src="/icons/gcp-compute-engine.png"]')).not.toBeNull();
    expect(fn).toHaveBeenCalled();
    expect(calls.some(c => /\/accounts\/7\//.test(c.url))).toBe(false);
    expect(calls.some(c => /stack-capabilities|region-coverage/.test(c.url))).toBe(false);
  });
});
