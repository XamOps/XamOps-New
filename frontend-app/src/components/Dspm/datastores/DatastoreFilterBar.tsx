import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import { MAX_SEARCH, type DatastoreFilterState } from './datastoreFilters';
import { dimensionLabel, moreFiltersDimensions, type DatastoreTabConfig } from './datastoreTabConfig';
import FilterDropdown from './FilterDropdown';
import MoreFiltersPanel from './MoreFiltersPanel';
import { SearchInput } from './ui/SearchInput';

interface Props {
  config: DatastoreTabConfig;
  searchInput: string;
  onSearchInput: (value: string) => void;
  options: DatastoreFilterOptions | null;
  optionsLoading: boolean;
  optionsError: boolean;
  filters: DatastoreFilterState;
  labelMemory: ReadonlyMap<string, ReadonlyMap<string, string>>;
  onFiltersChange: (next: DatastoreFilterState) => void;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/** "Search by name", the decorative funnel, the tab's quick filters and "+ More filters". */
const DatastoreFilterBar: React.FC<Props> = ({
  config, searchInput, onSearchInput, options, optionsLoading, optionsError, filters, labelMemory, onFiltersChange,
}) => {
  // The tab's More-filters dimensions; the panel itself no longer reads the tab config (review fix R3).
  const moreDimensions = useMemo(() => moreFiltersDimensions(config, options, filters), [config, options, filters]);

  return (
    <div className="flex flex-wrap items-center gap-2">
      <SearchInput
        value={searchInput}
        onChange={onSearchInput}
        placeholder="Search by name"
        maxLength={MAX_SEARCH}
        className="w-64"
      />
      <Icon icon="mdi:filter-outline" width={16} className="text-[rgba(0,0,0,0.4)]" />
      {config.quickFilters.map(key => {
        const dimension = options?.dimensions.find(d => d.key === key);
        return (
          <FilterDropdown
            key={key}
            label={dimensionLabel(key)}
            dimension={key}
            options={options === null ? null : (dimension?.options ?? [])}
            hasMore={dimension?.hasMore ?? false}
            filters={filters}
            labelMemory={labelMemory.get(key) ?? NO_LABELS}
            loading={optionsLoading}
            error={optionsError}
            onChange={onFiltersChange}
          />
        );
      })}
      <MoreFiltersPanel
        dimensions={moreDimensions}
        labelFor={dimensionLabel}
        options={options}
        optionsLoading={optionsLoading}
        optionsError={optionsError}
        filters={filters}
        labelMemory={labelMemory}
        onChange={onFiltersChange}
      />
    </div>
  );
};

export default DatastoreFilterBar;
