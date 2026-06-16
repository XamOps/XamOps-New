import { useRef } from 'react';
import { Icon } from '@iconify/react';

// ─── Types ────────────────────────────────────────────────────────────────────

export type SortField = 'status' | 'priority' | 'createdAt' | 'name';
export type SortDirection = 'asc' | 'desc';

interface JobListControlsProps {
  sortBy: SortField;
  sortDirection: SortDirection;
  searchQuery: string;
  onSortChange: (field: SortField, direction: SortDirection) => void;
  onSearchChange: (query: string) => void;
  onNewJob: () => void;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const SORT_OPTIONS: { value: SortField; label: string }[] = [
  { value: 'status', label: 'Status' },
  { value: 'priority', label: 'Priority' },
  { value: 'createdAt', label: 'Created Date' },
  { value: 'name', label: 'Name' },
];

// ─── Component ────────────────────────────────────────────────────────────────

export function JobListControls({
  sortBy,
  sortDirection,
  searchQuery,
  onSortChange,
  onSearchChange,
  onNewJob,
}: JobListControlsProps) {
  const searchInputRef = useRef<HTMLInputElement>(null);

  /** Expose the search input ref so keyboard shortcuts can focus it */
  (JobListControls as any).__searchInputRef = searchInputRef;

  return (
    <div className="flex items-center gap-3 flex-wrap">
      {/* Sort Controls */}
      <div className="flex items-center gap-1.5">
        <label className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Sort</label>
        <select
          value={sortBy}
          onChange={(e) => onSortChange(e.target.value as SortField, sortDirection)}
          className="px-2.5 py-1.5 border border-[rgba(0,0,0,0.10)] rounded-lg text-[12px] font-medium bg-white focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none"
        >
          {SORT_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        <button
          onClick={() => onSortChange(sortBy, sortDirection === 'asc' ? 'desc' : 'asc')}
          className="w-7 h-7 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.10)] bg-white hover:bg-[rgba(0,0,0,0.03)] transition-colors"
          title={`Sort ${sortDirection === 'asc' ? 'ascending' : 'descending'}`}
        >
          <Icon
            icon={sortDirection === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'}
            className="text-sm text-[rgba(0,0,0,0.55)]"
          />
        </button>
      </div>

      {/* Search Input */}
      <div className="relative flex-1 min-w-[180px] max-w-xs">
        <Icon
          icon="mdi:magnify"
          className="absolute left-2.5 top-1/2 -translate-y-1/2 text-sm text-[rgba(0,0,0,0.3)]"
        />
        <input
          ref={searchInputRef}
          type="text"
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search jobs… (Ctrl+K)"
          className="w-full pl-8 pr-8 py-1.5 border border-[rgba(0,0,0,0.10)] rounded-lg text-[12px] bg-white focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none placeholder-[rgba(0,0,0,0.3)]"
        />
        {searchQuery && (
          <button
            onClick={() => onSearchChange('')}
            className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 rounded hover:bg-[rgba(0,0,0,0.05)] transition-colors"
          >
            <Icon icon="mdi:close" className="text-xs text-[rgba(0,0,0,0.4)]" />
          </button>
        )}
      </div>

      {/* New Job Button */}
      <button
        onClick={onNewJob}
        className="flex items-center gap-1.5 px-3.5 py-1.5 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[11px] font-bold rounded-lg shadow hover:shadow-md transition-all hover:scale-[1.02]"
        title="New Job (Ctrl+N)"
      >
        <Icon icon="mdi:plus" className="text-sm" />
        New Job
      </button>
    </div>
  );
}

/** Helper to get the search input ref for external focus (keyboard shortcuts) */
export function getSearchInputRef(): HTMLInputElement | null {
  return (JobListControls as any).__searchInputRef?.current ?? null;
}
