import React, { useMemo, useState } from 'react';
import type { DatastoreFilterOption } from '../../../types/dspmDatastores';
import {
  EXCLUDABLE_DIMENSIONS, MAX_FILTER_VALUES, clearDimension, isSelectable, mergeSelectedOptions,
  selectAllVisible, setMode, toggleValue, type DatastoreFilterState, type FilterMode,
} from './datastoreFilters';
import { SearchInput } from './ui/SearchInput';

export interface FilterOptionListProps {
  dimension: string;
  /** This dimension's options from the latest filter-options response; null before the first. */
  options: DatastoreFilterOption[] | null;
  hasMore: boolean;
  filters: DatastoreFilterState;
  /** Value → label of every option this dimension has returned. */
  labelMemory: ReadonlyMap<string, string>;
  loading: boolean;
  /** True when the filter-options request failed. */
  error: boolean;
  onChange: (next: DatastoreFilterState) => void;
  /**
   * Local mode, for a filter over rows already loaded (the drawer's Risk severities): Include only, so
   * there is no Include / Exclude choice, and every listed value can be chosen, because none is sent to
   * the backend and its value rules do not apply.
   */
  local?: boolean;
}

const EMPTY_OPTIONS: DatastoreFilterOption[] = [];

/**
 * One dimension's options with the console's controls: a search over the labels, Include / Exclude,
 * "Select all" and a checkbox per option. Shared by the quick-filter dropdowns and the More-filters pane.
 *
 * Selected values the response no longer returns (always the case for excluded ones) stay listed and
 * checked, so they can be cleared. Values that cannot be sent as a filter render disabled, and once 50
 * values are selected the remaining boxes are disabled too.
 */
const FilterOptionList: React.FC<FilterOptionListProps> = ({
  dimension, options, hasMore, filters, labelMemory, loading, error, onChange, local = false,
}) => {
  const [query, setQuery] = useState('');
  const [stoppedShort, setStoppedShort] = useState(false);

  const selection = filters[dimension];
  const mode: FilterMode = selection?.mode ?? 'include';
  const selectedCount = selection?.values.length ?? 0;
  const atLimit = selectedCount >= MAX_FILTER_VALUES;
  const excludable = EXCLUDABLE_DIMENSIONS.has(dimension);

  const listed = useMemo(
    () => mergeSelectedOptions(options ?? EMPTY_OPTIONS, selection, labelMemory),
    [options, selection, labelMemory],
  );
  const needle = query.trim().toLowerCase();
  const visible = needle ? listed.filter(option => option.label.toLowerCase().includes(needle)) : listed;
  const canSelect = (option: { value: string }) => local || isSelectable(dimension, option);
  const selectable = visible.filter(canSelect);
  const allVisibleChecked = selectable.length > 0 && selectable.every(option => option.checked);

  const apply = (next: DatastoreFilterState) => { if (next !== filters) onChange(next); };

  const onSelectAll = () => {
    if (allVisibleChecked) {
      apply(selectable.reduce((state, option) => toggleValue(state, dimension, option.value), filters));
      setStoppedShort(false);
      return;
    }
    const result = selectAllVisible(filters, dimension, visible, canSelect);
    apply(result.state);
    setStoppedShort(result.stoppedShort);
  };

  return (
    <div className="flex min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-[rgba(0,0,0,0.06)] p-2">
        <SearchInput value={query} onChange={setQuery} placeholder="Search" autoFocus className="min-w-0 flex-1" />
        {!local && (
          <select
            aria-label="Include or exclude"
            value={mode}
            onChange={e => apply(setMode(filters, dimension, e.target.value as FilterMode))}
            className="rounded-lg border border-[rgba(0,0,0,0.1)] bg-white px-1.5 py-1.5 text-xs text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
          >
            <option value="include">Include</option>
            <option
              value="exclude"
              disabled={!excludable}
              title={excludable ? undefined : 'Exclude is not available for this filter'}
            >
              Exclude
            </option>
          </select>
        )}
      </div>

      <div className="max-h-64 overflow-y-auto p-1">
        {error && <p className="px-2 py-3 text-center text-xs text-amber-700">Could not load options</p>}
        {!error && loading && options === null && (
          <p className="px-2 py-3 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading options…</p>
        )}

        {visible.length > 0 && (
          <div className="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-[rgba(0,0,0,0.03)]">
            <label className="flex flex-1 cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={allVisibleChecked}
                disabled={selectable.length === 0}
                onChange={onSelectAll}
                className="accent-[rgba(0,0,0,0.88)]"
              />
              <span className="text-xs font-semibold text-[rgba(0,0,0,0.7)]">Select all</span>
            </label>
            {selectedCount > 0 && (
              <button
                type="button"
                onClick={() => { setStoppedShort(false); apply(clearDimension(filters, dimension)); }}
                className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.75)]"
              >
                Clear
              </button>
            )}
          </div>
        )}
        {(atLimit || stoppedShort) && (
          <p className="px-2 pb-1 text-[11px] text-[rgba(0,0,0,0.4)]">Up to {MAX_FILTER_VALUES} values per filter</p>
        )}

        {!loading && !error && options !== null && visible.length === 0 && (
          <p className="px-2 py-4 text-center text-xs text-[rgba(0,0,0,0.35)]">No matches</p>
        )}

        {visible.map(option => {
          const usable = canSelect(option);
          return (
            <label
              key={`v:${option.value}`}
              title={usable ? undefined : "This value can't be used as a filter"}
              className={`flex items-center gap-2 rounded px-2 py-1.5 ${usable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.03)]' : 'cursor-not-allowed opacity-50'}`}
            >
              <input
                type="checkbox"
                checked={option.checked}
                disabled={!usable || (!option.checked && atLimit)}
                onChange={() => apply(toggleValue(filters, dimension, option.value))}
                className="accent-[rgba(0,0,0,0.88)]"
              />
              <span className="min-w-0 flex-1 truncate text-xs text-[rgba(0,0,0,0.7)]" title={option.label}>{option.label}</span>
              {' '}
              {option.count !== null && (
                <span className="flex-shrink-0 text-[10px] tabular-nums text-[rgba(0,0,0,0.35)]">
                  {option.count.toLocaleString('en-US')}
                </span>
              )}
            </label>
          );
        })}
      </div>

      {hasMore && (
        <p className="border-t border-[rgba(0,0,0,0.06)] px-3 py-2 text-[10px] text-[rgba(0,0,0,0.4)]">
          Some values are not shown
        </p>
      )}
    </div>
  );
};

export default FilterOptionList;
