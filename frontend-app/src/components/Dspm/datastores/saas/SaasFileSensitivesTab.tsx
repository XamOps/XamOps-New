import React, { useMemo, useState } from 'react';
import type { SaasFileSensitive } from '../../../../types/dspmDrawers';
import { usePaged } from '../../dspmShared';
import { MAX_SEARCH } from '../datastoreFilters';
import { abbreviate } from '../datastoreFormat';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { EmptyState } from '../ui/EmptyState';
import { SearchInput } from '../ui/SearchInput';
import { TableFooter } from '../ui/TableFooter';
import { TH, useHiddenColumns } from '../drawer/TabParts';

type SensitiveColumnId = 'dataElement' | 'sensitives';

/** Table order; Data Element is always shown ("Columns 2"). */
const COLUMNS: ReadonlyArray<{ id: SensitiveColumnId; label: string }> = [
  { id: 'dataElement', label: 'Data Element' },
  { id: 'sensitives', label: 'Sensitives' },
];

/**
 * The file's Sensitives tab (FR-SFILE-6): "Data Element | Sensitives" from the overview, searched by data
 * element and paged in the browser (Rows Per Page 10).
 */
const SaasFileSensitivesTab: React.FC<{ sensitives: SaasFileSensitive[] }> = ({ sensitives }) => {
  const [search, setSearch] = useState('');
  const [hidden, toggleColumn] = useHiddenColumns<SensitiveColumnId>();
  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (needle ? sensitives.filter(s => (s.dataElement ?? '').toLowerCase().includes(needle)) : sensitives),
    [sensitives, needle],
  );
  const paged = usePaged(visible, needle);
  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SearchInput value={search} onChange={setSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
        <div className="ml-auto">
          <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        </div>
      </div>
      <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {columns.map(column => <th key={column.id} scope="col" className={TH}>{column.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 && (
              <tr>
                <td colSpan={columns.length}>
                  {sensitives.length === 0
                    ? <EmptyState title="No sensitive data found." />
                    : <EmptyState title="No matches" body="Try a different search." />}
                </td>
              </tr>
            )}
            {paged.items.map((sensitive, index) => (
              <tr key={`${index}:${sensitive.dataElement ?? ''}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                <td className="px-3 py-2.5">
                  <EntityChip name={sensitive.dataElement ?? ''} categoryLabel={sensitive.categoryLabel} />
                </td>
                {!hidden.has('sensitives') && (
                  <td className="px-3 py-2.5">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={sensitive.count.toLocaleString('en-US')}>
                        {abbreviate(sensitive.count)}
                      </span>
                      <CategoryChip label={sensitive.categoryLabel} />
                    </span>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
        <TableFooter
          page={paged.page}
          pageSize={paged.size}
          total={paged.total}
          onPage={paged.setPage}
          onPageSize={paged.setSize}
        />
      </div>
    </div>
  );
};

export default SaasFileSensitivesTab;
