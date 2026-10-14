import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  ChargeTypeAmount,
  CostBasis,
  ReportCapabilities,
  ServiceCharge,
} from '../../../api/curInsightsApi';
import ChargesByServiceSection from '../ChargesByServiceSection';
import CostDetailTabs from '../CostDetailTabs';
import { renderWithQuery } from './renderWithQuery';

/**
 * The card as a whole: the basis reaching every query that must be re-based, the two feature gates
 * staying independent, and the states where a figure must not be rendered at all.
 */

const CHARGES: ServiceCharge[] = [
  {
    service: 'Amazon Simple Storage Service',
    region: 'ap-south-1',
    description: '$0.025 per GB - first 50 TB / month of storage used',
    usageType: 'APS3-TimedStorage-ByteHrs',
    unit: 'GB-Mo',
    quantity: 47210,
    cost: 1180.25,
  },
];

const LEDGER: ChargeTypeAmount[] = [{ chargeType: 'Usage', cost: 1180.25 }];

const CAPABLE: ReportCapabilities = {
  amortizedAvailable: true,
  amortizedMissingColumns: [],
  unitAvailable: true,
  amortizedDetail: '',
  unitDetail: '',
};

const render = (props: Partial<React.ComponentProps<typeof ChargesByServiceSection>> = {}) =>
  renderWithQuery(
    <ChargesByServiceSection
      accountId="123456789012"
      fetcher={() => Promise.resolve(CHARGES)}
      insightName="test-charges"
      capabilitiesFetcher={() => Promise.resolve(CAPABLE)}
      chargeTypesFetcher={() => Promise.resolve(LEDGER)}
      resourcesFetcher={null}
      {...props}
    />,
  );

const switchToAmortized = async () => {
  const amortized = await screen.findByRole('button', { name: 'Amortized' });
  await waitFor(() => expect(amortized).not.toHaveAttribute('aria-disabled'));
  await userEvent.click(amortized);
};

