import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import { useGroupSavings } from '../../../hooks/autospotting/useLedgerSavings';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';

/**
 * Estimate vs the ledger's measured figure per month (plan-phase-17.md 17.4). The estimate is the plan's;
 * each month's figure is the ledger accrual, labelled "Accruing (not final)" for the open month and "Not
 * invoiced" for a closed shadow month. Invoiced amounts appear on the Statements screen, not here.
 */
export const SavingsTab: React.FC<{ row: GroupRow; accountId?: number | null }> = ({ row, accountId = null }) => {
  const months = useGroupSavings(accountId, accountId === null ? null : row.id);
  return (
    <div className="space-y-2 text-sm text-black">
      <p className="text-gray-600">Estimated monthly savings at the current profile or the preselected one.</p>
      <p className="text-base">
        {row.savingsUnknown ? NOT_PRICED : <MoneyFigure amount={row.monthlySavings} label="Estimate" perMonth />}
      </p>
      {months.data && months.data.length > 0 ? (
        <table className="w-full text-sm" data-testid="ledger-months">
          <thead>
            <tr className="text-left text-gray-600">
              <th className="py-1 font-normal">Month</th>
              <th className="py-1 font-normal">Estimate</th>
              <th className="py-1 font-normal">Measured</th>
            </tr>
          </thead>
          <tbody>
            {months.data.map(m => (
              <tr key={m.month}>
                <td className="py-1">{m.month}</td>
                <td className="py-1">
                  {row.savingsUnknown ? NOT_PRICED : <MoneyFigure amount={row.monthlySavings} label="Estimate" />}
                </td>
                <td className="py-1">
                  <MoneyFigure amount={Number(m.amount.amount)} currency={m.amount.currency} label={m.label} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="text-gray-600">No measured savings for this group yet.</p>
      )}
    </div>
  );
};

export default SavingsTab;
