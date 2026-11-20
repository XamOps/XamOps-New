import React, { useMemo, useState } from 'react';
import type { SaasRecords, SaasRecordsView } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import type { SaasDrawerApi } from '../../../../hooks/useDspmSaasDrawer';
import { usePaged } from '../../dspmShared';
import { MAX_SEARCH } from '../datastoreFilters';
import { abbreviate } from '../datastoreFormat';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle, type SegmentedOption } from '../ui/SegmentedToggle';
import { TableFooter } from '../ui/TableFooter';
import { EmptyTableRow, ExpandButton, ResourceView, TH } from '../drawer/TabParts';
import SaasFileList, { type OpenSaasFile } from './SaasFileList';
import { GreyTag } from './SaasParts';

const VIEWS: ReadonlyArray<SegmentedOption<SaasRecordsView>> = [
  { value: 'data_elements', label: 'Data Elements' },
  { value: 'document_classes', label: 'Document Classes' },
];

const FIRST_COLUMN: Record<SaasRecordsView, string> = {
  data_elements: 'Data Elements',
  document_classes: 'Document Classes',
};

interface TableProps {
  api: SaasDrawerApi;
  view: SaasRecordsView;
  records: LazyResource<SaasRecords>;
  search: string;
  onOpenFile: OpenSaasFile;
}

/** One view's table: the rows of its records, filtered by name and paged in the browser (Rows Per Page 10). */
const RecordsTable: React.FC<TableProps> = ({ api, view, records, search, onOpenFile }) => {
  const rows = useMemo(() => records.data?.rows ?? [], [records.data]);
  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (needle ? rows.filter(row => row.name.toLowerCase().includes(needle)) : rows),
    [rows, needle],
  );
  const paged = usePaged(visible, needle);
  // One row is open at a time. The open row belongs to the search and page it was opened on, so another
  // search or page closes it (a view switch remounts this table).
  const [expanded, setExpanded] = useState<{ name: string; at: string } | null>(null);
  const at = `${needle}|${paged.page}`;
  const openName = expanded?.at === at ? expanded.name : null;
  const loaded = records.data !== null && records.error === null;
  const documentClasses = view === 'document_classes';

  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <th scope="col" className={TH}>{FIRST_COLUMN[view]}</th>
            <th scope="col" className={TH}>Sensitives</th>
          </tr>
        </thead>
        <tbody>
          {!loaded && (
            <tr>
              <td colSpan={2} className="px-3 py-2">
                <ResourceView resource={records}>{() => null}</ResourceView>
              </td>
            </tr>
          )}
          {/* DA18 [O3]: the console's text for an empty view of a scanned SaaS datastore is unseen, so an
              empty view keeps build 1's empty row (review fix R13). */}
          {loaded && visible.length === 0 && <EmptyTableRow colSpan={2} searching={needle !== '' && rows.length > 0} />}
          {loaded && paged.items.map((row, index) => {
            const open = openName === row.name;
            const toggle = () => setExpanded(open ? null : { name: row.name, at });
            return (
              <React.Fragment key={`${index}:${row.name}`}>
                <tr
                  onClick={toggle}
                  className={`cursor-pointer border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] ${open ? 'bg-[rgba(0,0,0,0.03)]' : ''}`}
                >
                  <td className="px-3 py-2.5">
                    <span className="flex min-w-0 items-center gap-2">
                      <ExpandButton expanded={open} name={row.name} onToggle={toggle} />
                      {documentClasses
                        ? <GreyTag>{row.name}</GreyTag>
                        : <EntityChip name={row.name} categoryLabel={row.categoryLabel} />}
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={row.count.toLocaleString('en-US')}>
                        {abbreviate(row.count)}
                      </span>
                      {/* Document categories are another vocabulary than the sensitive ones: neutral grey (A23). */}
                      <CategoryChip label={row.categoryLabel} neutral={documentClasses} />
                    </span>
                  </td>
                </tr>
                {open && (
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    <td colSpan={2} className="px-4 py-3">
                      <SaasFileList
                        api={api}
                        kind={documentClasses ? 'documentClass' : 'dataElement'}
                        value={row.name}
                        onOpenFile={onOpenFile}
                      />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
      {loaded && (
        <TableFooter
          page={paged.page}
          pageSize={paged.size}
          total={paged.total}
          onPage={paged.setPage}
          onPageSize={paged.setSize}
        />
      )}
    </div>
  );
};

interface Props {
  api: SaasDrawerApi;
  view: SaasRecordsView;
  onView: (view: SaasRecordsView) => void;
  /** The records of `view`, each view its own resource. */
  records: LazyResource<SaasRecords>;
  onOpenFile: OpenSaasFile;
}

/**
 * The SaaS drawer's Sensitives tab (FR-SAAS-5): "Data Elements | Document Classes" and a name search over
 * the loaded rows, cleared on a view switch. A row expands into its files (`SaasFileList`). While a view's
 * records load or after they failed, the table body says so; the toggle and the search stay usable.
 */
const SaasSensitivesTab: React.FC<Props> = ({ api, view, onView, records, onOpenFile }) => {
  const [search, setSearch] = useState('');
  const switchView = (next: SaasRecordsView) => {
    onView(next);
    setSearch('');
  };
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SegmentedToggle options={VIEWS} value={view} onChange={switchView} label="Sensitive view" />
        <SearchInput value={search} onChange={setSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
      </div>
      <RecordsTable key={view} api={api} view={view} records={records} search={search} onOpenFile={onOpenFile} />
    </div>
  );
};

export default SaasSensitivesTab;
