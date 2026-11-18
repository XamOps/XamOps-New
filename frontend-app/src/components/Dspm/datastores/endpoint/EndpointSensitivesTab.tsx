import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import type { EndpointDrawerApi } from '../../../../hooks/useDspmEndpointDrawer';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastoreFilters';
import FilterDropdown from '../FilterDropdown';
import MoreFiltersPanel from '../MoreFiltersPanel';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle, type SegmentedOption } from '../ui/SegmentedToggle';
import { ToggleSwitch } from '../ui/ToggleSwitch';
import EndpointEntityView from './EndpointEntityView';
import EndpointFileView from './EndpointFileView';
import {
  ENDPOINT_FILTER_DIMENSIONS, endpointFilterValueAllowed, filterParamsOf, type EndpointFilterDimension,
} from './endpointFormat';

type EndpointView = 'entity' | 'file';

const VIEWS: ReadonlyArray<SegmentedOption<EndpointView>> = [
  { value: 'entity', label: 'Entity View' },
  { value: 'file', label: 'File View' },
];

const PLACEHOLDER: Record<EndpointView, string> = {
  entity: 'Search entities',
  file: 'Search files',
};

const DIMENSION_LABELS: Record<EndpointFilterDimension, string> = {
  sensitives: 'Sensitives',
  documentClass: 'Document Class',
  category: 'Category',
  dataElement: 'Data Element',
};

const labelFor = (dimension: string): string => DIMENSION_LABELS[dimension as EndpointFilterDimension] ?? dimension;

/** The two dropdowns of the filter row; "+ More filters" lists all four (A13 [O3]). */
const QUICK_FILTERS: ReadonlyArray<EndpointFilterDimension> = ['sensitives', 'documentClass'];

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/** Value → label per dimension, from the one filter-options response (it does not change while the drawer is open). */
function labelMemoryOf(options: DatastoreFilterOptions | null): ReadonlyMap<string, ReadonlyMap<string, string>> {
  const memory = new Map<string, ReadonlyMap<string, string>>();
  for (const dimension of options?.dimensions ?? []) {
    memory.set(dimension.key, new Map(dimension.options.map(option => [option.value, option.label])));
  }
  return memory;
}

interface Props {
  api: EndpointDrawerApi;
  /** The drawer's filter options, requested on open. */
  filterOptions: LazyResource<DatastoreFilterOptions>;
}

/**
 * The Endpoint drawer's Sensitives tab (FR-EP-4): "Entity View | File View", a search cleared on every
 * view switch, Show False Positives, and a filter row with the "Sensitives" and "Document Class"
 * dropdowns and "+ More filters". The filters apply to both views; they offer no Include / Exclude and
 * disable every value the backend would reject or split (`endpointFilterValueAllowed`).
 *
 * Show False Positives stays visible and usable in File View, as in the console, but only Entity View
 * and its occurrences read it: the files route takes no such parameter, so toggling it there sends
 * nothing. Its state is kept, so it applies again back in Entity View.
 */
const EndpointSensitivesTab: React.FC<Props> = ({ api, filterOptions }) => {
  const [view, setView] = useState<EndpointView>('entity');
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [showFalsePositives, setShowFalsePositives] = useState(false);

  const options = filterOptions.data;
  const optionsLoading = filterOptions.loading;
  const optionsError = filterOptions.error !== null;
  const labelMemory = useMemo(() => labelMemoryOf(options), [options]);
  const params = useMemo(() => filterParamsOf(filters), [filters]);
  // A dimension is listed when it has options or holds a selection.
  const moreDimensions = useMemo(
    () => ENDPOINT_FILTER_DIMENSIONS.filter(dimension =>
      (options?.dimensions.find(d => d.key === dimension)?.options.length ?? 0) > 0
      || (filters[dimension]?.values.length ?? 0) > 0),
    [options, filters],
  );

  const switchView = (next: EndpointView) => {
    setView(next);
    setSearch('');
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SegmentedToggle options={VIEWS} value={view} onChange={switchView} label="Sensitive data view" />
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

      <div className="flex flex-wrap items-center gap-2">
        <Icon icon="mdi:filter-variant" width={16} className="text-[rgba(0,0,0,0.4)]" aria-hidden="true" />
        {QUICK_FILTERS.map(key => {
          const dimension = options?.dimensions.find(d => d.key === key);
          return (
            <FilterDropdown
              key={key}
              label={labelFor(key)}
              dimension={key}
              options={options === null ? null : (dimension?.options ?? [])}
              hasMore={dimension?.hasMore ?? false}
              filters={filters}
              labelMemory={labelMemory.get(key) ?? NO_LABELS}
              loading={optionsLoading}
              error={optionsError}
              onChange={setFilters}
              local
              canSelectValue={endpointFilterValueAllowed(key)}
            />
          );
        })}
        <MoreFiltersPanel
          dimensions={moreDimensions}
          labelFor={labelFor}
          options={options}
          optionsLoading={optionsLoading}
          optionsError={optionsError}
          filters={filters}
          labelMemory={labelMemory}
          onChange={setFilters}
          local
          canSelectValue={endpointFilterValueAllowed}
        />
      </div>

      {view === 'entity' ? (
        <EndpointEntityView api={api} filters={params} showFalsePositives={showFalsePositives} search={search} />
      ) : (
        <EndpointFileView api={api} filters={params} search={search} />
      )}
    </div>
  );
};

export default EndpointSensitivesTab;
