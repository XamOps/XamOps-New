import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import type { ResourceChange } from '../../../api/curInsightsApi';
import ResourceCostDrawer from '../ResourceCostDrawer';
import { renderWithQuery } from './renderWithQuery';

/**
 * The per-resource panel's one concession to the cost basis: it says what basis the row that opened
 * it was on, and does not re-base its own figures. Its arithmetic only means anything on unblended
 * cost — cost ÷ quantity is a real unit rate there and an accounting artefact on an amortized
 * figure over an on-demand quantity.
 */

const change = (over: Partial<ResourceChange> = {}): ResourceChange => ({
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
  ...over,
});

const render = (
  props: Partial<React.ComponentProps<typeof ResourceCostDrawer>> = {},
  payload: ResourceChange = change(),
) =>
  renderWithQuery(
    <ResourceCostDrawer
      resourceId="arn:aws:s3:::xammer-prod-assets"
      accountId="123456789012"
      period="2026-08"
      onClose={vi.fn()}
      fetcher={() => Promise.resolve(payload)}
      insightName="test-detail"
      {...props}
    />,
  );

describe('ResourceCostDrawer — cost basis', () => {
  it('names the row figure when the row was priced on the amortized basis', async () => {
    render({ basis: 'amortized', rowCost: 1180.25 });

    await screen.findByText(/The row you opened reads \$1,180\.25 on the amortized basis/);
    expect(screen.getByText(/only unblended cost divided by quantity is a real unit rate/))
      .toBeInTheDocument();
  });

  it.each([
    ['no basis at all', {}],
    ['the unblended basis', { basis: 'unblended' as const, rowCost: 1180.25 }],
    // The early return. The sentence interpolates rowCost, and "the row you opened reads $NaN" is
    // worse than silence.
    ['an amortized basis with no row figure', { basis: 'amortized' as const }],
  ])('says nothing on %s', async (_case, props) => {
    render(props);

    await screen.findByText('xammer-prod-assets');
    expect(screen.queryByText(/The row you opened reads/)).not.toBeInTheDocument();
  });

  it('renders the excluded-spend note instead, never alongside it', async () => {
    /*
     * On the amortized basis a commitment-covered resource is exactly the case where excludedCost
     * is large, so the two notes would stack — two paragraphs in the identical treatment about the
     * same gap. One replaces the other and folds the figure in, so no fact is lost.
     */
    const withExcluded = change({ excludedCost: 420 });

    const unblended = render({}, withExcluded);
    await screen.findByText(/billed in total/);
    expect(screen.queryByText(/The row you opened reads/)).not.toBeInTheDocument();
    unblended.unmount();

    render({ basis: 'amortized', rowCost: 1420 }, withExcluded);
    await screen.findByText(/The row you opened reads \$1,420\.00 on the amortized basis/);
    // The excluded figure is named inside the basis note rather than in a second one.
    expect(screen.getByText(/the other \$420\.00/)).toBeInTheDocument();
    expect(screen.queryByText(/billed in total/)).not.toBeInTheDocument();
  });
});
