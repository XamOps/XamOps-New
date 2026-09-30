import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmFilters } from '../../types/dspm';
import type { DspmFilterState, DspmSurface } from '../../hooks/useDspmData';

/**
 * Horizontal filter bar with dropdown chips, replacing the earlier left rail.
 *
 * Matches the vendor console: a search box, then one chip per dimension reading
 * `Account = Select…`, each opening a searchable checkbox panel, with `+ Add filters` to bring in the
 * less-used dimensions rather than showing all seven at once.
 *
 * <h3>Two deliberate differences from theirs</h3>
 * <b>Counts are shown.</b> Their options are bare labels; ours carry the number of matching assets,
 * because the API returns them for free and disjunctively — each dimension's counts reflect every
 * <em>other</em> active filter but not its own. So with `S3` selected the provider list still shows what
 * picking `RDS` instead would yield. Dropping that would be throwing away the most useful thing the
 * endpoint gives us.
 *
 * <b>No Include/Exclude toggle.</b> Theirs offers one. The API supports only OR-within-a-dimension and
 * AND-across-dimensions — there is no negation — so a toggle here would be a control that silently does
 * nothing.
 */

/** Dimension → chip label, using the console's wording where it differs from the API's. */
const DIMENSION_LABELS: Record<string, string> = {
  // "Account" in their bar. The backend resolves the Matters integration ids to the console's
  // `605134457560 (Xammer-prod)` form.
  integration_id: 'Account',
  // "Infrastructure" in their bar — S3, RDS, MSK, MongoDB Atlas, Databricks, DynamoDB.
  provider: 'Infrastructure',
  sensitive_type: 'Sensitives',
  scan_status: 'Scan status',
  severity: 'Risk level',
  // The specific detector that fired. Undocumented upstream and the most useful dimension here: it turns
  // "this bucket has PII" into "this bucket has 738 person names".
  data_element: 'Data element',
  lifecycle: 'Lifecycle',
};

/** "Infrastructure" only fits datastores; SaaS apps and endpoint agents are not infrastructure. */
const PROVIDER_LABEL: Record<DspmSurface, string> = {
  cloud: 'Infrastructure',
  saas: 'Application',
  endpoint: 'Source',
};

const dimensionLabel = (dimension: string, surface: DspmSurface) =>
  dimension === 'provider' ? PROVIDER_LABEL[surface] : (DIMENSION_LABELS[dimension] ?? dimension);

/** Chips shown without being added, mirroring each console screen's default bar. */
const DEFAULT_CHIPS: Record<DspmSurface, string[]> = {
  cloud: ['integration_id', 'provider'],
  saas: ['integration_id', 'sensitive_type'],
  endpoint: ['integration_id', 'provider'],
};

const SEARCH_PLACEHOLDER: Record<DspmSurface, string> = {
  cloud: 'Search',
  saas: 'Search Inventory',
  endpoint: 'Search',
};

/** Order chips and the add-menu follow. */
const CHIP_ORDER = [
  'integration_id', 'provider', 'sensitive_type', 'scan_status',
  'severity', 'data_element', 'lifecycle',
];

interface DropdownProps {
  dimension: string;
  label: string;
  options: DspmFilters['dimensions'][string];
  selected: string[];
  truncated: boolean;
  onToggle: (dimension: string, value: string) => void;
  onClear: (dimension: string) => void;
}

const FilterChip: React.FC<DropdownProps> = ({
  dimension, label, options, selected, truncated, onToggle, onClear,
}) => {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const wrapper = useRef<HTMLDivElement>(null);

  // Close on outside click and on Escape. Without the outside click a user has to find the chip again to
  // dismiss the panel, which is the one interaction people try first.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapper.current && !wrapper.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return options;
    return options.filter(o => o.label.toLowerCase().includes(needle)
      || o.value.toLowerCase().includes(needle));
  }, [options, query]);

  const allVisibleSelected = visible.length > 0 && visible.every(o => selected.includes(o.value));

  const summary = selected.length === 0
    ? 'Select…'
    : selected.length === 1
      ? (options.find(o => o.value === selected[0])?.label ?? selected[0])
      : `${selected.length} selected`;

  return (
    <div className="relative" ref={wrapper}>
      <button
        onClick={() => setOpen(o => !o)}
        className={`inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-xs transition-colors
          ${selected.length > 0
            ? 'border-[rgba(0,0,0,0.25)] bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)]'
            : 'border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'}`}
      >
        <span className="text-[rgba(0,0,0,0.5)]">{label}</span>
        <span className="text-[rgba(0,0,0,0.3)]">=</span>
        <span className={`max-w-[10rem] truncate ${selected.length > 0 ? 'font-semibold' : ''}`}>
          {summary}
        </span>
        <Icon icon="mdi:chevron-down" width={12} className="text-[rgba(0,0,0,0.35)]" />
      </button>

      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-72 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white shadow-lg">
          <div className="flex items-center gap-2 border-b border-[rgba(0,0,0,0.06)] p-2">
            <div className="relative flex-1">
              <Icon
                icon="mdi:magnify" width={12}
                className="absolute left-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
              />
              <input
                autoFocus
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder="Search"
                className="w-full rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] py-1 pl-7 pr-2 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
              />
            </div>
            {selected.length > 0 && (
              <button
                onClick={() => onClear(dimension)}
                className="flex-shrink-0 text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.75)]"
              >
                Clear
              </button>
            )}
          </div>

          <div className="max-h-64 overflow-y-auto p-1">
            {visible.length === 0 && (
              <p className="px-2 py-4 text-center text-xs text-[rgba(0,0,0,0.35)]">No matches</p>
            )}

            {visible.length > 0 && (
              <label className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 hover:bg-[rgba(0,0,0,0.03)]">
                <input
                  type="checkbox"
                  checked={allVisibleSelected}
                  // Toggles only what is visible, so "Select all" under an active search means
                  // "everything I can see" rather than silently selecting hidden values too.
                  onChange={() => visible.forEach(o => {
                    const isOn = selected.includes(o.value);
                    if (allVisibleSelected ? isOn : !isOn) onToggle(dimension, o.value);
                  })}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                <span className="text-xs font-semibold text-[rgba(0,0,0,0.7)]">Select all</span>
              </label>
            )}

            {visible.map(option => (
              <label
                key={option.value || '__blank__'}
                className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 hover:bg-[rgba(0,0,0,0.03)]"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(option.value)}
                  onChange={() => onToggle(dimension, option.value)}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                <span
                  className="min-w-0 flex-1 truncate text-xs text-[rgba(0,0,0,0.7)]"
                  title={option.value ? `${option.label} (${option.value})` : option.label}
                >
                  {option.label}
                </span>
                <span className="flex-shrink-0 text-[10px] tabular-nums text-[rgba(0,0,0,0.35)]">
                  {option.count.toLocaleString()}
                </span>
              </label>
            ))}
          </div>

          {truncated && (
            // The vendor paginates its own facet lists and gives no cursor to follow on the asset
            // endpoint. A list that silently omitted accounts would be worse than one that says so.
            <p className="border-t border-[rgba(0,0,0,0.06)] px-3 py-2 text-[10px] text-[rgba(0,0,0,0.4)]">
              Some values are not shown. Use search to narrow instead.
            </p>
          )}
        </div>
      )}
    </div>
  );
};

