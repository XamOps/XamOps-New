import React, { useMemo, useState } from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import { formatMoney, NOT_PRICED } from '../../../utils/formatMoney';
import { Icon } from '@iconify/react';
import StateBadge from './StateBadge';
import { CARD, MUTED, TABLE, TD as TD_BASE, TH as TH_BASE, THEAD_ROW } from './theme';
/** Tighter horizontal padding than the page tables, so all columns fit beside the detail panel. */
const TH = TH_BASE.replace('px-5', 'px-3 first:pl-5 last:pr-5');
const TD = TD_BASE.replace('px-5', 'px-3 first:pl-5 last:pr-5');

export const rowKey = (r: GroupRow): string => r.id ?? `${r.location}/${r.groupKey}`;

/** Estimated savings descending; unpriced and empty rows last. */
export function sortRows(rows: GroupRow[], dir: 'desc' | 'asc' = 'desc'): GroupRow[] {
  const val = (r: GroupRow) => (r.monthlySavings === null || r.monthlySavings === undefined ? null : r.monthlySavings);
  return [...rows].sort((a, b) => {
    const av = val(a);
    const bv = val(b);
    if (av === null && bv === null) return a.name.localeCompare(b.name);
    if (av === null) return 1;
    if (bv === null) return -1;
    return dir === 'desc' ? bv - av : av - bv;
  });
}

interface GroupTableProps {
  rows: GroupRow[];
  adapter: ProviderAdapter;
  selectedKey: string | null;
  onSelect: (row: GroupRow) => void;
}

/**
 * The group table (design-ux-ops.md §2.2, §2.7). No action buttons in rows. The name cell is the
 * keyboard route to the detail panel, and the Est./mo header is a sort button with aria-sort.
 */
export const GroupTable: React.FC<GroupTableProps> = ({ rows, adapter, selectedKey, onSelect }) => {
  const [dir, setDir] = useState<'desc' | 'asc'>('desc');
  const sorted = useMemo(() => sortRows(rows, dir), [rows, dir]);
  return (
    <div className={`${CARD} overflow-x-auto`}>
    <table className={TABLE}>
      <caption className="sr-only">{`${adapter.groupNoun}s`}</caption>
      <thead>
        <tr className={THEAD_ROW}>
          <th scope="col" className={TH}>
            Group
          </th>
          <th scope="col" className={TH}>
            {adapter.locationLabel}
          </th>
          <th scope="col" className={TH}>
            State
          </th>
          <th scope="col" className={TH}>
            Desired
          </th>
          {adapter.columns.map(c => (
            <th key={c.id} scope="col" className={TH}>
              {c.header}
            </th>
          ))}
          <th scope="col" aria-sort={dir === 'desc' ? 'descending' : 'ascending'} className={`${TH} text-right`}>
            <button
              type="button"
              onClick={() => setDir(d => (d === 'desc' ? 'asc' : 'desc'))}
              className="inline-flex items-center gap-0.5 rounded font-semibold uppercase tracking-wide hover:text-[rgba(0,0,0,0.88)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
            >
              Est./mo
              <Icon icon={dir === 'desc' ? 'mdi:arrow-down' : 'mdi:arrow-up'} width={12} aria-hidden="true" />
            </button>
          </th>
        </tr>
      </thead>
      <tbody>
        {sorted.map(r => {
          const key = rowKey(r);
          const selected = key === selectedKey;
          return (
            <tr
              key={key}
              aria-selected={selected}
              onClick={() => onSelect(r)}
              className={`cursor-pointer border-t border-[rgba(0,0,0,0.04)] transition-colors ${
                selected ? 'bg-indigo-50/60' : 'hover:bg-[rgba(0,0,0,0.015)]'
              }`}
            >
              <td className={TD}>
                <button
                  type="button"
                  className={`rounded text-left font-semibold hover:text-indigo-700 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 ${
                    selected ? 'text-indigo-700' : 'text-[rgba(0,0,0,0.88)]'
                  }`}
                  onClick={e => {
                    e.stopPropagation();
                    onSelect(r);
                  }}
                >
                  {r.name}
                </button>
              </td>
              <td className={`${TD} ${MUTED}`}>{r.location}</td>
              <td className={TD}>
                <StateBadge row={r} />
              </td>
              <td className={`${TD} tabular-nums`}>{r.desired ?? '--'}</td>
              {adapter.columns.map(c => (
                <td key={c.id} className={TD}>
                  {c.render(r)}
                </td>
              ))}
              <td className={`${TD} text-right font-semibold tabular-nums ${!r.savingsUnknown && r.monthlySavings ? 'text-emerald-700' : MUTED}`}>
                {r.savingsUnknown
                  ? NOT_PRICED
                  : r.monthlySavings === null || r.monthlySavings === undefined
                    ? '--'
                    : formatMoney(r.monthlySavings, 'USD')}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
    </div>
  );
};

export default GroupTable;
