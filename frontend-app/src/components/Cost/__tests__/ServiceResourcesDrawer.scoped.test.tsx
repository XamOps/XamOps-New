import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResourceCost, TopResources } from '../../../api/curInsightsApi';
import ServiceResourcesDrawer from '../ServiceResourcesDrawer';
import { renderWithQuery } from './renderWithQuery';

/**
 * The resource panel's two paths. On the scoped path the query asked about one service, so an empty
 * result is a statement about that service; on the account-wide fallback an empty result usually
 * means the service fell outside the costliest 500 and the panel has to say so. Those two claims
 * must never be swapped, which is most of what is pinned here.
 *
 * <p>`labels={false}` throughout: the label controls are react-query hooks of their own and are not
 * what these tests are about. The panel renders through a portal into document.body, so everything
 * is queried through `screen`.
 */

const resource = (over: Partial<ResourceCost> = {}): ResourceCost => ({
  resourceId: 'arn:aws:s3:::xammer-prod-assets',
  name: 'xammer-prod-assets',
  service: 'Amazon Simple Storage Service',
  region: 'ap-south-1',
  instanceType: '',
  cost: 1180.25,
  ...over,
});

const S3: TopResources = {
  resources: [
    resource(),
    resource({
      resourceId: 'arn:aws:s3:::xammer-prod-logs',
      name: 'xammer-prod-logs',
      cost: 540,
    }),
    resource({ resourceId: '', name: '', region: 'Global', cost: 200 }),
  ],
  totalCost: 1920.25,
  groupCount: 3,
};

const render = (
  props: Partial<React.ComponentProps<typeof ServiceResourcesDrawer>> = {},
) =>
  renderWithQuery(
    <ServiceResourcesDrawer
      selection={{ service: 'Amazon Simple Storage Service', cost: 1920.25 }}
      accountId="123456789012"
      period="2026-08"
      monthLabel="August 2026"
      isCurrentMonth={false}
      onClose={vi.fn()}
      labels={false}
      insightName="test-resources"
      {...props}
    />,
  );

