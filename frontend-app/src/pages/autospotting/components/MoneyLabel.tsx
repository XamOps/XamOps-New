import React from 'react';
import { formatMoney } from '../../../utils/formatMoney';

/** The only five labels that may sit beside a money figure (design-ux-ops.md §2.4, FR-UI-05). */
export const MONEY_LABELS = [
  'Estimate',
  'Billable',
  'Not invoiced',
  'Legacy (unverified)',
  'Accruing (not final)',
] as const;
export type MoneyLabelText = (typeof MONEY_LABELS)[number];

/** A small neutral outlined tag. */
export const MoneyLabel: React.FC<{ label: MoneyLabelText }> = ({ label }) => (
  <span className="ml-2 inline-block rounded border border-[rgba(0,0,0,0.12)] bg-white px-1.5 py-0.5 align-middle font-sans text-[10px] font-semibold leading-none tracking-wide text-[rgba(0,0,0,0.6)]">
    {label}
  </span>
);

/** A figure with its label. `null` amount renders "Not priced" and no label (nothing to qualify). */
export const MoneyFigure: React.FC<{
  amount: number | null | undefined;
  currency?: string;
  label: MoneyLabelText;
  perMonth?: boolean;
}> = ({ amount, currency = 'USD', label, perMonth }) => {
  const priced = amount !== null && amount !== undefined;
  return (
    <span>
      <span>{formatMoney(amount, currency, { perMonth })}</span>
      {priced && <MoneyLabel label={label} />}
    </span>
  );
};
