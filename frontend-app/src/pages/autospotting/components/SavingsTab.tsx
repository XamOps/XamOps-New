import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import { useGroupSavings } from '../../../hooks/autospotting/useLedgerSavings';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';
import { MUTED, TABLE, TD_SM, TH_SM, THEAD_ROW, TR } from './theme';

/**
 * Estimate vs the ledger's measured figure per month (plan-phase-17.md 17.4). The estimate is the plan's;
 * each month's figure is the ledger accrual, labelled "Accruing (not final)" for the open month and "Not
 * invoiced" for a closed shadow month. Invoiced amounts appear on the Statements screen, not here.
 */
export const SavingsTab: React.FC<{ row: GroupRow; accountId?: number | null }> = ({ row, accountId = null }) => {
  const months = useGroupSavings(accountId, accountId === null ? null : row.id);
  return (
    <div className="space-y-3 text-[13px]">
      <p className={MUTED}>Estimated monthly savings at the current profile or the preselected one.</p>
      <p className="text-[20px] font-semibold text-emerald-700">
        {row.savingsUnknown ? NOT_PRICED : <MoneyFigure amount={row.monthlySavings} label="Estimate" perMonth />}
      </p>
      {months.data && months.data.length > 0 ? (
        <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.08)]">
        <table className={TABLE} data-testid="ledger-months">
          <thead>
            <tr className={THEAD_ROW}>
              <th className={TH_SM}>Month</th>
              <th className={TH_SM}>Estimate</th>
              <th className={TH_SM}>Measured</th>
            </tr>
          </thead>
          <tbody>
            {months.data.map(m => (
              <tr key={m.month} className={TR}>
                <td className={TD_SM}>{m.month}</td>
                <td className={TD_SM}>
                  {row.savingsUnknown ? NOT_PRICED : <MoneyFigure amount={row.monthlySavings} label="Estimate" />}
                </td>
                <td className={TD_SM}>
                  <MoneyFigure amount={Number(m.amount.amount)} currency={m.amount.currency} label={m.label} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      ) : (
        <p className={MUTED}>No measured savings for this group yet.</p>
      )}
    </div>
  );
};

export default SavingsTab;