/** The `+ Add filters` menu. */
const AddFilters: React.FC<{
  available: string[];
  surface: DspmSurface;
  onAdd: (dimension: string) => void;
}> = ({ available, surface, onAdd }) => {
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapper.current && !wrapper.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  if (available.length === 0) return null;

  return (
    <div className="relative" ref={wrapper}>
      <button
        onClick={() => setOpen(o => !o)}
        className="inline-flex items-center gap-1 px-1.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.5)] transition-colors hover:text-[rgba(0,0,0,0.8)]"
      >
        <Icon icon="mdi:plus" width={12} />
        Add filters
      </button>

      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-48 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white p-1 shadow-lg">
          {available.map(dimension => (
            <button
              key={dimension}
              onClick={() => { onAdd(dimension); setOpen(false); }}
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)]"
            >
              {dimensionLabel(dimension, surface)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

interface Props {
  surface: DspmSurface;
  filters: DspmFilters | null;
  selected: DspmFilterState;
  activeCount: number;
  search: string;
  onSearch: (value: string) => void;
  onToggle: (dimension: string, value: string) => void;
  onClearDimension: (dimension: string) => void;
  onClearAll: () => void;
}

const DspmFilterBar: React.FC<Props> = ({
  surface, filters, selected, activeCount, search, onSearch, onToggle, onClearDimension, onClearAll,
}) => {
  const [pinned, setPinned] = useState<string[]>(DEFAULT_CHIPS[surface]);

  /**
   * Dimensions rendered as chips.
   *
   * A dimension with an active selection is always shown, even if it was never pinned — otherwise a
   * filter restored from a shared URL would narrow the table with no visible control to undo it.
   *
   * A pinned chip whose dimension came back empty is hidden: endpoints have no Account values, and a
   * dropdown with nothing in it reads as broken. Only once filters have loaded, so chips do not flicker.
   */
  const shown = useMemo(() => CHIP_ORDER.filter(d => {
    if ((selected[d]?.length ?? 0) > 0) return true;
    if (!pinned.includes(d)) return false;
    return filters === null || (filters.dimensions[d]?.length ?? 0) > 0;
  }), [pinned, selected, filters]);

  // Only offer dimensions the vendor actually returned for this project. A customer with no on-prem
  // datastores should not be offered a Lifecycle filter with nothing in it.
  const available = CHIP_ORDER.filter(d =>
    !shown.includes(d) && (filters?.dimensions[d]?.length ?? 0) > 0);

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white px-3 py-2.5">
      <div className="relative">
        <Icon
          icon="mdi:magnify" width={13}
          className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
        />
        <input
          type="search"
          value={search}
          onChange={e => onSearch(e.target.value)}
          placeholder={SEARCH_PLACEHOLDER[surface]}
          aria-label={SEARCH_PLACEHOLDER[surface]}
          className="w-56 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] py-1.5 pl-8 pr-3 text-xs text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
        />
      </div>

      {shown.map(dimension => (
        <FilterChip
          key={dimension}
          dimension={dimension}
          label={dimensionLabel(dimension, surface)}
          options={filters?.dimensions[dimension] ?? []}
          selected={selected[dimension] ?? []}
          truncated={Boolean(filters?.hasMore[dimension])}
          onToggle={onToggle}
          onClear={onClearDimension}
        />
      ))}

      <AddFilters
        available={available}
        surface={surface}
        onAdd={d => setPinned(p => (p.includes(d) ? p : [...p, d]))}
      />

      {activeCount > 0 && (
        <button
          onClick={onClearAll}
          className="ml-auto inline-flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.8)]"
        >
          <Icon icon="mdi:close" width={11} />
          Clear all ({activeCount})
        </button>
      )}
    </div>
  );
};

export default DspmFilterBar;
