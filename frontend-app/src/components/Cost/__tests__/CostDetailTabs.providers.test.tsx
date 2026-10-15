import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { ServiceCharge, TopResources } from '../../../api/curInsightsApi';
import CostDetailTabs from '../CostDetailTabs';
import { renderWithQuery } from './renderWithQuery';

/**
 * The card's props default to the AWS endpoints, which is what lets `CostPage` configure it in one
 * line — and is also the hazard: a provider page that omits a prop silently gets the AWS endpoint,
 * where a GCP project id or an Azure subscription id 404s and empties every panel.
 *
 * <p>These are the regression guard for that. Table-driven over the two provider shapes, with the
 * AWS shape alongside so the first two cannot be satisfied by deleting the defaults.
 */

const CHARGES: ServiceCharge[] = [
  {
    service: 'Amazon Simple Storage Service',
    region: 'ap-south-1',
    description: 'storage',
    usageType: 'APS3-TimedStorage-ByteHrs',
    unit: 'GB-Mo',
    quantity: 100,
    cost: 1180.25,
  },
];

const RESOURCES: TopResources = {
  resources: [
    {
      resourceId: 'arn:aws:s3:::xammer-prod-assets',
      name: 'xammer-prod-assets',
      service: 'Amazon Simple Storage Service',
      region: 'ap-south-1',
      instanceType: '',
      cost: 1180.25,
    },
  ],
  totalCost: 1180.25,
  groupCount: 1,
};

/** Opens the one service in the tree, which is what makes a resource query run at all. */
const openTheService = async () => {
  await userEvent.click(
    await screen.findByRole('button', { name: 'Amazon Simple Storage Service' }),
  );
};

describe.each([
  ['GCP', 'gcp-'],
  ['Azure', 'azure-'],
])('%s-shaped props', (_provider, prefix) => {
  it('keeps the account-wide resource path, renders no basis toggle, and reads its own ledger', async () => {
    const accountWide = vi.fn().mockResolvedValue(RESOURCES);
    const scoped = vi.fn();
    const ledger = vi.fn().mockResolvedValue([{ chargeType: 'Regular', cost: 1180.25 }]);

    renderWithQuery(
      <CostDetailTabs
        accountId="project-or-subscription"
        insightPrefix={prefix}
        chargesFetcher={() => Promise.resolve(CHARGES)}
        resourcesFetcher={accountWide}
        // The four props both provider pages pass.
        capabilitiesFetcher={null}
        chargeTypesFetcher={ledger}
        scopedResourcesFetcher={null}
        labels={false}
      />,
    );

    await screen.findByText('Amazon Simple Storage Service');
    expect(screen.queryByRole('group', { name: 'Cost basis' })).not.toBeInTheDocument();

    await openTheService();
    await waitFor(() => expect(accountWide).toHaveBeenCalled());
    expect(scoped).not.toHaveBeenCalled();

    await userEvent.click(
      screen.getByRole('button', { name: /Reconcile this total by charge type/ }),
    );
    await waitFor(() => expect(ledger).toHaveBeenCalled());
  });

  it('renders no Connectivity tab on the per-resource panel', async () => {
    /*
     * The tab is AWS-only: Azure has no topology endpoint at all, and GCP has a graph but no batch
     * per-resource cost query and no identifier reconciler. A tab strip entry that can only
     * apologise is worse than no entry, and both pages pass `connectivity={null}` for the same
     * reason they already pass `labels={false}`.
     */
    renderWithQuery(
      <CostDetailTabs
        accountId="project-or-subscription"
        insightPrefix={prefix}
        chargesFetcher={() => Promise.resolve(CHARGES)}
        resourcesFetcher={() => Promise.resolve(RESOURCES)}
        detailFetcher={() => Promise.resolve(null)}
        capabilitiesFetcher={null}
        chargeTypesFetcher={null}
        scopedResourcesFetcher={null}
        connectivity={null}
        security={null}
        labels={false}
      />,
    );
    await screen.findByText('Amazon Simple Storage Service');
    await openTheService();
    await userEvent.click(
      await screen.findByRole('button', { name: /xammer-prod-assets/ }),
    );

    // Scoped to the drawer's own strip: the card above it has a tablist too, and this assertion
    // is about the drawer's three-versus-five.
    const strip = await screen.findByRole('tablist', { name: 'Resource detail' });
    expect(within(strip).getAllByRole('tab').map((tab) => tab.textContent))
      .toEqual(['Change', 'Breakdown', 'Trend']);
    expect(screen.queryByRole('tab', { name: 'Connectivity' })).not.toBeInTheDocument();
    // Security & Alerts is omitted for the same reason, since both pages pass `security={null}`.
    expect(screen.queryByRole('tab', { name: 'Security & Alerts' })).not.toBeInTheDocument();
  });
});