describe('ChargesByServiceSection — cost basis', () => {
  it('threads the basis into both the charges query and the scoped resources query', async () => {
    const fetcher = vi.fn().mockResolvedValue(CHARGES);
    const scopedResourcesFetcher = vi
      .fn()
      .mockResolvedValue({ resources: [], totalCost: 0, groupCount: 0 });
    render({ fetcher, scopedResourcesFetcher, resourcesFetcher: undefined });

    await screen.findByText('Amazon Simple Storage Service');
    await switchToAmortized();

    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith('123456789012', expect.any(String), 'amortized'),
    );

    // Open a service, so the panel's own query runs on the same basis the tree is on.
    await userEvent.click(screen.getByRole('button', { name: 'Amazon Simple Storage Service' }));
    await waitFor(() =>
      expect(scopedResourcesFetcher).toHaveBeenCalledWith(
        '123456789012',
        'Amazon Simple Storage Service',
        expect.any(String),
        'amortized',
      ),
    );
  });

  it('gives the non-default basis its own cache key, and the default none', async () => {
    /*
     * The default basis deliberately carries no `:unblended` suffix, so CostDetailTabs' own
     * readability probe and this card's query stay one react-query entry and one request. The
     * non-default basis must have a suffix, or the 30-minute staleTime re-renders the cached
     * unblended rows under the amortized heading.
     *
     * The wire half of the same rule — `basis=unblended` never reaching the query string, which
     * would fork a second server-side cache entry — is asserted in
     * `src/api/__tests__/curInsightsApi.basis.test.ts`.
     */
    const fetcher = vi.fn().mockResolvedValue(CHARGES);
    render({ fetcher });
    await screen.findByText('Amazon Simple Storage Service');
    expect(fetcher).toHaveBeenCalledTimes(1);

    await switchToAmortized();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenLastCalledWith('123456789012', expect.any(String), 'amortized');
  });

  it('renders no toggle and runs no probe without a capabilities fetcher, but keeps the ledger', async () => {
    // The GCP and Azure shape. Two gates, not one: a single gate over both would delete the
    // reconciliation from the two providers that are the only reason it is three-provider.
    const capabilitiesFetcher = vi.fn();
    const chargeTypesFetcher = vi.fn().mockResolvedValue(LEDGER);
    render({ capabilitiesFetcher: null, chargeTypesFetcher });

    await screen.findByText('Amazon Simple Storage Service');
    expect(screen.queryByRole('group', { name: 'Cost basis' })).not.toBeInTheDocument();
    expect(capabilitiesFetcher).not.toHaveBeenCalled();

    const trigger = screen.getByRole('button', { name: /Reconcile this total by charge type/ });
    await userEvent.click(trigger);
    await waitFor(() => expect(chargeTypesFetcher).toHaveBeenCalled());
  });

  it('renders no reconciliation without a charge-types fetcher, but keeps the toggle', async () => {
    // The mirror of the test above, so neither gate can be collapsed into the other in either
    // direction.
    render({ chargeTypesFetcher: null });

    await screen.findByText('Amazon Simple Storage Service');
    expect(screen.getByRole('group', { name: 'Cost basis' })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Reconcile this total by charge type/ }),
    ).not.toBeInTheDocument();
  });

  it('replaces the unverifiable footnote with the ledger, below the tree', async () => {
    render();
    await screen.findByText('Amazon Simple Storage Service');

    expect(screen.queryByText(/reconciles to your invoice/)).not.toBeInTheDocument();
    // The second sentence of that footnote is still true and still there.
    expect(screen.getByText(/Negative amounts are credits, refunds/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Reconcile this total by charge type/ }),
    ).toBeInTheDocument();
  });

  it('carries one amortized note naming all four zeroed types and the quantity caveat', async () => {
    render();
    await screen.findByText('Amazon Simple Storage Service');
    expect(screen.queryByText(/are not listed on this basis/)).not.toBeInTheDocument();

    await switchToAmortized();

    const note = await screen.findByText(/are not listed on this basis/);
    for (const label of [
      'Reservation fee',
      'Savings Plan fee',
      'Savings Plan upfront',
      'Savings Plan offset',
    ]) {
      expect(note.textContent).toContain(label);
    }
    // One note, not two: the quantity caveat is in the same sentence as the dropped fee rows.
    expect(note.textContent).toContain('on-demand grain');
    // And the basis is in the prose, because the control itself does not print.
    expect(screen.getByText(/, amortized,/)).toBeInTheDocument();
  });

  it('keeps the card and its controls when a non-default basis cannot be read', async () => {
    // A 422 from the amortized guard, or an Athena timeout on that basis, both yield a null
    // payload. Returning null then would remove the month selector and the toggle — leaving no
    // control on screen to get back to unblended, inside a card CostDetailTabs keeps rendering.
    const fetcher = vi.fn((_a: string, _p?: string, basis?: CostBasis) =>
      Promise.resolve(basis === 'amortized' ? null : CHARGES),
    );
    render({ fetcher });

    await screen.findByText('Amazon Simple Storage Service');
    await switchToAmortized();

    await screen.findByText(/could not be read on the amortized basis/);
    expect(screen.getByRole('group', { name: 'Cost basis' })).toBeInTheDocument();
    expect(screen.getByLabelText('Billing month')).toBeInTheDocument();
    // No rows, no empty-month claim, and no $0.00 total — all three would be assertions the
    // report did not make.
    expect(screen.queryByText('Amazon Simple Storage Service')).not.toBeInTheDocument();
    expect(screen.queryByText(/No charges found for/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Total not available on this basis')).toHaveTextContent('—');
    expect(
      screen.queryByRole('button', { name: /Reconcile this total by charge type/ }),
    ).not.toBeInTheDocument();

    // Switching back recovers from the still-cached unblended entry.
    await userEvent.click(screen.getByRole('button', { name: 'Unblended' }));
    await screen.findByText('Amazon Simple Storage Service');
  });

  it('still renders nothing when the default basis cannot be read', async () => {
    // The other half of basisFailed: a reader with no readable source does not get a card with one
    // dead control in it.
    const { container } = render({ fetcher: () => Promise.resolve(null) });
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('still renders the card and its month selector for an empty month', async () => {
    // Regression guard on the existing fix: an empty month is not an unreadable source, and
    // vanishing takes away the one control that would get the reader to a month with data in it.
    render({ fetcher: () => Promise.resolve([]) });

    await screen.findByText(/No charges found for/);
    expect(screen.getByLabelText('Billing month')).toBeInTheDocument();
  });

  it('blocks amortized with "could not check" when the probe itself errors, never "Checking…"', async () => {
    /*
     * The third blocked state, and the only one that is not reachable from the toggle's own props:
     * a thrown query. The card has to resolve it to `null` — a *failed* probe — because collapsing
     * it into the pending `undefined` would leave the toggle saying "Checking…" for the life of the
     * tab after one dropped connection, with no way for the reader to tell a transient failure from
     * a report that genuinely cannot amortize.
     */
    render({ capabilitiesFetcher: () => Promise.reject(new Error('502')) });

    await screen.findByText('Amazon Simple Storage Service');
    const amortized = screen.getByRole('button', { name: 'Amortized' });

    await waitFor(() => {
      const describedBy = amortized.getAttribute('aria-describedby');
      expect(describedBy && document.getElementById(describedBy)?.textContent).toMatch(
        /could not check what this report can be asked/,
      );
    });
    expect(amortized).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByText(/Checking what this report can be asked/)).not.toBeInTheDocument();
  });

  it('explains a missing unit column once, under the table rather than per row', async () => {
    render({
      capabilitiesFetcher: () =>
        Promise.resolve({
          ...CAPABLE,
          unitAvailable: false,
          unitDetail: 'This export does not carry pricing_unit, so quantities have no unit.',
        }),
    });

    await screen.findByText('Amazon Simple Storage Service');
    expect(
      screen.getByText('This export does not carry pricing_unit, so quantities have no unit.'),
    ).toBeInTheDocument();
  });
});

describe('CostDetailTabs', () => {
  it('still renders exactly two tabs', async () => {
    // Regression guard on the decision not to make the reconciliation a third tab: the ledger is a
    // proof of the first tab's number, not a third question of the same kind.
    renderWithQuery(
      <CostDetailTabs
        accountId="123456789012"
        chargesFetcher={() => Promise.resolve(CHARGES)}
        capabilitiesFetcher={null}
        chargeTypesFetcher={null}
        scopedResourcesFetcher={null}
        resourcesFetcher={null}
      />,
    );

    await screen.findByText('Amazon Simple Storage Service');
    expect(screen.getAllByRole('tab')).toHaveLength(2);
  });
});
