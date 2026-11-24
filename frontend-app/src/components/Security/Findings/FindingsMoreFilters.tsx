import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import FilterOptionList from '../../Dspm/datastores/FilterOptionList';
import type { DatastoreFilterState } from '../../Dspm/datastores/datastoreFilters';
import { usePopoverEscape } from '../../Dspm/datastores/ui/usePopoverEscape';

interface Props {
  /** The dimensions to list, in order. */
  dimensions: readonly string[];
  /** A dimension's name in the left list. */
  labelFor: (dimension: string) => string;
  /** Null until the findings have loaded. */
  options: DatastoreFilterOptions | null;
  loading: boolean;
  filters: DatastoreFilterState;
  labelMemory: ReadonlyMap<string, ReadonlyMap<string, string>>;
  onChange: (next: DatastoreFilterState) => void;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/** The space kept between a shifted panel and the edges of its visible area. */
const VIEWPORT_MARGIN = 16;

/** The nearest ancestor that clips its content sideways, or null when only the viewport does. */
function clippingParent(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
    if (/auto|scroll|hidden|clip/.test(getComputedStyle(p).overflowX)) return p;
  }
  return null;
}

/**
 * "+ More filters" over rows already loaded: a two-pane popover whose left list holds the dimensions
 * (with a badge of selected values), the first one selected on open, and whose right pane is that
 * dimension's options in local mode (Include only).
 *
 * The XamLens Misconfigurations page's panel. The shared `MoreFiltersPanel` here takes a Datastores tab
 * config rather than a list of dimensions, so the Findings tab keeps this copy. It closes on an outside
 * click and on Escape, read by a React `onKeyDown` and stopped there. The panel opens under the button,
 * left-aligned, and moves left when that would run past the right edge of its visible area.
 */
const FindingsMoreFilters: React.FC<Props> = ({
  dimensions, labelFor, options, loading, filters, labelMemory, onChange,
}) => {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<string | null>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  /** How far left of the button the open panel starts, in px (0 when it fits). */
  const [shift, setShift] = useState(0);
  /** The clipping container's scrollLeft when the panel was opened. */
  const scrollLeftAtOpen = useRef(0);
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
    let minX = 0;
    let maxX = viewport;
    const clip = clippingParent(wrapper.current);
    if (clip) {
      // The search box's autofocus can scroll the container sideways before this runs: undo it first.
      if (clip.scrollLeft !== scrollLeftAtOpen.current) clip.scrollLeft = scrollLeftAtOpen.current;
      const box = clip.getBoundingClientRect();
      minX = Math.max(minX, box.left + clip.clientLeft);
      maxX = Math.min(maxX, box.left + clip.clientLeft + clip.clientWidth);
    }
    const left = wrapper.current.getBoundingClientRect().left;
    const right = left + panel.current.offsetWidth;
    setShift(right <= maxX ? 0 : Math.max(minX + VIEWPORT_MARGIN - left, maxX - VIEWPORT_MARGIN - right));
  }, [open]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Escape' || !open) return;
    e.stopPropagation();
    setOpen(false);
    trigger.current?.focus();
  };

  const toggleOpen = () => {
    if (!open) {
      setActive(null);
      const clip = wrapper.current && clippingParent(wrapper.current);
      scrollLeftAtOpen.current = clip ? clip.scrollLeft : 0;
    }
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
          </ul>
          <div className="min-w-0 flex-1">
            {current !== null && (
              <FilterOptionList
                key={current}
                dimension={current}
                options={options === null ? null : (dimension?.options ?? [])}
                hasMore={false}
                filters={filters}
                labelMemory={labelMemory.get(current) ?? NO_LABELS}
                loading={loading}
                error={false}
                onChange={onChange}
                local
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default FindingsMoreFilters;
