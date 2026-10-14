import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
});
