import React, { useMemo, useState } from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import { formatMoney, NOT_PRICED } from '../../../utils/formatMoney';
import StateBadge from './StateBadge';

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
    <table className="w-full text-left text-sm text-black">
      <caption className="sr-only">{`${adapter.groupNoun}s`}</caption>
      <thead>
        <tr className="border-b border-gray-300 text-gray-600">
          <th scope="col" className="py-2 pr-3 font-medium">
            Group
          </th>
          <th scope="col" className="py-2 pr-3 font-medium">
            {adapter.locationLabel}
          </th>
          <th scope="col" className="py-2 pr-3 font-medium">
            State
          </th>
          <th scope="col" className="py-2 pr-3 font-medium">
            Desired
          </th>
          {adapter.columns.map(c => (
            <th key={c.id} scope="col" className="py-2 pr-3 font-medium">
              {c.header}
            </th>
          ))}
          <th scope="col" aria-sort={dir === 'desc' ? 'descending' : 'ascending'} className="py-2 font-medium">
            <button type="button" onClick={() => setDir(d => (d === 'desc' ? 'asc' : 'desc'))} className="font-medium">
              Est./mo
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
              className={`cursor-pointer border-b border-gray-100 ${selected ? 'bg-gray-100' : 'hover:bg-gray-50'}`}
            >
              <td className="py-2 pr-3">
                <button
                  type="button"
                  className="font-medium underline"
                  onClick={e => {
                    e.stopPropagation();
                    onSelect(r);
                  }}
                >
                  {r.name}
                </button>
              </td>
              <td className="py-2 pr-3">{r.location}</td>
              <td className="py-2 pr-3">
                <StateBadge row={r} />
              </td>
              <td className="py-2 pr-3">{r.desired ?? '--'}</td>
              {adapter.columns.map(c => (
                <td key={c.id} className="py-2 pr-3">
                  {c.render(r)}
                </td>
              ))}
              <td className="py-2">
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
  );
};

export default GroupTable;
