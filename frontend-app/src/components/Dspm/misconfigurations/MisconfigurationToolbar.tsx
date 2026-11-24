import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import FilterDropdown from '../datastores/FilterDropdown';
import MoreFiltersPanel from '../datastores/MoreFiltersPanel';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastores/datastoreFilters';
import { SearchInput } from '../datastores/ui/SearchInput';
import {
  CONSOLE_ONLY_DIMENSIONS, GROUP_BY_OPTIONS, MORE_FILTER_DIMENSIONS, SEVERITY, STATUS, dimensionLabel, type GroupBy,
} from './misconfigurationModel';

interface Props {
  search: string;
  onSearch: (value: string) => void;
  groupBy: GroupBy;
  onGroupBy: (value: GroupBy) => void;
  /** Null until the list has loaded. */
  options: DatastoreFilterOptions | null;
  loading: boolean;
  labelMemory: ReadonlyMap<string, ReadonlyMap<string, string>>;
  filters: DatastoreFilterState;
  onFilters: (next: DatastoreFilterState) => void;
  analyticsShown: boolean;
  onToggleAnalytics: () => void;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/**
 * The console's toolbar, in its order: Search over titles, Group By, the filter icon, the pinned Severity
 * and Status filters, "+ More filters" (Source, Category, Framework, Cloud Account, SaaS Account), then a
 * separator and the chart button that shows or hides the analytics row. Every filter is local: the page
 * already holds every row. Status has one option, Open; Category and SaaS Account have no data and are
 * greyed.
 */
const MisconfigurationToolbar: React.FC<Props> = ({
  search, onSearch, groupBy, onGroupBy, options, loading, labelMemory, filters, onFilters, analyticsShown,
  onToggleAnalytics,
}) => {
  const quickFilter = (dimension: string) => (
    <FilterDropdown
      label={dimensionLabel(dimension)}
      local
      dimension={dimension}
      options={options === null ? null : (options.dimensions.find(d => d.key === dimension)?.options ?? [])}
      hasMore={false}
      filters={filters}
      labelMemory={labelMemory.get(dimension) ?? NO_LABELS}
      loading={loading}
      error={false}
      onChange={onFilters}
    />
  );
  return (
    <div className="flex items-start gap-4">
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-3">
        <SearchInput value={search} onChange={onSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
        <label className="inline-flex items-center gap-2 text-sm text-[#292a2e]">
          <span>Group By</span>
          <Icon icon="mdi:filter-variant" width={16} className="text-[#6b6e76]" aria-hidden="true" />
          <select
            aria-label="Group by"
            value={groupBy}
            onChange={e => onGroupBy(e.target.value as GroupBy)}
            className="rounded-[4px] border border-[#dddedd] bg-white px-2 py-1.5 text-sm text-[#292a2e] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
          >
            {GROUP_BY_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <Icon icon="mdi:filter-outline" width={16} className="text-[#6b6e76]" aria-hidden="true" />
        {quickFilter(SEVERITY)}
        {quickFilter(STATUS)}
        <MoreFiltersPanel
          dimensions={MORE_FILTER_DIMENSIONS}
          labelFor={dimensionLabel}
          options={options}
          optionsLoading={loading}
          optionsError={false}
          filters={filters}
          labelMemory={labelMemory}
          onChange={onFilters}
          local
          consoleOnly={CONSOLE_ONLY_DIMENSIONS}
        />
      </div>
      <div className="flex h-8 flex-shrink-0 items-center gap-4">
        <div className="h-8 w-px bg-[#e0e0e0]" aria-hidden="true" />
        <button
          type="button"
          onClick={onToggleAnalytics}
          aria-pressed={analyticsShown}
          aria-label="Show analytics"
          title="Show analytics"
          className={`inline-flex h-8 items-center rounded-[4px] border px-1.5 transition-colors ${
            analyticsShown ? 'border-[#211be9] bg-[#ebf3ff] text-[#211be9]' : 'border-[#dddedd] bg-white text-[#6b6e76] hover:bg-[#f9f9f9]'
          }`}
        >
          <Icon icon="mdi:chart-box-outline" width={20} />
        </button>
      </div>
    </div>
  );
};

export default MisconfigurationToolbar;
