import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import type { DatastoreFilterState } from './datastoreFilters';
import { dimensionLabel, moreFiltersDimensions, type DatastoreTabConfig } from './datastoreTabConfig';
import FilterOptionList from './FilterOptionList';

interface Props {
  config: DatastoreTabConfig;
  options: DatastoreFilterOptions | null;
  optionsLoading: boolean;
  optionsError: boolean;
  filters: DatastoreFilterState;
  labelMemory: ReadonlyMap<string, ReadonlyMap<string, string>>;
  onChange: (next: DatastoreFilterState) => void;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/**
 * "+ More filters": a two-pane popover. The left list holds the tab's More-filters dimensions (with a
 * badge of selected values), the first one selected on open; the right pane is that dimension's options.
 * Closes on an outside click and on Escape, read by a React `onKeyDown` and stopped there.
 */
const MoreFiltersPanel: React.FC<Props> = ({
  config, options, optionsLoading, optionsError, filters, labelMemory, onChange,
}) => {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<string | null>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  const dimensions = useMemo(() => moreFiltersDimensions(config, options, filters), [config, options, filters]);
  const current = active !== null && dimensions.includes(active) ? active : (dimensions[0] ?? null);
  const dimension = current === null ? undefined : options?.dimensions.find(d => d.key === current);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapper.current && !wrapper.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Escape' || !open) return;
    e.stopPropagation();
    setOpen(false);
    trigger.current?.focus();
  };

  const toggleOpen = () => {
    if (!open) setActive(null);
    setOpen(!open);
  };

  return (
    <div className="relative" ref={wrapper} onKeyDown={onKeyDown}>
      <button
        ref={trigger}
        type="button"
        onClick={toggleOpen}
        aria-expanded={open}
        aria-haspopup="dialog"
        className="inline-flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
      >
        <Icon icon="mdi:plus" width={14} />
        More filters
      </button>
      {open && (
        <div
          role="dialog"
          aria-label="More filters"
          className="absolute left-0 top-full z-30 mt-1 flex w-[34rem] max-w-[90vw] rounded-xl border border-[rgba(0,0,0,0.1)] bg-white shadow-lg"
        >
          <ul className="w-44 flex-shrink-0 space-y-0.5 border-r border-[rgba(0,0,0,0.06)] p-1" aria-label="Filters">
            {dimensions.map(key => {
              const count = filters[key]?.values.length ?? 0;
              const selected = key === current;
              return (
                <li key={key}>
                  <button
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setActive(key)}
                    className={`flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors ${
                      selected ? 'bg-[rgba(0,0,0,0.06)] font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.03)]'
                    }`}
                  >
                    <span className="flex-1 truncate">{dimensionLabel(key)}</span>
                    {' '}
                    {count > 0 && (
                      <span className="rounded-full bg-[rgba(0,0,0,0.88)] px-1.5 text-[10px] tabular-nums text-white">{count}</span>
                    )}
                  </button>
                </li>
              );
            })}
            {dimensions.length === 0 && (
              <li className="px-2 py-3 text-xs text-[rgba(0,0,0,0.4)]">
                {optionsError ? 'Could not load options' : optionsLoading ? 'Loading…' : 'No filters available'}
              </li>
            )}
          </ul>
          <div className="min-w-0 flex-1">
            {current !== null && (
              <FilterOptionList
                key={current}
                dimension={current}
                options={options === null ? null : (dimension?.options ?? [])}
                hasMore={dimension?.hasMore ?? false}
                filters={filters}
                labelMemory={labelMemory.get(current) ?? NO_LABELS}
                loading={optionsLoading}
                error={optionsError}
                onChange={onChange}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default MoreFiltersPanel;