describe('AWS-shaped props', () => {
  it('takes the scoped resource path from the defaults, as CostPage does', async () => {
    // CostPage passes `<CostDetailTabs accountId={curAccountId} />` and nothing else, so the
    // defaults are the AWS wiring. This is what stops the two tests above being satisfied by
    // removing them.
    const scoped = vi.fn().mockResolvedValue(RESOURCES);
    const accountWide = vi.fn().mockResolvedValue(RESOURCES);

    renderWithQuery(
      <CostDetailTabs
        accountId="123456789012"
        chargesFetcher={() => Promise.resolve(CHARGES)}
        // Only the two queries this test measures are stubbed; everything else takes its default.
        scopedResourcesFetcher={scoped}
        resourcesFetcher={accountWide}
        capabilitiesFetcher={() =>
          Promise.resolve({
            amortizedAvailable: true,
            amortizedMissingColumns: [],
            unitAvailable: true,
            amortizedDetail: '',
            unitDetail: '',
          })
        }
        chargeTypesFetcher={() => Promise.resolve([])}
        labels={false}
      />,
    );

    await screen.findByText('Amazon Simple Storage Service');
    // The basis toggle renders here, which is the other half of the provider difference.
    expect(screen.getByRole('group', { name: 'Cost basis' })).toBeInTheDocument();

    await openTheService();
    await waitFor(() =>
      expect(scoped).toHaveBeenCalledWith(
        '123456789012',
        'Amazon Simple Storage Service',
        expect.any(String),
        'unblended',
      ),
    );
    expect(accountWide).not.toHaveBeenCalled();
  });

  it('renders the Security & Alerts tab from the defaults, after Connectivity', async () => {
    // CostPage takes the defaults, so both Connectivity and Security & Alerts render. The order
    // matters: Security & Alerts is the fifth tab, placed immediately after Connectivity.
    renderWithQuery(
      <MemoryRouter>
        <CostDetailTabs
          accountId="123456789012"
          chargesFetcher={() => Promise.resolve(CHARGES)}
          // AWS takes the scoped resource path by default; stub it so the resource row renders
          // without a real network read (the account-wide fetcher is unused on this path).
          scopedResourcesFetcher={() => Promise.resolve(RESOURCES)}
          resourcesFetcher={() => Promise.resolve(RESOURCES)}
          detailFetcher={() => Promise.resolve(null)}
          // Connectivity would otherwise fire a topology read on mount; the Security tab is the
          // subject here and needs no stub (its body only mounts once selected).
          connectivity={null}
          capabilitiesFetcher={null}
          chargeTypesFetcher={() => Promise.resolve([])}
          labels={false}
        />
      </MemoryRouter>,
    );
    await screen.findByText('Amazon Simple Storage Service');
    await openTheService();
    await userEvent.click(await screen.findByRole('button', { name: /xammer-prod-assets/ }));

    const strip = await screen.findByRole('tablist', { name: 'Resource detail' });
    expect(within(strip).getAllByRole('tab').map((tab) => tab.textContent))
      .toEqual(['Change', 'Breakdown', 'Trend', 'Security & Alerts']);
  });
});
