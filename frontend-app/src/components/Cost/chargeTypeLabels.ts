/**
 * What each AWS line item type is, in words a reader who does not already know the term can use.
 *
 * <p>The report records charge types as codes — `DiscountedUsage`, `SavingsPlanNegation` — and the
 * reconciliation ledger exists precisely for the reader who is trying to work out why their bill
 * does not match a dashboard. Showing them the codes would answer that question in the vocabulary
 * that caused it.
 *
 * <p>No `.tsx`: this file holds a record, one helper and a const tuple, and not one JSX element.
 */

export interface ChargeTypeLabel {
  label: string;
  /** One clause explaining what the charge is, for the reader who has not met the term. */
  detail: string;
}

export const AWS_CHARGE_TYPE_LABELS: Record<string, ChargeTypeLabel> = {
  Usage: { label: 'Usage', detail: 'What you used, at the rate you pay for it' },
  DiscountedUsage: {
    label: 'Reserved usage',
    detail: "Usage covered by a Reserved Instance, charged at the reservation's rate",
  },
  SavingsPlanCoveredUsage: {
    label: 'Savings Plan usage',
    detail: "Usage covered by a Savings Plan, charged at the plan's rate",
  },
  SavingsPlanRecurringFee: {
    label: 'Savings Plan fee',
    detail: "The plan's hourly commitment, charged whether or not it was used",
  },
  SavingsPlanUpfrontFee: {
    label: 'Savings Plan upfront',
    detail: 'The part of a plan paid for in advance',
  },
  SavingsPlanNegation: {
    label: 'Savings Plan offset',
    detail: 'Removes the on-demand charge the plan already paid for, so it is not billed twice',
  },
  RIFee: {
    label: 'Reservation fee',
    detail: "The reservation's recurring charge, charged whether or not it was used",
  },
  Tax: { label: 'Tax', detail: 'Tax on the charges above' },
  Credit: { label: 'Credit', detail: 'A credit applied to this bill' },
  Refund: { label: 'Refund', detail: 'Money returned to you' },
  Fee: {
    label: 'Fee',
    detail: 'A one-off charge — a Marketplace subscription or an upfront purchase',
  },
  /*
   * Observed, not guessed. On the account this was verified against, `FlatRateSubscription` is
   * $1,964 of a $3,367 month — 58% of the bill — and without a row here it rendered as its own
   * raw code with no explanation, which is the single worst place on the page for jargon.
   *
   * It is absent from the AWS column reference's enumerated list of lineItem/LineItemType values,
   * which is why it was missed: the map was built from that list. The fallthrough did its job and
   * kept the ledger correct and complete; it just could not make the largest line legible.
   */
  FlatRateSubscription: {
    label: 'Subscription',
    detail: 'A fixed fee for a subscription, charged at a flat rate rather than per unit used',
  },
  /*
   * Documented by AWS as a type whose own name varies and has to be read from the line
   * description, so this detail points the reader at the row rather than naming a mechanism that
   * may not be theirs.
   */
  Discount: {
    label: 'Discount',
    detail: 'A discount AWS applied to this usage — the billed line says which',
  },
  SppDiscount: {
    label: 'Partner discount',
    detail: 'A Solution Provider Program discount applied through your partner',
  },
  BundledDiscount: {
    label: 'Bundled discount',
    detail: 'A discount earned by using one service alongside another',
  },
  EdpDiscount: {
    label: 'Enterprise discount',
    detail: 'Your negotiated enterprise agreement discount',
  },
  PrivateRateDiscount: {
    label: 'Private rate discount',
    detail: 'A negotiated rate applied below the public one',
  },
  Support: { label: 'Support', detail: "Your support plan's charge for the month" },
};

/**
 * The readable label and one-line explanation for a code.
 *
 * Falls through to `{ label: code, detail: '' }` for anything unmapped — a charge type AWS adds
 * next month renders as its own code rather than as a blank row or a guess. Overrides merge over
 * the AWS defaults rather than replacing the map, so a provider that renames two codes keeps the
 * other thirteen.
 *
 * One helper, not two: it returns the whole record, so a separate `chargeTypeDetail(code)` would be
 * `chargeTypeLabel(code).detail` with a second override-merge to keep in step. Callers that want
 * only the label read `.label`.
 */
export const chargeTypeLabel = (
  code: string,
  overrides?: Record<string, ChargeTypeLabel>,
): ChargeTypeLabel =>
  ({ ...AWS_CHARGE_TYPE_LABELS, ...overrides })[code] ?? { label: code, detail: '' };

/**
 * The line item types the amortized basis zeroes, so the card can say which it is not listing.
 *
 * Mirrors `CurAdvancedMetricsService.AMORTIZED_ZEROED_TYPES`. Deliberately a client-side constant
 * rather than a field on the payload: it is a property of the amortized expression, not a
 * measurement of a month, and sending a constant over HTTP invites a reader of the payload to
 * believe it was measured. Both copies are pinned by tests — the Java one against the CASE it comes
 * from, this one against the literal four strings.
 */
export const AMORTIZED_ZEROED_TYPES = [
  'RIFee',
  'SavingsPlanRecurringFee',
  'SavingsPlanUpfrontFee',
  'SavingsPlanNegation',
] as const;

/**
 * Those four types as readable labels, joined into a list a sentence can carry.
 *
 * Shared by the ledger's explanation and the invoice tree's amortized note, so the two cannot
 * describe different sets of charge types — and so a fifth zeroed type needs no copy edit.
 */
export const amortizedZeroedLabels = (
  overrides?: Record<string, ChargeTypeLabel>,
): string[] => AMORTIZED_ZEROED_TYPES.map((code) => chargeTypeLabel(code, overrides).label);
