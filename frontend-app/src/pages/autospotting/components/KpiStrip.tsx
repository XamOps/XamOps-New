import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import { Icon } from '@iconify/react';
import { MoneyFigure } from './MoneyLabel';
import { CARD } from './theme';

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

const NUM = "font-['Bebas_Neue','Barlow_Condensed',sans-serif] text-[32px] font-normal leading-none text-[rgba(0,0,0,0.88)]";
const Kpi: React.FC<{ label: string; icon: string; iconClass: string; children: React.ReactNode; sub?: React.ReactNode }> = ({
  label,
  icon,
  iconClass,
  children,
  sub,
}) => (
  <div className={`${CARD} relative p-5`}>
    <div aria-hidden="true" className={`absolute right-5 top-5 flex h-8 w-8 items-center justify-center rounded-lg ${iconClass}`}>
      <Icon icon={icon} width={18} />
    </div>
    <dt className="mb-3 pr-10 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">{label}</dt>
    <dd className={NUM}>{children}</dd>
    {sub && <dd className="mt-1.5 text-[11px] text-[rgba(0,0,0,0.55)]">{sub}</dd>}
  </div>
);
/** Three figures only, each with its label (design-ux-ops.md §2.2, FR-UI-05). */
export const KpiStrip: React.FC<KpiStripProps> = ({ rows, partial, accruingNotFinal }) => {
  const { total, unpriced } = summarizeSavings(rows);
  const suffix = partial ? ' (partial)' : '';
  return (
    <dl className="mb-5 grid grid-cols-1 gap-4 md:grid-cols-3">
      <Kpi
        label={`Est. monthly savings${suffix}`}
        icon="mdi:piggy-bank-outline"
        iconClass="bg-emerald-50 text-emerald-700"
        sub={unpriced > 0 ? `${unpriced} ${unpriced === 1 ? 'group' : 'groups'} not priced` : undefined}
      >
        <span className="text-emerald-700">
          <MoneyFigure amount={total} label="Estimate" />
        </span>
      </Kpi>
      <Kpi
        label="Accruing MTD (not final)"
        icon="mdi:calendar-clock-outline"
        iconClass="bg-indigo-50 text-indigo-700"
      >
        {accruingNotFinal === null || accruingNotFinal === undefined ? (
          'Not tracked'
        ) : (
          <MoneyFigure amount={accruingNotFinal} label="Accruing (not final)" />
        )}
      </Kpi>
      <Kpi label={`Groups${suffix}`} icon="mdi:server-network" iconClass="bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]">
        {rows.length}
      </Kpi>
    </dl>
  );
};
export default KpiStrip;