describe('ServiceResourcesDrawer — scoped path', () => {
  it('calls the scoped fetcher with the clicked service, and never the account-wide one', async () => {
    const scopedFetcher = vi.fn().mockResolvedValue(S3);
    const fetcher = vi.fn().mockResolvedValue(S3);
    render({ scopedFetcher, fetcher, basis: 'amortized' });

    await waitFor(() =>
      expect(scopedFetcher).toHaveBeenCalledWith(
        '123456789012',
        'Amazon Simple Storage Service',
        '2026-08',
        'amortized',
      ),
    );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('passes service="Other" through verbatim — the inverse mapping is the backend\'s job', async () => {
    const scopedFetcher = vi.fn().mockResolvedValue({
      resources: [resource({ service: 'Other', cost: 12 })],
      totalCost: 12,
      groupCount: 1,
    });
    render({
      scopedFetcher,
      selection: { service: 'Other', cost: 12 },
    });

    await waitFor(() =>
      expect(scopedFetcher).toHaveBeenCalledWith('123456789012', 'Other', '2026-08', undefined),
    );
  });

  it('keeps the account-wide fetcher and the client-side filter when there is no scoped one', async () => {
    // The fallback GCP and Azure use. The account-wide payload carries every service, so the
    // client-side filter is load-bearing here.
    const fetcher = vi.fn().mockResolvedValue({
      resources: [
        resource(),
        resource({ resourceId: 'i-0abc', name: 'prod-web-01', service: 'Amazon EC2', cost: 999 }),
      ],
      totalCost: 2179.25,
      groupCount: 2,
    });
    render({ fetcher, scopedFetcher: null });

    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    await screen.findByText('xammer-prod-assets');
    expect(screen.queryByText('prod-web-01')).not.toBeInTheDocument();
  });

  it('does not apologise about a cap on the scoped path when the service is simply empty', async () => {
    // The endpoint asked about this service specifically, so an empty result means the service
    // genuinely has no resource-level rows. The costliest-N sentence would be false here.
    render({
      scopedFetcher: vi.fn().mockResolvedValue({ resources: [], totalCost: 0, groupCount: 0 }),
    });

    await screen.findByText(/No resource-level cost for Amazon Simple Storage Service/);
    expect(screen.queryByText(/costliest/)).not.toBeInTheDocument();
    expect(screen.getByText(/Either nothing was billed/)).toBeInTheDocument();
  });

  it('keeps the apology on the fallback path, where it is true', async () => {
    // Regression guard: deleting it from the scoped path must not delete it from the path where a
    // service genuinely can fall outside the account-wide cap.
    render({
      scopedFetcher: null,
      fetcher: vi.fn().mockResolvedValue({
        resources: [resource({ service: 'Amazon EC2', cost: 999 })],
        totalCost: 18420.75,
        groupCount: 214,
      }),
    });

    await screen.findByText(/costliest/);
    expect(screen.getByText(/none of this service's fall inside that/)).toBeInTheDocument();
  });

  it('states the per-service truncation exactly, from the payload rather than a guess', async () => {
    render({
      scopedFetcher: vi.fn().mockResolvedValue({
        resources: [resource({ cost: 1000 })],
        // The unpartitioned windows describe the whole service, so both of these are exact.
        totalCost: 1812,
        groupCount: 3412,
      }),
      selection: { service: 'Amazon Simple Storage Service', cost: 1812 },
    });

    await screen.findByText(/Showing the 1 costliest of 3,412 resources billed to this service/);
    // 1812 - 1000, from the payload's own total rather than from the rows on screen.
    expect(screen.getByText(/The rest account for \$812\.00/)).toBeInTheDocument();

    // And nothing else explains the same $812 differently. The sub-cent wording is true only when
    // every group is on screen; beside a truncation note it contradicts the sentence above it.
    expect(screen.queryByText(/tenth of a cent/)).not.toBeInTheDocument();
    expect(screen.queryByText(/The rows above account for/)).not.toBeInTheDocument();
  });

  it('reports a failed scoped read as "cannot answer", not as "this service has nothing"', async () => {
    // The scoped empty state is a much stronger claim than the fallback's, and it must never be
    // made about a request that failed — which is what a 422 from the basis guard produces.
    render({ scopedFetcher: vi.fn().mockResolvedValue(null) });

    await screen.findByText(/No Cost and Usage Report to read for August 2026/);
    expect(
      screen.queryByText(/No resource-level cost for Amazon Simple Storage Service/),
    ).not.toBeInTheDocument();
  });

  it('names the sub-cent mechanism for a scoped gap rather than blaming unattributed spend', async () => {
    // On the scoped path the resource-less charges are listed in their own block and *are* counted
    // in the rows' total, so "spend the source attributes to no resource" would be wrong.
    render({
      scopedFetcher: vi.fn().mockResolvedValue(S3),
      selection: { service: 'Amazon Simple Storage Service', cost: 1921.5 },
    });

    await screen.findByText(/net to less than a tenth of a cent/);
    expect(
      screen.queryByText(/spend the billing source attributes to no resource/),
    ).not.toBeInTheDocument();
  });

  it('issues one query per service and none on reopening the first', async () => {
    const scopedFetcher = vi.fn((_a: string, service: string) =>
      Promise.resolve({
        resources: [resource({ service })],
        totalCost: 10,
        groupCount: 1,
      }),
    );
    const view = render({ scopedFetcher });
    await waitFor(() => expect(scopedFetcher).toHaveBeenCalledTimes(1));

    const open = (service: string) =>
      view.rerender(
        <ServiceResourcesDrawer
          selection={{ service, cost: 10 }}
          accountId="123456789012"
          period="2026-08"
          monthLabel="August 2026"
          isCurrentMonth={false}
          onClose={vi.fn()}
          labels={false}
          insightName="test-resources"
          scopedFetcher={scopedFetcher}
        />,
      );

    // The service is in the key on this path, so a second service is a second query — a scoped
    // query under the account-wide key would serve the first service's rows for every service
    // after it.
    open('AWS Lambda');
    await waitFor(() => expect(scopedFetcher).toHaveBeenCalledTimes(2));

    open('Amazon Simple Storage Service');
    await waitFor(() => expect(scopedFetcher).toHaveBeenCalledTimes(2));
  });

  it('keeps the filters, the resource-less block and the headline figure on a scoped payload', async () => {
    const many: TopResources = {
      resources: [
        ...Array.from({ length: 12 }, (_, i) =>
          resource({
            resourceId: `arn:aws:s3:::bucket-${i}`,
            name: `bucket-${i}`,
            region: i % 2 === 0 ? 'ap-south-1' : 'us-east-1',
            cost: 100 - i,
          }),
        ),
        resource({ resourceId: '', name: '', region: 'Global', cost: 50 }),
      ],
      totalCost: 1184,
      groupCount: 13,
    };
    render({
      scopedFetcher: vi.fn().mockResolvedValue(many),
      selection: { service: 'Amazon Simple Storage Service', cost: 1184 },
    });

    await screen.findByText('bucket-0');
    // The header keeps the figure the reader clicked, not the payload's own total at a different
    // grain — the number that raised the question must not disappear on arrival.
    expect(screen.getByText('$1,184.00')).toBeInTheDocument();
    // Charges with no resource, listed so the panel reconciles against its header.
    expect(screen.getByText('Charges with no resource')).toBeInTheDocument();

    // Both controls appear once the list is longer than a page.
    await userEvent.selectOptions(screen.getByLabelText('Region'), 'us-east-1');
    expect(screen.queryByText('bucket-0')).not.toBeInTheDocument();
    expect(screen.getByText('bucket-1')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Region'), 'all');
    await userEvent.type(
      screen.getByPlaceholderText(/Find a resource by name/),
      'bucket-11',
    );
    await waitFor(() => expect(screen.getByText('bucket-11')).toBeInTheDocument());
    expect(screen.queryByText('bucket-0')).not.toBeInTheDocument();
  });

  it('hands the basis and the clicked row figure to the per-resource panel', async () => {
    render({
      scopedFetcher: vi.fn().mockResolvedValue(S3),
      basis: 'amortized',
      detailFetcher: vi.fn().mockResolvedValue({
        resourceId: 'arn:aws:s3:::xammer-prod-assets',
        name: 'xammer-prod-assets',
        service: 'Amazon Simple Storage Service',
        region: 'ap-south-1',
        instanceType: '',
        priorCost: 900,
        currentCost: 1000,
        costChange: 100,
        priorQuantity: 90,
        currentQuantity: 100,
        priceEffect: 0,
        volumeEffect: 100,
        comparisonAvailable: true,
        excludedCost: 0,
        lines: [],
        daily: [],
      }),
    });

    await userEvent.click(await screen.findByText('xammer-prod-assets'));

    // The panel stays on unblended cost whatever this one is on, so it says so — naming the row's
    // amortized figure rather than leaving the reader to find the gap by subtracting.
    await screen.findByText(/The row you opened reads \$1,180\.25 on the amortized basis/);
  });
});
