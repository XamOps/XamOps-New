import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreOverview, DatastoreSensitives } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { MAX_SEARCH } from '../datastoreFilters';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle, type SegmentedOption } from '../ui/SegmentedToggle';
import { ToggleSwitch } from '../ui/ToggleSwitch';
import EntityView from './EntityView';
import FileView from './FileView';
import TableView from './TableView';

type SensitiveView = 'entity' | 'file' | 'table';

const PLACEHOLDER: Record<SensitiveView, string> = {
  entity: 'Search entities',
  file: 'Search file paths',
  table: 'Search tables',
};

interface Props {
  api: DatastoreDrawerApi;
  sensitives: DatastoreSensitives;
  /** Null while the overview loads or after it failed; it then counts as "not a database". */
  overview: DatastoreOverview | null;
}

/**
 * The Sensitive tab: Entity View, plus File View for object stores or Table View for databases (a
 * datastore is a database when the sensitives carry a database section or the overview says its schema
 * can be discovered). The same predicate picks the occurrence columns.
 *
 * The search filters entity names, file paths (on the server) or table names, and is cleared on every
 * view switch (A15). Show False Positives changes the occurrence requests and the Table View findings
 * only, never the File View or the counts.
 */
const SensitiveTab: React.FC<Props> = ({ api, sensitives, overview }) => {
  const database = sensitives.database || overview?.canDiscoverSchema === true;
  const [detailView, setDetailView] = useState(false);
  const [search, setSearch] = useState('');
  const [showFalsePositives, setShowFalsePositives] = useState(false);
  const [databaseIndex, setDatabaseIndex] = useState(0);

  const view: SensitiveView = !detailView ? 'entity' : database ? 'table' : 'file';
  const options: ReadonlyArray<SegmentedOption<SensitiveView>> = [
    { value: 'entity', label: 'Entity View' },
    database ? { value: 'table', label: 'Table View' } : { value: 'file', label: 'File View' },
  ];
  const switchView = (next: SensitiveView) => {
    setDetailView(next !== 'entity');
    setSearch('');
  };
  const { databases } = sensitives;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SegmentedToggle options={options} value={view} onChange={switchView} label="Sensitive data view" />
        {view === 'table' && databases.length > 0 && (
          <span className="inline-flex items-center gap-1.5">
            <Icon icon="mdi:database-outline" width={14} className="text-[rgba(0,0,0,0.45)]" />
            <select
              aria-label="Database"
              value={databaseIndex}
              onChange={e => setDatabaseIndex(Number(e.target.value))}
              className="rounded-lg border border-[rgba(0,0,0,0.1)] bg-white px-2 py-1.5 text-xs text-[rgba(0,0,0,0.75)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            >
              {databases.map((db, index) => <option key={index} value={index}>{db.name ?? '-'}</option>)}
            </select>
          </span>
        )}
        <SearchInput
          value={search}
          onChange={setSearch}
          placeholder={PLACEHOLDER[view]}
          maxLength={MAX_SEARCH}
          className="w-60"
        />
        <div className="ml-auto">
          <ToggleSwitch label="Show False Positives" checked={showFalsePositives} onChange={setShowFalsePositives} />
        </div>
      </div>

      {view === 'entity' && (
        <EntityView
          api={api}
          entities={sensitives.entities}
          search={search}
          database={database}
          showFalsePositives={showFalsePositives}
        />
      )}
      {view === 'file' && <FileView api={api} search={search} />}
      {view === 'table' && (
        <TableView
          key={databaseIndex}
          database={databases[databaseIndex] ?? null}
          search={search}
          showFalsePositives={showFalsePositives}
        />
      )}
    </div>
  );
};

export default SensitiveTab;
