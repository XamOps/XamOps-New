import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { UnusedCommitment } from '../../../api/curInsightsApi';

const api = vi.hoisted(() => ({
  fetchSavingsRate: vi.fn(),
  fetchUnusedCommitment: vi.fn(),
}));
vi.mock('../../../api/curInsightsApi', () => api);

import FinOpsKPISection from '../FinOpsKPISection';

/**
 * The Unused Commitment card's one rule: it reports money already paid that bought nothing, so it
 * must never render a figure the report cannot actually measure.
 *
 * <p>This section fetches with `useState`/`useEffect` rather than through react-query, so the API
 * module is mocked instead of a client being provided.
 */

const KPIS = {
  monthToDateSpend: 18420.75,
  forecastedSpend: 21000,
  lastMonthSpend: 17000,
  potentialSavings: 1200,
};

const renderSection = () =>
  render(<FinOpsKPISection kpis={KPIS} curAccountId="123456789012" />);

const card = () => screen.queryByText('Unused Commitment');

describe('FinOpsKPISection — unused commitment', () => {
  beforeEach(() => {
    api.fetchSavingsRate.mockResolvedValue(null);
    api.fetchUnusedCommitment.mockReset();
  });

  it('hides the card when the report cannot measure it and the total is zero', async () => {
    const payload: UnusedCommitment = {
      unusedRecurringFee: 0,
      unusedUpfrontFee: 0,
      total: 0,
      available: false,
    };
    api.fetchUnusedCommitment.mockResolvedValue(payload);

    renderSection();
    await waitFor(() => expect(api.fetchUnusedCommitment).toHaveBeenCalled());
    expect(card()).not.toBeInTheDocument();
  });

  it('hides the card when the report cannot measure it even with a non-zero total', async () => {
    // "Cannot say" outranks a non-zero total, which is the whole point of the flag: a trimmed
    // export produces figures that are arithmetic over substituted constants, and a card headed
    // "you wasted $120" asserts a measurement that was never made.
    const payload: UnusedCommitment = {
      unusedRecurringFee: 120,
      unusedUpfrontFee: 0,
      total: 120,
      available: false,
    };
    api.fetchUnusedCommitment.mockResolvedValue(payload);

    renderSection();
    await waitFor(() => expect(api.fetchUnusedCommitment).toHaveBeenCalled());
    expect(card()).not.toBeInTheDocument();
  });

  it('still renders the card when the flag is absent', async () => {
    /*
     * The case this actually guards is an older cached payload, written before the flag existed.
     * CurInsightCache holds entries in Redis for three days and in Postgres indefinitely, so for
     * the first days after a deploy the field legitimately will not be there — and an absent field
     * must mean "no such concept here" rather than "cannot say". That is why the guard is
     * `available !== false` and not `available === true`.
     */
    api.fetchUnusedCommitment.mockResolvedValue({
      unusedRecurringFee: 120,
      unusedUpfrontFee: 0,
      total: 120,
    } as UnusedCommitment);

    renderSection();
    await waitFor(() => expect(card()).toBeInTheDocument());
  });
});
