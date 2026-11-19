import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import FilterDropdown from '../datastores/FilterDropdown';
import MoreFiltersPanel from '../datastores/MoreFiltersPanel';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastores/datastoreFilters';
import { SearchInput } from '../datastores/ui/SearchInput';
import {
  GROUP_BY_OPTIONS, MORE_FILTER_DIMENSIONS, SEVERITY, dimensionLabel, type GroupBy,
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
 * Search over titles, Group By, the Severity quick filter, "+ More filters" (Source, Framework, Cloud
 * Account) and the chart button that shows or hides the cards. Every filter is local: the page already
 * holds every row. Status and Category are left out: the API has neither.
 */
const MisconfigurationToolbar: React.FC<Props> = ({
  search, onSearch, groupBy, onGroupBy, options, loading, labelMemory, filters, onFilters, analyticsShown,
  onToggleAnalytics,
}) => {
  const severity = options?.dimensions.find(d => d.key === SEVERITY);
  return (
    <div className="flex flex-wrap items-center gap-3">
      <SearchInput value={search} onChange={onSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
      <label className="inline-flex items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
        <span>Group By</span>
        <select
          aria-label="Group by"
          value={groupBy}
          onChange={e => onGroupBy(e.target.value as GroupBy)}
          className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-2 py-1.5 text-xs font-normal text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
        >
          {GROUP_BY_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </label>
      <Icon icon="mdi:filter-outline" width={16} className="text-[rgba(0,0,0,0.45)]" aria-hidden="true" />
      <FilterDropdown
        label={dimensionLabel(SEVERITY)}
        local
        dimension={SEVERITY}
        options={options === null ? null : (severity?.options ?? [])}
        hasMore={false}
        filters={filters}
        labelMemory={labelMemory.get(SEVERITY) ?? NO_LABELS}
        loading={loading}
        error={false}
        onChange={onFilters}
      />
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
      />
      <button
        type="button"
        onClick={onToggleAnalytics}
        aria-pressed={analyticsShown}
        aria-label="Show analytics"
        title="Show analytics"
        className={`ml-auto inline-flex items-center rounded-lg p-1.5 transition-colors ${
          analyticsShown ? 'bg-sky-50 text-sky-700' : 'text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.04)]'
        }`}
      >
        <Icon icon="mdi:chart-box-outline" width={18} />
      </button>
    </div>
  );
};

export default MisconfigurationToolbar;
