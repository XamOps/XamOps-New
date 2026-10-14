import { describe, it, expect } from 'vitest';
import {
  AMORTIZED_ZEROED_TYPES,
  AWS_CHARGE_TYPE_LABELS,
  amortizedZeroedLabels,
  chargeTypeLabel,
} from '../chargeTypeLabels';

/**
 * The charge-type vocabulary. Its job is to never show a reader a raw billing code, and to never
 * show them a blank row either — the reconciliation ledger exists for someone trying to work out
 * why their bill does not match, and answering in codes would be answering in the vocabulary that
 * caused the question.
 */
describe('chargeTypeLabel', () => {
  it('gives every AWS code both a label and an explanation', () => {
    for (const [code, entry] of Object.entries(AWS_CHARGE_TYPE_LABELS)) {
      expect(entry.label, `${code} has no label`).not.toBe('');
      // The detail is the half a reader who has not met the term needs; a mapped code with an
      // empty one would render as a bare label and lose the point of the map.
      expect(entry.detail, `${code} has no detail`).not.toBe('');
    }
  });

  it('renders an unmapped code as itself rather than as a blank row or a guess', () => {
    // A charge type AWS adds next month must still appear on the ledger, or the total stops
    // reconciling with no explanation on screen.
    expect(chargeTypeLabel('BundledDiscountAlt')).toEqual({
      label: 'BundledDiscountAlt',
      detail: '',
    });
  });

  it('merges overrides over the defaults rather than replacing the map', () => {
    const overrides = { Tax: { label: 'GST', detail: 'Indian goods and services tax' } };

    // A provider that renames two codes keeps the other thirteen.
    expect(chargeTypeLabel('Tax', overrides).label).toBe('GST');
    expect(chargeTypeLabel('Usage', overrides).label).toBe('Usage');
  });

  /**
   * Every charge type seen on a real report, pinned.
   *
   * <p>The map was first built from the AWS column reference's enumerated list, and that list is
   * not complete: verifying against account 605134457560's October 2026 report turned up
   * `FlatRateSubscription` carrying $1,964 of a $3,367 month — the largest single line on the bill,
   * rendering as a raw code because it is documented nowhere in that enumeration. These are the
   * codes observed or documented since, so the same omission cannot come back silently.
   */
  it('explains every charge type observed on a real report', () => {
    for (const code of [
      'FlatRateSubscription',
      'Usage',
      'SavingsPlanCoveredUsage',
      'SavingsPlanNegation',
      'Discount',
      'SppDiscount',
    ]) {
      // Mapped, rather than merely rendering: the fallthrough returns the code with an empty
      // detail, so an absent entry is indistinguishable from a present one by label alone —
      // `Usage` is legitimately labelled "Usage". The explanation is what proves it was mapped.
      expect(AWS_CHARGE_TYPE_LABELS, `${code} is not mapped`).toHaveProperty(code);
      expect(chargeTypeLabel(code).detail, `${code} has no explanation`).not.toBe('');
    }
  });
});

describe('AMORTIZED_ZEROED_TYPES', () => {
  it('is exactly the four types the amortized expression zeroes', () => {
    // The TypeScript counterpart to the Java test that pins the same four names against
    // amortizedCostExpr's own CASE. Two tests rather than a wire field, because the thing that can
    // actually drift is the expression — and a wire field would not catch that either.
    expect([...AMORTIZED_ZEROED_TYPES]).toEqual([
      'RIFee',
      'SavingsPlanRecurringFee',
      'SavingsPlanUpfrontFee',
      'SavingsPlanNegation',
    ]);
  });

  it('resolves every one of them to a readable label', () => {
    // The amortized sentences name all four, so an unmapped one would put a raw billing code in
    // front of a reader mid-sentence.
    const labels = amortizedZeroedLabels();
    expect(labels).toHaveLength(AMORTIZED_ZEROED_TYPES.length);
    for (const [index, code] of AMORTIZED_ZEROED_TYPES.entries()) {
      expect(labels[index]).not.toBe(code);
      expect(labels[index]).not.toBe('');
    }
    expect(labels).toEqual([
      'Reservation fee',
      'Savings Plan fee',
      'Savings Plan upfront',
      'Savings Plan offset',
    ]);
  });
});
