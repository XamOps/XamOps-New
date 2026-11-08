import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import FilterOptionList, { type FilterOptionListProps } from './FilterOptionList';
import { usePopoverEscape } from './ui/usePopoverEscape';

interface Props extends FilterOptionListProps {
  label: string;
  /** Iconify id drawn before the label (the Risk tab's funnel). */
  icon?: string;
}

/**
 * A quick-filter button with its option list in a popover. The badge counts the selected values.
 *
 * The popover closes on an outside click and on Escape. Escape is read by a React `onKeyDown` on the
 * wrapper and stopped there (no document listener), so inside a drawer it closes the popover and not the
 * drawer; while open, the popover is also on the drawer's Escape stack.
 */
const FilterDropdown: React.FC<Props> = ({ label, icon, ...listProps }) => {
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const close = useCallback(() => setOpen(false), []);
  usePopoverEscape(open, close, trigger);

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

  const count = listProps.filters[listProps.dimension]?.values.length ?? 0;

  return (
    <div className="relative" ref={wrapper} onKeyDown={onKeyDown}>
      <button
        ref={trigger}
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-haspopup="dialog"
        className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition-colors ${
          count > 0
            ? 'border-[rgba(0,0,0,0.25)] bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)]'
            : 'border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
        }`}
      >
        {icon && <Icon icon={icon} width={14} className="text-[rgba(0,0,0,0.45)]" />}
        {label}{' '}
        {count > 0 && (
          <span className="rounded-full bg-[rgba(0,0,0,0.88)] px-1.5 text-[10px] tabular-nums text-white">{count}</span>
        )}
        <Icon icon="mdi:chevron-down" width={12} className="text-[rgba(0,0,0,0.35)]" />
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={label}
          className="absolute left-0 top-full z-30 mt-1 w-72 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white shadow-lg"
        >
          <FilterOptionList {...listProps} />
        </div>
      )}
    </div>
  );
};

export default FilterDropdown;
