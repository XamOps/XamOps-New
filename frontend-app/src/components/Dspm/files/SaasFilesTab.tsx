import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileListRow } from '../../../types/dspmFiles';
import { useFilesFilterOptions, useSaasFileList } from '../../../hooks/useDspmFiles';
import { datastoreErrorCopy } from '../datastores/datastoreErrors';
import { useHiddenColumns } from '../datastores/drawer/TabParts';
import SaasFileDrawer from '../datastores/saas/SaasFileDrawer';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import FileDrawerHost from './FileDrawerHost';
import FilesFilterBar from './FilesFilterBar';
import { SAAS_FILTERS } from './filesFilterConfig';
import KeysetFooter from './KeysetFooter';
import { SAAS_FILE_COLUMNS, type SaasFileColumnId } from './saasFileColumns';
import SaasFileTable, { type SaasFileOpenTab } from './SaasFileTable';

const NO_VALUES: readonly string[] = [];

interface OpenFile {
  row: SaasFileListRow & { id: string };
  tab: SaasFileOpenTab;
  opener: HTMLElement;
}

/**
 * The Files page's SaaS tab: the filter bar, "Columns 6", the keyset-paged table and its footer. A row opens
 * build 2's SaaS file drawer on Sensitives, its lineage icon on Lineage; the drawer has no datastore name.
 */
const SaasFilesTab: React.FC = () => {
  const list = useSaasFileList();
  const categories = list.filters.category?.values ?? NO_VALUES;
  const filterOptions = useFilesFilterOptions('saas', categories);
  const [hidden, toggleColumn] = useHiddenColumns<SaasFileColumnId>();
  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const openRow = useCallback((row: OpenFile['row'], tab: SaasFileOpenTab, opener: HTMLElement) => {
    setOpenFile({ row, tab, opener });
  }, []);
  // Stable, as `useSidePanel` requires.
  const closeFile = useCallback(() => setOpenFile(null), []);
  const errorCopy = list.error ? datastoreErrorCopy(list.error) : null;
  const filtered = list.q !== null || Object.values(list.filters).some(selection => selection.values.length > 0);
  return (
    <div className="space-y-4">
      <FilesFilterBar
        config={SAAS_FILTERS}
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
          <ColumnsMenu columns={SAAS_FILE_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
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
        <SaasFileTable
          rows={list.shown?.page.rows ?? null}
          loading={list.loading}
          hidden={hidden}
          filtered={filtered}
          onOpen={openRow}
        />
        <KeysetFooter
          pageSize={list.pageSize}
          onPageSize={list.setPageSize}
          range={list.range}
          canPrevious={list.canPrevious}
          canNext={list.canNext}
          onPrevious={list.previous}
          onNext={list.next}
        />
      </section>
      {openFile && (
        <FileDrawerHost>
          <SaasFileDrawer
            key={openFile.row.id}
            fileId={openFile.row.id}
            fileName={openFile.row.name ?? '-'}
            datastoreName={null}
            driveType={openFile.row.source}
            initialTab={openFile.tab}
            returnFocus={openFile.opener}
            onClose={closeFile}
          />
        </FileDrawerHost>
      )}
    </div>
  );
};

export default SaasFilesTab;
