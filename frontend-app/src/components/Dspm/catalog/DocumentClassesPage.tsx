import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../../types/dspm';
import type { DocumentClassList } from '../../../types/dspmPages';
import { pagesApi } from '../../../hooks/useDspmPages';
import { useLazyResource } from '../../../hooks/useDspmDatastoreDrawer';
import DspmNotReady from '../DspmNotReady';
import { FILE_PAGE_SIZES, usePaged } from '../dspmShared';
import FilterDropdown from '../datastores/FilterDropdown';
import type { DatastoreFilterState } from '../datastores/datastoreFilters';
import { ErrorNote, LoadingNote, TH, useHiddenColumns } from '../datastores/drawer/TabParts';
import { DotChip } from '../datastores/ui/Chips';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { TableFooter } from '../datastores/ui/TableFooter';
import {
  CATEGORY_DIMENSION, documentClassCategoryOptions, documentClassColour, filterDocumentClasses,
} from './documentClasses';

type ColumnId = 'name' | 'category';

/**
 * The console has Sensitive Tags, Status and Description as well; `/saas/categories` gives only class and
 * category names, so those columns are left out (brief 10.2, GAP-14).
 */
const COLUMNS: ReadonlyArray<{ id: ColumnId; label: string }> = [
  { id: 'name', label: 'Document Classes' },
  { id: 'category', label: 'Category' },
];

const NO_FILTERS: DatastoreFilterState = {};

/** A class name as the console prints it: a chip with its category's colour as a left bar. */
const ClassChip: React.FC<{ name: string; colour: string }> = ({ name, colour }) => (
  <span className="inline-flex max-w-full items-stretch overflow-hidden rounded border border-[rgba(0,0,0,0.1)] bg-white">
    <span aria-hidden="true" data-colour={colour} className={`w-1 flex-shrink-0 ${colour}`} />
    <span className="truncate px-1.5 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.75)]" title={name}>{name}</span>
  </span>
);

const DocumentClassTable: React.FC<{ list: DocumentClassList }> = ({ list }) => {
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>(NO_FILTERS);
  const [hidden, toggleColumn] = useHiddenColumns<ColumnId>();
  const options = useMemo(() => documentClassCategoryOptions(list), [list]);
  const labelMemory = useMemo(() => new Map(options.map(option => [option.value, option.label])), [options]);
  const rows = useMemo(() => filterDocumentClasses(list.rows, search, filters), [list.rows, search, filters]);
  const paged = usePaged(rows, `${search.trim().toLowerCase()}|${JSON.stringify(filters)}`);
  const showCategory = !hidden.has('category');
  return (
    <div className="rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
      <div className="flex flex-wrap items-center gap-2 px-4 py-3">
        <SearchInput value={search} onChange={setSearch} placeholder="Search" className="w-60" />
        <Icon icon="mdi:filter-variant" width={16} className="text-[rgba(0,0,0,0.4)]" aria-hidden="true" />
        <FilterDropdown
          label="Category"
          dimension={CATEGORY_DIMENSION}
          options={options}
          hasMore={false}
          filters={filters}
          labelMemory={labelMemory}
          loading={false}
          error={false}
          onChange={setFilters}
          local
        />
        <div className="ml-auto">
          <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-t border-[rgba(0,0,0,0.06)]">
              <th className={TH}>Document Classes</th>
              {showCategory && <th className={TH}>Category</th>}
            </tr>
          </thead>
          <tbody>
            {paged.items.length === 0 && (
              <tr>
                <td colSpan={showCategory ? 2 : 1}><EmptyState title="No document classes found" /></td>
              </tr>
            )}
            {paged.items.map(row => {
              const colour = documentClassColour(row.category, list.categories);
              return (
                <tr key={`${row.category}\u0000${row.name}`} className="border-t border-[rgba(0,0,0,0.06)]">
                  <td className="px-3 py-2.5"><ClassChip name={row.name} colour={colour} /></td>
                  {showCategory && <td className="px-3 py-2.5"><DotChip label={row.category} dot={colour} /></td>}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <TableFooter
        page={paged.page}
        pageSize={paged.size}
        total={paged.total}
        onPage={paged.setPage}
        onPageSize={paged.setSize}
        pageSizes={FILE_PAGE_SIZES}
      />
    </div>
  );
};

const ReadyDocumentClasses: React.FC = () => {
  const list = useLazyResource(pagesApi.documentClasses, true);
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Document Classes</h1>
        <ConsoleOnlyButton variant="primary" placement="left">Add</ConsoleOnlyButton>
      </div>
      {list.error ? <ErrorNote error={list.error} onRetry={list.reload} subject="item" />
        : list.data === null ? <LoadingNote />
          : <DocumentClassTable list={list.data} />}
    </div>
  );
};

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Document Classes (DATA CATALOGUE; design 6.5, brief 10.2): every class of
 * `GET /api/dspm/document-classes` with its category, searched and filtered by category in the browser,
 * paged 10 / 25 / 50. "Add" is the console's and greyed.
 */
const DocumentClassesPage: React.FC<Props> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyDocumentClasses />;
};

export default DocumentClassesPage;
