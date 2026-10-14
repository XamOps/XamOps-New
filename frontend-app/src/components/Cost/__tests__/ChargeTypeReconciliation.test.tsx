import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ChargeTypeAmount, CostBasis } from '../../../api/curInsightsApi';
import ChargeTypeReconciliation from '../ChargeTypeReconciliation';
import { renderWithQuery } from './renderWithQuery';

/**
 * The ledger that replaced an unverifiable footnote. Its duties: add up to the figure above it or
 * say why not, keep credits as subtractions, cost nothing until it is opened, and never report a
 * failed read as "nothing was billed".
 */

const LEDGER: ChargeTypeAmount[] = [
  { chargeType: 'Usage', cost: 15980.75 },
  { chargeType: 'DiscountedUsage', cost: 1240 },
  { chargeType: 'Tax', cost: 620 },
  { chargeType: 'Credit', cost: -250 },
  { chargeType: 'Refund', cost: -150 },
];

const INVOICE_TOTAL = 17440.75;

const render = (
  props: Partial<React.ComponentProps<typeof ChargeTypeReconciliation>> = {},
) =>
  renderWithQuery(
    <ChargeTypeReconciliation
      accountId="123456789012"
      period="2026-08"
      monthLabel="August 2026"
      basis="unblended"
      invoiceTotal={INVOICE_TOTAL}
      fetcher={() => Promise.resolve(LEDGER)}
      insightName="test-charge-types"
      {...props}
    />,
  );

/** Opens the disclosure and waits for the ledger to land. */
const expand = async () => {
  await userEvent.click(screen.getByRole('button', { expanded: false }));
  await screen.findByText('Total');
};

/** The footer row, so an amount is asserted on the total rather than on whichever row matches. */
const totalRow = () => screen.getByRole('rowheader', { name: 'Total' }).closest('tr');

/**
 * The real October 2026 ledger from account 605134457560, to the cent.
 *
 * <p>Kept verbatim rather than rounded because the point of it is the 2.6 cent gap against the
 * tree's own total of $3,367.5188 — the tree drops groups under a tenth of a cent and this query
 * drops none, so the two cannot be identical and a flat one-cent tolerance called a healthy month
 * a reconciliation failure. The `SavingsPlanCoveredUsage` and `SavingsPlanNegation` pair summing to
 * exactly zero is also real, and is what a correct Savings Plan month looks like.
 */
const REAL_LEDGER: ChargeTypeAmount[] = [
  { chargeType: 'FlatRateSubscription', cost: 1964.3933 },
  { chargeType: 'Usage', cost: 1403.1519 },
  { chargeType: 'SavingsPlanCoveredUsage', cost: 0.9354 },
  { chargeType: 'SavingsPlanNegation', cost: -0.9354 },
];
const REAL_INVOICE_TOTAL = 3367.5188;

