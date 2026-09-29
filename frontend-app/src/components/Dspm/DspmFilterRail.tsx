import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmFilters } from '../../types/dspm';
import { DSPM_FILTER_DIMENSIONS, type DspmFilterState } from '../../hooks/useDspmData';

/** Headings for each dimension, including the three the vendor documentation omits. */
const DIMENSION_LABELS: Record<string, string> = {
  severity: 'Severity',
  scan_status: 'Scan status',
  provider: 'Provider',
  sensitive_type: 'Sensitive data',
  // The specific detector that fired. Undocumented upstream but the most useful dimension here — it is
  // what turns "this bucket has PII" into "this bucket has 738 person names".
  data_element: 'Data element',
  integration_id: 'Source',
  resource_type: 'Resource type',
  lifecycle: 'Lifecycle',
};

/** How many options to show before collapsing behind a "show all" toggle. */
const COLLAPSE_AFTER = 6;

interface Props {
  filters: DspmFilters | null;
  selected: DspmFilterState;
  activeCount: number;
  loading: boolean;
  onToggle: (dimension: string, value: string) => void;
  onClear: () => void;
}

const DimensionBlock: React.FC<{
  dimension: string;
  options: DspmFilters['dimensions'][string];
  selectedValues: string[];
  truncated: boolean;
  onToggle: (dimension: string, value: string) => void;
}> = ({ dimension, options, selectedValues, truncated, onToggle }) => {
  const [expanded, setExpanded] = useState(false);
  const visible = expanded ? options : options.slice(0, COLLAPSE_AFTER);

  return (
    <div className="border-b border-[rgba(0,0,0,0.06)] px-4 py-3 last:border-b-0">
      <div className="mb-2 flex items-center gap-1.5">
        <h4 className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">
          {DIMENSION_LABELS[dimension] ?? dimension}
        </h4>
        {truncated && (
          // The vendor paginates its own facet lists and offers no cursor on the asset endpoint to
          // follow. A rail that silently omitted providers would be worse than one that admits it.
          <span
            className="inline-flex text-[rgba(0,0,0,0.3)]"
            title="This list is truncated upstream — some values are not shown. Use search to narrow instead."
          >
            <Icon icon="mdi:information-outline" width={11} />
          </span>
        )}
      </div>

      <ul className="space-y-0.5">
        {visible.map(option => {
          const checked = selectedValues.includes(option.value);
          return (
            <li key={option.value || '__blank__'}>
              <label className="flex cursor-pointer items-center gap-2 rounded px-1 py-1 hover:bg-[rgba(0,0,0,0.03)]">
                <input
                  type="checkbox" checked={checked}
                  onChange={() => onToggle(dimension, option.value)}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                <span
                  className={`flex-1 truncate text-xs ${checked ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.65)]'}`}
                  title={option.label}
                >
                  {option.label}
                </span>
                <span className="text-[10px] tabular-nums text-[rgba(0,0,0,0.35)]">
                  {option.count.toLocaleString()}
                </span>
              </label>
            </li>
          );
        })}
      </ul>

      {options.length > COLLAPSE_AFTER && (
        <button
          onClick={() => setExpanded(e => !e)}
          className="mt-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]"
        >
          {expanded ? 'Show less' : `Show all ${options.length}`}
        </button>
      )}
    </div>
  );
};

/**
 * The filter rail.
 *
 * Counts here are **disjunctive**: each dimension's numbers reflect every other active filter but not its
 * own. That is upstream behaviour worth preserving rather than working around, because it is what lets a
 * user who has selected `S3` still see what picking `EBS` instead would yield. It only holds because the
 * list and filter calls are issued with identical filter parameters.
 */
const DspmFilterRail: React.FC<Props> = ({
  filters, selected, activeCount, loading, onToggle, onClear,
}) => (
  <aside className="w-60 flex-shrink-0 self-start rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
    <header className="flex items-center gap-2 border-b border-[rgba(0,0,0,0.06)] px-4 py-3">
      <Icon icon="mdi:filter-variant" width={14} className="text-[rgba(0,0,0,0.5)]" />
      <h3 className="text-xs font-bold text-[rgba(0,0,0,0.88)]">Filters</h3>
      {activeCount > 0 && (
        <button
          onClick={onClear}
          className="ml-auto text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.75)]"
        >
          Clear ({activeCount})
        </button>
      )}
    </header>

    {loading && !filters && (
      <p className="px-4 py-6 text-center text-xs text-[rgba(0,0,0,0.35)]">Loading filters…</p>
    )}

    {filters && DSPM_FILTER_DIMENSIONS
      // Dimensions the vendor did not return for this project are omitted rather than rendered empty —
      // a customer with no on-prem datastores should not see an empty Lifecycle block.
      .filter(dimension => (filters.dimensions[dimension]?.length ?? 0) > 0)
      .map(dimension => (
        <DimensionBlock
          key={dimension}
          dimension={dimension}
          options={filters.dimensions[dimension]}
          selectedValues={selected[dimension] ?? []}
          truncated={Boolean(filters.hasMore[dimension])}
          onToggle={onToggle}
        />
      ))}
  </aside>
);

export default DspmFilterRail;
