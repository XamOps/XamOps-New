import React from 'react';
import { Icon } from '@iconify/react';
import { useEndpointFileList, useFilesFilterOptions } from '../../../hooks/useDspmFiles';
import { datastoreErrorCopy } from '../datastores/datastoreErrors';
import { useHiddenColumns } from '../datastores/drawer/TabParts';
import { ENDPOINT_FILE_COLUMNS, type EndpointFileColumnId } from '../datastores/endpoint/endpointFileColumns';
import EndpointFileTable from '../datastores/endpoint/EndpointFileTable';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { EmptyState } from '../datastores/ui/EmptyState';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import FilesFilterBar from './FilesFilterBar';
import { ENDPOINT_FILTERS } from './filesFilterConfig';

/**
 * The Files page's Endpoint tab: the filter bar, "Columns 9", the offset-paged table and its footer, which
 * stays mounted and describes the page on screen.
 */
const EndpointFilesTab: React.FC = () => {
  const list = useEndpointFileList();
  const filterOptions = useFilesFilterOptions('endpoint');
  const [hidden, toggleColumn] = useHiddenColumns<EndpointFileColumnId>();
  const errorCopy = list.error ? datastoreErrorCopy(list.error) : null;
  const filtered = list.q !== null || Object.values(list.filters).some(selection => selection.values.length > 0);
  const shown = list.shown;
  return (
    <div className="space-y-4">
      <FilesFilterBar
        config={ENDPOINT_FILTERS}
        searchInput={list.searchInput}
        onSearchInput={list.setSearchInput}
        options={filterOptions.options}
        optionsLoading={filterOptions.loading}
        optionsError={filterOptions.error !== null}
        filters={list.filters}
        labelMemory={filterOptions.labelMemory}
        onFiltersChange={list.updateFilters}
      />
      <section className="min-w-0 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-2.5">
          <ColumnsMenu columns={ENDPOINT_FILE_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        </header>
        {errorCopy && (
          <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
            <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
            <span className="flex-1">{errorCopy.text}</span>
            {errorCopy.retry && (
              <button type="button" onClick={list.reload} className="font-semibold underline">Retry</button>
            )}
          </div>
        )}
        <EndpointFileTable
          rows={shown?.rows ?? null}
          loading={list.loading}
          hidden={hidden}
          emptyState={filtered
            ? <EmptyState title="No files match these filters" body="Try clearing the search or filters." />
            : <EmptyState title="No files yet" body="Files appear here once a scan has found them." />}
        />
        <TableFooter
          page={shown ? shown.skip / shown.limit + 1 : 1}
          total={shown?.total ?? 0}
          pageSize={list.pageSize}
          onPage={list.goToPage}
          onPageSize={list.setPageSize}
          maxPage={listPageLimit(list.pageSize)}
        />
      </section>
    </div>
  );
};

export default EndpointFilesTab;
