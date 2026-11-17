import React, { useMemo, useState } from 'react';
import type { DatastoreSensitiveDatabase, DatastoreSensitiveTable } from '../../../../types/dspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { usePaged } from '../../dspmShared';
import { abbreviate } from '../datastoreFormat';
import { CategoryChip, EntityChip, LabelChips } from '../ui/Chips';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { TableFooter } from '../ui/TableFooter';
import { EmptyTableRow, ExpandButton, TH } from './TabParts';

interface Props {
  /** The database picked in the "Database" select, or null when the datastore reports none. */
  database: DatastoreSensitiveDatabase | null;
  search: string;
  showFalsePositives: boolean;
}

const NO_TABLES: DatastoreSensitiveTable[] = [];

const TD = 'px-3 py-2 align-middle text-xs text-[rgba(0,0,0,0.75)]';

/** An expanded table's findings: "Column | Entity | Sensitives | False Positive". */
const Findings: React.FC<{ table: DatastoreSensitiveTable; showFalsePositives: boolean }> = ({ table, showFalsePositives }) => {
  // TODO: expanded-row behaviour for zero-count tables unverified (A17)
  const findings = showFalsePositives ? table.findings : table.findings.filter(finding => !finding.falsePositive);
  return (
    <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.06)] bg-white">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <th scope="col" className={TH}>Column</th>
            <th scope="col" className={TH}>Entity</th>
            <th scope="col" className={TH}>Sensitives</th>
            <th scope="col" className={TH}>False Positive</th>
          </tr>
        </thead>
        <tbody>
          {findings.length === 0 && (
            <tr>
              <td colSpan={4} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No findings to show.</td>
            </tr>
          )}
          {findings.map((finding, index) => (
            <tr key={`${index}:${finding.columnName ?? ''}:${finding.entityType ?? ''}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
              <td className={TD}>{finding.columnName ?? '-'}</td>
              <td className={TD}>
                <span className="inline-flex items-center gap-1">
                  <EntityChip name={finding.entityType ?? ''} categoryLabel={finding.labels[0] ?? null} />
                  <ConsoleOnlyButton variant="icon" icon="mdi:pencil-outline" label="Edit entity" placement="right" />
                </span>
              </td>
              <td className={TD}>
                <span className="inline-flex flex-wrap items-center gap-1.5">
                  <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={finding.count.toLocaleString('en-US')}>
                    {abbreviate(finding.count)}
                  </span>
                  <LabelChips labels={finding.labels} />
                </span>
              </td>
              <td className={TD}>
                <ConsoleOnlyButton variant="secondary" placement="left">Mark</ConsoleOnlyButton>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/**
 * Table View (databases): "Table | Sensitives" for the selected database, sorted by count as returned
 * and paged in the browser. A table with at least one named finding expands into its findings; Show
 * False Positives off hides the marked ones. The search filters by table name.
 *
 * The parent keys this view by database, so picking another one starts again on page 1, collapsed.
 */
const TableView: React.FC<Props> = ({ database, search, showFalsePositives }) => {
  const tables = database?.tables ?? NO_TABLES;
  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (needle ? tables.filter(table => (table.name ?? '').toLowerCase().includes(needle)) : tables),
    [tables, needle],
  );
  const paged = usePaged(visible, needle);
  const [expanded, setExpanded] = useState<DatastoreSensitiveTable | null>(null);

  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <th scope="col" className={TH}>Table</th>
            <th scope="col" className={TH}>Sensitives</th>
          </tr>
        </thead>
        <tbody>
          {visible.length === 0 && <EmptyTableRow colSpan={2} searching={needle !== '' && tables.length > 0} />}
          {paged.items.map((table, index) => {
            const open = table.expandable && expanded === table;
            const toggle = () => setExpanded(open ? null : table);
            const name = table.name ?? '-';
            return (
              <React.Fragment key={`${index}:${table.schemaName ?? ''}.${name}`}>
                <tr
                  onClick={table.expandable ? toggle : undefined}
                  className={`border-b border-[rgba(0,0,0,0.04)] ${
                    table.expandable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.02)]' : ''
                  } ${open ? 'bg-[rgba(0,0,0,0.03)]' : ''}`}
                >
                  <td className="px-3 py-2.5">
                    <span className="flex min-w-0 items-center gap-2">
                      {table.expandable
                        ? <ExpandButton expanded={open} name={name} onToggle={toggle} />
                        : <span className="w-[18px] flex-shrink-0" />}
                      <DspmObjectIcon objectType="TABLE" />
                      <span className="min-w-0">
                        <span className="block max-w-[18rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={name}>
                          {name}
                        </span>
                        {table.schemaName && <span className="block text-[10px] text-[rgba(0,0,0,0.45)]">{table.schemaName}</span>}
                      </span>
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={table.count.toLocaleString('en-US')}>
                        {abbreviate(table.count)}
                      </span>
                      {table.labels.map(label => <CategoryChip key={label} label={label} />)}
                    </span>
                  </td>
                </tr>
                {open && (
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    <td colSpan={2} className="px-4 py-3">
                      <Findings table={table} showFalsePositives={showFalsePositives} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
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
  );
};

export default TableView;
