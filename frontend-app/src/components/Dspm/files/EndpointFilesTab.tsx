import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import { useEndpointFileList, useFilesFilterOptions } from '../../../hooks/useDspmFiles';
import { datastoreErrorCopy } from '../datastores/datastoreErrors';
import { useHiddenColumns } from '../datastores/drawer/TabParts';
import { ENDPOINT_FILE_COLUMNS, type EndpointFileColumnId } from '../datastores/endpoint/endpointFileColumns';
import EndpointFileDrawer from '../datastores/endpoint/EndpointFileDrawer';
import EndpointFileTable, {
  type EndpointFileOpenTab, type OpenableEndpointFileRow,
} from '../datastores/endpoint/EndpointFileTable';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { EmptyState } from '../datastores/ui/EmptyState';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import FileDrawerHost from './FileDrawerHost';
import FilesFilterBar from './FilesFilterBar';
import { ENDPOINT_FILTERS } from './filesFilterConfig';

interface OpenFile {
  row: OpenableEndpointFileRow;
  tab: EndpointFileOpenTab;
  opener: HTMLElement;
}

/**
 * The Files page's Endpoint tab: the filter bar, "Columns 9", the offset-paged table and its footer, which
 * stays mounted and describes the page on screen. A row opens the Endpoint file drawer on Sensitives, its
 * lineage icon on Lineage.
 */
const EndpointFilesTab: React.FC = () => {
  const list = useEndpointFileList();
  const filterOptions = useFilesFilterOptions('endpoint');
  const [hidden, toggleColumn] = useHiddenColumns<EndpointFileColumnId>();
  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const openRow = useCallback((row: OpenableEndpointFileRow, tab: EndpointFileOpenTab, opener: HTMLElement) => {
    setOpenFile({ row, tab, opener });
  }, []);
  // Stable, as `useSidePanel` requires.
  const closeFile = useCallback(() => setOpenFile(null), []);
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
          onOpen={openRow}
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
      {openFile && (
        <FileDrawerHost>
          <EndpointFileDrawer
            key={openFile.row.id}
            fileId={openFile.row.id}
            fileName={openFile.row.name ?? '-'}
            extension={openFile.row.extension}
            modifiedAt={openFile.row.modifiedAt}
            scannedAt={openFile.row.scannedAt}
            initialTab={openFile.tab}
            returnFocus={openFile.opener}
            onClose={closeFile}
          />
        </FileDrawerHost>
      )}
    </div>
  );
};

export default EndpointFilesTab;