describe('ChargeTypeReconciliation', () => {
  it('does not fetch until it is expanded', async () => {
    // A reader who only reads the invoice pays for one Athena scan, not two.
    //
    // Note for anyone changing this component: the trigger has to render while its own query is
    // disabled. With a null accountId react-query reports isPending for the whole collapsed
    // lifetime, so gating the trigger on that flag makes the disclosure permanently unopenable and
    // this test fails on its first assertion.
    const fetcher = vi.fn().mockResolvedValue(LEDGER);
    render({ fetcher });

    const trigger = screen.getByRole('button', { expanded: false });
    expect(fetcher).toHaveBeenCalledTimes(0);

    await userEvent.click(trigger);
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    // The panel the trigger controls is the one that holds the ledger.
    expect(trigger.getAttribute('aria-controls')).toBeTruthy();
  });

  it('says it is reading while the scan runs, and claims no failure until one happens', async () => {
    /*
     * The query does not start until the click, so a pending read flattened into "no rows" makes
     * the failure sentence the first thing every expand produces — announced for the length of an
     * Athena scan and then withdrawn. The in-flight window is its own fact and says so.
     */
    render({ fetcher: () => new Promise<ChargeTypeAmount[]>(() => {}) });

    await userEvent.click(screen.getByRole('button', { expanded: false }));

    await screen.findByText(/Reading the charge type split for August 2026/);
    expect(screen.queryByText(/could not be read/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Nothing was billed/)).not.toBeInTheDocument();
    expect(screen.queryByText('Total')).not.toBeInTheDocument();
  });

  it('groups by the sign of the amount, each group ordered by magnitude', async () => {
    render();
    await expand();

    // scope="rowgroup", not colgroup: the heading heads a group of rows, and the wrong axis makes a
    // screen reader announce "Tax" under a column heading of "Credits and adjustments".
    expect(screen.getByText('Charges')).toHaveAttribute('scope', 'rowgroup');
    expect(screen.getByText('Credits and adjustments')).toHaveAttribute('scope', 'rowgroup');

    const rows = screen.getAllByRole('rowheader').map((cell) => cell.textContent ?? '');
    const labels = rows.filter(
      (text) => !['Total', 'Charges', 'Credits and adjustments'].includes(text),
    );
    // Sign, not a static per-code sign: a reversal can flip one, and a hard-coded sign would file
    // a positive Credit under subtractions and get the arithmetic backwards.
    expect(labels[0]).toContain('Usage');
    expect(labels[1]).toContain('Reserved usage');
    expect(labels[2]).toContain('Tax');
    expect(labels[3]).toContain('Credit');
    expect(labels[4]).toContain('Refund');
  });

  it('omits a zero row, which invites the reader to look for meaning in it', async () => {
    render({
      fetcher: () =>
        Promise.resolve([
          { chargeType: 'Usage', cost: 100 },
          { chargeType: 'BundledDiscount', cost: 0 },
        ]),
      invoiceTotal: 100,
    });
    await expand();

    expect(screen.queryByText(/Bundled discount/)).not.toBeInTheDocument();
  });

  it('says it matches when the rows sum to the figure above it', async () => {
    render();
    await expand();

    expect(screen.getByText(/Matches the/)).toBeInTheDocument();
  });

  /**
   * The real month, which must read as reconciled.
   *
   * <p>This is the case a flat one-cent tolerance got wrong. Both figures are correct and the
   * month is healthy; they differ by 2.6 cents only because the tree filters sub-cent groups out
   * and this ledger does not. Reporting that as a disagreement teaches the reader to distrust a
   * number that is right, which is worse than showing nothing at all.
   */
  it('reads a real month with a sub-cent filtering gap as reconciled', async () => {
    render({
      fetcher: () => Promise.resolve(REAL_LEDGER),
      invoiceTotal: REAL_INVOICE_TOTAL,
    });
    await expand();

    expect(screen.getByText(/Matches the/)).toBeInTheDocument();
    expect(screen.queryByText(/more than the/)).not.toBeInTheDocument();
    expect(screen.queryByText(/less than the/)).not.toBeInTheDocument();
  });

  it('explains the largest line on a real bill rather than printing its billing code', async () => {
    // FlatRateSubscription carried 58% of this account's month and is absent from the AWS column
    // reference, so it reached the ledger as a raw code until it was mapped.
    render({
      fetcher: () => Promise.resolve(REAL_LEDGER),
      invoiceTotal: REAL_INVOICE_TOTAL,
    });
    await expand();

    expect(screen.getByText('Subscription')).toBeInTheDocument();
    expect(screen.queryByText('FlatRateSubscription')).not.toBeInTheDocument();
  });

  it('names the mechanism when the rows and the tree differ beyond the tolerance', async () => {
    // The mechanism is known exactly: the tree drops groups netting to under a tenth of a cent and
    // this query drops nothing, so the difference is those sub-cent groups. $1.25 is well past
    // what that can account for on a $17,440 month, where the tolerance allows 87 cents.
    render({ invoiceTotal: INVOICE_TOTAL - 1.25 });
    await expand();

    expect(screen.getByText(/\$1\.25 more than the \$17,439\.50 above/)).toBeInTheDocument();
    expect(screen.getByText(/netting to less than a tenth of a cent/)).toBeInTheDocument();
    expect(screen.queryByText(/Matches the/)).not.toBeInTheDocument();
  });

  it('reads the difference the other way round when the ledger is the smaller figure', async () => {
    // The dropped sub-cent groups can net negative, so the difference is signed. "-$1.25 more than"
    // is not a sentence: the preposition carries the sign and the figure is a magnitude.
    render({ invoiceTotal: INVOICE_TOTAL + 1.25 });
    await expand();

    expect(screen.getByText(/\$1\.25 less than the \$17,442\.00 above/)).toBeInTheDocument();
    expect(screen.queryByText(/-\$1\.25 more than/)).not.toBeInTheDocument();
  });

  it('renders a credit-only month as a negative total, with no Charges group', async () => {
    render({
      fetcher: () => Promise.resolve([{ chargeType: 'Credit', cost: -250 }]),
      invoiceTotal: -250,
    });
    await expand();

    expect(screen.queryByText('Charges')).not.toBeInTheDocument();
    expect(screen.getByText('Credits and adjustments')).toBeInTheDocument();
    // No Math.abs anywhere in this arithmetic: a negative total must render as one.
    expect(totalRow()).toHaveTextContent('-$250.00');
  });

  it('reports a failed read as a failure, not as an empty month', async () => {
    render({ fetcher: () => Promise.resolve(null) });

    await userEvent.click(screen.getByRole('button', { expanded: false }));
    await screen.findByText(/could not be read for August 2026/);

    expect(screen.queryByText('Total')).not.toBeInTheDocument();
    expect(screen.queryByText(/Nothing was billed/)).not.toBeInTheDocument();
  });

  it('renders its trigger on an empty month, and the empty copy once opened', async () => {
    // The footnote this replaced was hidden on an empty month, which hid it exactly when "was
    // anything billed at all?" is the question an empty tree raises.
    render({ fetcher: () => Promise.resolve([]), invoiceTotal: 0 });

    const trigger = screen.getByRole('button', { expanded: false });
    await userEvent.click(trigger);

    await screen.findByText(/Nothing was billed in this month/);
  });

  it('renders nothing at all when there is no fetcher', () => {
    const { container } = render({ fetcher: null });
    expect(container).toBeEmptyDOMElement();
  });

  it('names all four zeroed fee types on the amortized basis, and lists none of them', async () => {
    render({
      basis: 'amortized',
      fetcher: () =>
        Promise.resolve([
          { chargeType: 'Usage', cost: 100 },
          { chargeType: 'DiscountedUsage', cost: 50 },
        ]),
      invoiceTotal: 150,
    });
    await expand();

    const note = screen.getByText(/are not listed on the amortized basis/);
    for (const label of [
      'Reservation fee',
      'Savings Plan fee',
      'Savings Plan upfront',
      'Savings Plan offset',
    ]) {
      expect(note.textContent).toContain(label);
    }
    // Not listed at $0.00 — a zero whose truth is "not applicable on this basis".
    expect(screen.queryByRole('rowheader', { name: /Reservation fee/ })).not.toBeInTheDocument();
  });

  it('refetches under a different key when the basis changes, never reusing the old rows', async () => {
    // Without the basis in the query key, useCurInsight's 30-minute staleTime re-renders the
    // cached *unblended* rows under the amortized heading — the mislabelling the backend's 422
    // exists to prevent, arriving by cache instead.
    const fetcher = vi.fn((_a: string, _p?: string, basis?: CostBasis) =>
      Promise.resolve(
        basis === 'amortized'
          ? [{ chargeType: 'Usage', cost: 9000 }]
          : [{ chargeType: 'Usage', cost: 15980.75 }],
      ),
    );

    const view = render({ fetcher });
    await expand();
    expect(fetcher).toHaveBeenCalledWith('123456789012', '2026-08', 'unblended');

    view.rerender(
      <ChargeTypeReconciliation
        accountId="123456789012"
        period="2026-08"
        monthLabel="August 2026"
        basis="amortized"
        invoiceTotal={9000}
        fetcher={fetcher}
        insightName="test-charge-types"
      />,
    );

    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith('123456789012', '2026-08', 'amortized'),
    );
    await waitFor(() => expect(totalRow()).toHaveTextContent('$9,000.00'));
    expect(screen.queryByText(/\$15,980\.75/)).not.toBeInTheDocument();
  });
});
