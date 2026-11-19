import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import type { DatastoreFilterState } from './datastoreFilters';
import FilterOptionList from './FilterOptionList';
import { usePopoverEscape } from './ui/usePopoverEscape';

interface Props {
  /** The dimensions to list, in order: the list page's `moreFiltersDimensions`, or a drawer's own set. */
  dimensions: string[];
  /** A dimension's name in the left list. */
  labelFor: (dimension: string) => string;
  options: DatastoreFilterOptions | null;
  optionsLoading: boolean;
  optionsError: boolean;
  filters: DatastoreFilterState;
  labelMemory: ReadonlyMap<string, ReadonlyMap<string, string>>;
  onChange: (next: DatastoreFilterState) => void;
  /** Passed to every dimension's `FilterOptionList`: Include only, values checked by `canSelectValue`. */
  local?: boolean;
  /** Local mode: which values of a dimension can be chosen, forwarded to that dimension's option list. */
  canSelectValue?: (dimension: string) => (value: string) => boolean;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/**
 * "+ More filters": a two-pane popover. The left list holds the given dimensions (with a badge of
 * selected values), the first one selected on open; the right pane is that dimension's options.
 *
 * Closes on an outside click and on Escape, read by a React `onKeyDown` and stopped there. Inside a
 * drawer the open panel is also on the drawer's Escape stack, as the other popovers are.
 *
 * The panel opens under the button, left-aligned. When that runs past the viewport's right edge (the Endpoint
 * drawer's filter row sits near it), it moves left until `VIEWPORT_MARGIN` is free on that side; a panel that
 * fits stays where it is.
 */
/** The space kept between a shifted panel and the viewport's edges. */
const VIEWPORT_MARGIN = 16;

const MoreFiltersPanel: React.FC<Props> = ({
  dimensions, labelFor, options, optionsLoading, optionsError, filters, labelMemory, onChange, local, canSelectValue,
}) => {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<string | null>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  /** How far left of the button the open panel starts, in px (0 when it fits). */
  const [shift, setShift] = useState(0);
  const close = useCallback(() => setOpen(false), []);
  usePopoverEscape(open, close, trigger);

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

  // Before paint, so a shifted panel never shows at its first place.
  useLayoutEffect(() => {
    const viewport = document.documentElement.clientWidth;
    if (!open || !panel.current || !wrapper.current || viewport === 0) {
      setShift(0);
      return;
    }
    const left = wrapper.current.getBoundingClientRect().left;
    const right = left + panel.current.offsetWidth;
    setShift(right <= viewport ? 0 : Math.max(VIEWPORT_MARGIN - left, viewport - VIEWPORT_MARGIN - right));
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
          ref={panel}
          role="dialog"
          aria-label="More filters"
          style={shift === 0 ? undefined : { left: shift }}
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
                    <span className="flex-1 truncate">{labelFor(key)}</span>
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
                local={local}
                canSelectValue={canSelectValue?.(current)}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default MoreFiltersPanel;
