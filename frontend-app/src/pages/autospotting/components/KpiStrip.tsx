import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import { MoneyFigure } from './MoneyLabel';

/** Sum of the table's Est./mo column: priced rows only. Unpriced rows are counted, never added as 0. */
export function summarizeSavings(rows: GroupRow[]): { total: number | null; unpriced: number } {
  let total = 0;
  let priced = 0;
  let unpriced = 0;
  for (const r of rows) {
    if (r.savingsUnknown) unpriced += 1;
    else if (r.monthlySavings !== null && r.monthlySavings !== undefined) {
      total += r.monthlySavings;
      priced += 1;
    }
  }
  return { total: priced > 0 ? total : null, unpriced };
}

interface KpiStripProps {
  rows: GroupRow[];
  partial: boolean;
  /** `accruingNotFinal` of the open month from the ledger read service; "Not tracked" while it cannot be read. */
  accruingNotFinal?: number | null;
}

/** Three figures only, each with its label (design-ux-ops.md §2.2, FR-UI-05). */
export const KpiStrip: React.FC<KpiStripProps> = ({ rows, partial, accruingNotFinal }) => {
  const { total, unpriced } = summarizeSavings(rows);
  const suffix = partial ? ' (partial)' : '';
  return (
    <dl className="flex flex-wrap items-baseline gap-x-8 gap-y-2 text-sm text-black">
      <div>
        <dt className="text-gray-600">{`Est. monthly savings${suffix}`}</dt>
        <dd className="text-lg font-semibold">
          <MoneyFigure amount={total} label="Estimate" />
          {unpriced > 0 && (
            <span className="ml-2 text-xs font-normal text-gray-600">{`${unpriced} ${unpriced === 1 ? 'group' : 'groups'} not priced`}</span>
          )}
        </dd>
      </div>
      <div>
        <dt className="text-gray-600">Accruing MTD (not final)</dt>
        <dd className="text-lg font-semibold">
          {accruingNotFinal === null || accruingNotFinal === undefined ? (
            'Not tracked'
          ) : (
            <MoneyFigure amount={accruingNotFinal} label="Accruing (not final)" />
          )}
        </dd>
      </div>
      <div className="ml-auto">
        <dt className="text-gray-600">{`Groups${suffix}`}</dt>
        <dd className="text-lg font-semibold">{rows.length}</dd>
      </div>
    </dl>
  );
};

export default KpiStrip;
