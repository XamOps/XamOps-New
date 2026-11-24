import React, { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import type { DatastoreFilterState } from './datastoreFilters';
import FilterOptionList from './FilterOptionList';
import { ConsoleOnlyButton } from './ui/ConsoleOnly';
import SingleChoiceList from './SingleChoiceList';
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
  /** Dimensions the API has no data for: listed in their place, greyed with "Disabled by admin", never opened. */
  consoleOnly?: ReadonlySet<string>;
  /** A visible heading over the two panes. Default none. */
  title?: string;
  /** Which dimensions are single choice (`SingleChoiceList`). Default none. */
  singleChoice?: (dimension: string) => boolean;
  /** A single-choice dimension's "no choice" option label, if it has one. */
  anyLabelFor?: (dimension: string) => string | undefined;
  /** How a dimension draws its option labels; undefined keeps the plain text. */
  renderLabelFor?: (dimension: string) => ((label: string) => ReactNode) | undefined;
  /** Local mode: which dimensions show Include / Exclude with Exclude disabled (`FilterOptionList`). */
  consoleOnlyExcludeFor?: (dimension: string) => boolean;
}

const NO_LABELS: ReadonlyMap<string, string> = new Map();

/**
 * "+ More filters": a two-pane popover. The left list holds the given dimensions (with a badge of
 * selected values), the first one selected on open; the right pane is that dimension's options.
 *
 * Closes on an outside click and on Escape, read by a React `onKeyDown` and stopped there. Inside a
 * drawer the open panel is also on the drawer's Escape stack, as the other popovers are.
 *
 * The panel opens under the button, left-aligned. When that runs past the right edge of its visible area (the
 * nearest scroll container, such as a drawer body, clipped to the viewport; the Endpoint drawer's filter row sits
 * near it), it moves left until `VIEWPORT_MARGIN` is free on that side; a panel that fits stays where it is.
 *
 * At its first, unshifted place the panel can run past that container, and the search box's autofocus then
 * scrolls the container sideways before the panel is placed. The placement puts the container's scrollLeft back
 * first, so opening the panel never moves the content behind it.
 */
/** The space kept between a shifted panel and the edges of its visible area. */
const VIEWPORT_MARGIN = 16;
/** The nearest ancestor that clips its content sideways, or null when only the viewport does. */
function clippingParent(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
    if (/auto|scroll|hidden|clip/.test(getComputedStyle(p).overflowX)) return p;
  }
  return null;
}

const MoreFiltersPanel: React.FC<Props> = ({
  dimensions, labelFor, options, optionsLoading, optionsError, filters, labelMemory, onChange, local, canSelectValue,
  consoleOnly,
  title, singleChoice, anyLabelFor, renderLabelFor, consoleOnlyExcludeFor,
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

  const openable = consoleOnly ? dimensions.filter(key => !consoleOnly.has(key)) : dimensions;
  const current = active !== null && openable.includes(active) ? active : (openable[0] ?? null);
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
      // Undo the sideways scroll the search box's autofocus caused, then measure the container's visible box.
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

  // The left list and the selected dimension's options; under the title when there is one.
  const panes = (
    <>
      <ul className="w-44 flex-shrink-0 space-y-0.5 border-r border-[rgba(0,0,0,0.06)] p-1" aria-label="Filters">
        {dimensions.map(key => {
          if (consoleOnly?.has(key)) {
            return (
              <li key={key}>
                <ConsoleOnlyButton variant="menu" placement="right">
                  <span className="flex-1 truncate">{labelFor(key)}</span>
                </ConsoleOnlyButton>
              </li>
            );
          }
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
        {current !== null && (singleChoice?.(current) ? (
          <SingleChoiceList
            key={current}
            dimension={current}
            options={options === null ? null : (dimension?.options ?? [])}
            hasMore={dimension?.hasMore ?? false}
            filters={filters}
            labelMemory={labelMemory.get(current) ?? NO_LABELS}
            loading={optionsLoading}
            error={optionsError}
            onChange={onChange}
            anyLabel={anyLabelFor?.(current)}
            canSelectValue={canSelectValue?.(current)}
          />
        ) : (
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
            renderLabel={renderLabelFor?.(current)}
            consoleOnlyExclude={consoleOnlyExcludeFor?.(current) ?? false}
          />
        ))}
      </div>
    </>
  );
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
          className={`absolute left-0 top-full z-30 mt-1 flex w-[34rem] max-w-[90vw] rounded-xl border border-[rgba(0,0,0,0.1)] bg-white shadow-lg${title ? ' flex-col' : ''}`}
        >
          {title ? (
            <>
              <h3 className="border-b border-[rgba(0,0,0,0.06)] px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.88)]">{title}</h3>
              <div className="flex min-h-0">
                {panes}
              </div>
            </>
          ) : panes}
        </div>
      )}
    </div>
  );
};

export default MoreFiltersPanel;
