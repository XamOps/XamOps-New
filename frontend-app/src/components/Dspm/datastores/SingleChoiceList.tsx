import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOption } from '../../../types/dspmDatastores';
import { clearDimension, mergeSelectedOptions, type DatastoreFilterState } from './datastoreFilters';
import { SearchInput } from './ui/SearchInput';

export interface SingleChoiceListProps {
  dimension: string;
  /** This dimension's options from the latest filter-options response; null before the first. */
  options: DatastoreFilterOption[] | null;
  filters: DatastoreFilterState;
  loading: boolean;
  /** True when the filter-options request failed. */
  error: boolean;
  onChange: (next: DatastoreFilterState) => void;
  /** Whether more values exist than the response lists. */
  hasMore?: boolean;
  /** Value → label of every option this dimension has returned, for a chosen value the response left out. */
  labelMemory?: ReadonlyMap<string, string>;
  /** An option listed first that stands for "no choice" ("Any Access"): chosen when nothing is set. */
  anyLabel?: string;
  /** Whether a value can be chosen. A value it refuses renders disabled. Without it every value can be chosen. */
  canSelectValue?: (value: string) => boolean;
}

const EMPTY_OPTIONS: DatastoreFilterOption[] = [];
const NO_LABELS: ReadonlyMap<string, string> = new Map();

const OPTION = 'flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs';

/**
 * A single-choice filter: a search over the labels, then one option button per value. Choosing a value
 * sets it as the dimension's only Include value; choosing it again, or the "Any" option, clears the
 * dimension. A chosen value the response no longer returns stays listed, so it can be cleared.
 */
const SingleChoiceList: React.FC<SingleChoiceListProps> = ({
  dimension, options, filters, loading, error, onChange, hasMore = false, labelMemory = NO_LABELS, anyLabel,
  canSelectValue,
}) => {
  const [query, setQuery] = useState('');
  const selection = filters[dimension];
  const chosen = selection && selection.values.length > 0 ? selection.values[0] : null;
  const listed = useMemo(
    () => mergeSelectedOptions(options ?? EMPTY_OPTIONS, selection, labelMemory),
    [options, selection, labelMemory],
  );
  const needle = query.trim().toLowerCase();
  const visible = needle ? listed.filter(option => option.label.toLowerCase().includes(needle)) : listed;
  const showAny = anyLabel !== undefined && (!needle || anyLabel.toLowerCase().includes(needle));
  const clear = () => onChange(clearDimension(filters, dimension));
  const choose = (value: string) => {
    if (value === chosen) clear();
    else onChange({ ...filters, [dimension]: { mode: 'include', values: [value] } });
  };
  return (
    <div className="flex min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-[rgba(0,0,0,0.06)] p-2">
        <SearchInput value={query} onChange={setQuery} placeholder="Search" autoFocus className="min-w-0 flex-1" />
      </div>
      <div className="max-h-64 overflow-y-auto p-1">
        {error && <p className="px-2 py-3 text-center text-xs text-amber-700">Could not load options</p>}
        {!error && loading && options === null && (
          <p className="px-2 py-3 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading options…</p>
        )}
        {!loading && !error && options !== null && visible.length === 0 && !showAny && (
          <p className="px-2 py-4 text-center text-xs text-[rgba(0,0,0,0.35)]">No matches</p>
        )}
        <div role="listbox" aria-label={dimension} className="space-y-0.5">
          {showAny && (
            <button
              type="button"
              role="option"
              aria-selected={chosen === null}
              onClick={clear}
              className={`${OPTION} cursor-pointer hover:bg-[rgba(0,0,0,0.03)] ${chosen === null ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.7)]'}`}
            >
              <span className="min-w-0 flex-1 truncate">{anyLabel}</span>
              {chosen === null && <Icon icon="mdi:check" width={14} className="flex-shrink-0" />}
            </button>
          )}
          {visible.map(option => {
            const selected = option.value === chosen;
            const usable = selected || (canSelectValue?.(option.value) ?? true);
            return (
              <button
                key={`v:${option.value}`}
                type="button"
                role="option"
                aria-selected={selected}
                disabled={!usable}
                title={usable ? option.label : "This value can't be used as a filter"}
                onClick={() => choose(option.value)}
                className={`${OPTION} ${usable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.03)]' : 'cursor-not-allowed opacity-50'} ${
                  selected ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.7)]'
                }`}
              >
                <span className="min-w-0 flex-1 truncate">{option.label}</span>
                {selected && <Icon icon="mdi:check" width={14} className="flex-shrink-0" />}
              </button>
            );
          })}
        </div>
      </div>
      {hasMore && (
        <p className="border-t border-[rgba(0,0,0,0.06)] px-3 py-2 text-[10px] text-[rgba(0,0,0,0.4)]">
          Some values are not shown
        </p>
      )}
    </div>
  );
};

export default SingleChoiceList;
