import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';

/** Estimate only. Closed, invoiceable amounts appear on the Statements screen, not here. */
export const SavingsTab: React.FC<{ row: GroupRow }> = ({ row }) => (
  <div className="space-y-2 text-sm text-black">
    <p className="text-gray-600">Estimated monthly savings at the current profile or the preselected one.</p>
    <p className="text-base">
      {row.savingsUnknown ? NOT_PRICED : <MoneyFigure amount={row.monthlySavings} label="Estimate" perMonth />}
    </p>
    <p className="text-gray-600">Accruing (not final) figures are not tracked yet.</p>
  </div>
);

export default SavingsTab;
