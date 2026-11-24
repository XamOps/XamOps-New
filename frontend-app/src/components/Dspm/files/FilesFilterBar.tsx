import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastores/datastoreFilters';
import FilterDropdown from '../datastores/FilterDropdown';
import MoreFiltersPanel from '../datastores/MoreFiltersPanel';
import { CategoryChip } from '../datastores/ui/Chips';
import { SearchInput } from '../datastores/ui/SearchInput';
import type { FilesFilterConfig } from './filesFilterConfig';
import { filesValueAllowed } from './filesFilters';

interface Props {
  config: FilesFilterConfig;
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

const chipLabel = (label: string) => <CategoryChip label={label} />;

/**
 * "Search Files", the decorative funnel, the tab's quick dropdowns and "More filters" with its title.
 * Every filter is local (no list-page dimension rules): values are checked by `filesValueAllowed`, the
 * multi-selects show Exclude disabled, and the single-choice dimensions use `SingleChoiceList`.
 */
const FilesFilterBar: React.FC<Props> = ({
  config, searchInput, onSearchInput, options, optionsLoading, optionsError, filters, labelMemory, onFiltersChange,
}) => {
  // A dimension is listed when it has options or holds a selection.
  const moreDimensions = useMemo(
    () => config.more.filter(key =>
      (options?.dimensions.find(d => d.key === key)?.options.length ?? 0) > 0
      || (filters[key]?.values.length ?? 0) > 0),
    [config, options, filters],
  );
  const labelFor = (key: string) => config.labels[key] ?? key;
  const isSingle = (key: string) => config.single.has(key);
  const renderLabelFor = (key: string) => (config.chips.has(key) ? chipLabel : undefined);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <SearchInput
        value={searchInput}
        onChange={onSearchInput}
        placeholder="Search Files"
        maxLength={MAX_SEARCH}
        className="w-64"
      />
      <Icon icon="mdi:filter-outline" width={16} className="text-[rgba(0,0,0,0.4)]" aria-hidden="true" />
      {config.quick.map(key => {
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
            onChange={onFiltersChange}
            local
            canSelectValue={filesValueAllowed(key)}
            single={isSingle(key)}
            anyLabel={config.anyLabel[key]}
            renderLabel={renderLabelFor(key)}
            consoleOnlyExclude={!isSingle(key)}
          />
        );
      })}
      <MoreFiltersPanel
        title="More filters"
        dimensions={moreDimensions}
        labelFor={labelFor}
        options={options}
        optionsLoading={optionsLoading}
        optionsError={optionsError}
        filters={filters}
        labelMemory={labelMemory}
        onChange={onFiltersChange}
        local
        canSelectValue={filesValueAllowed}
        singleChoice={isSingle}
        anyLabelFor={key => config.anyLabel[key]}
        renderLabelFor={renderLabelFor}
        consoleOnlyExcludeFor={key => !isSingle(key)}
      />
    </div>
  );
};

export default FilesFilterBar;
