import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { usePopoverEscape } from './usePopoverEscape';

interface ColumnsMenuProps<Id extends string> {
  /** Table order. The first column is always shown. */
  columns: ReadonlyArray<{ id: Id; label: string }>;
  hidden: ReadonlySet<Id>;
  onToggle: (id: Id) => void;
  /** Button text before the count: "Columns", or "Column" as Data Flow has it. */
  label?: string;
}

/**
 * The console's column chooser: "Columns N", N being the visible count, opening a list of checkboxes in
 * table order. The first column is checked, disabled and marked "Always shown".
 *
 * Closes on an outside click and on Escape. Escape is read by a React `onKeyDown` on the wrapper and
 * stopped there, so inside a drawer the first Escape closes the menu, not the drawer. The menu also
 * registers with the drawer's Escape stack while open, which covers Escape pressed after focus left it.
 */
export function ColumnsMenu<Id extends string>({ columns, hidden, onToggle, label = 'Columns' }: ColumnsMenuProps<Id>) {
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

  const shown = columns.filter((column, index) => index === 0 || !hidden.has(column.id)).length;

  return (
    <div className="relative" ref={wrapper} onKeyDown={onKeyDown}>
      <button
        ref={trigger}
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-haspopup="true"
        className="inline-flex items-center gap-1.5 rounded-lg px-1.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
      >
        <Icon icon="mdi:view-column-outline" width={14} />
        {label}{' '}
        <span className="rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] tabular-nums text-[rgba(0,0,0,0.55)]">{shown}</span>
      </button>
      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-60 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white p-1 shadow-lg">
          {columns.map((column, index) => {
            const locked = index === 0;
            return (
              <label
                key={column.id}
                className={`flex items-center gap-2 rounded px-2 py-1.5 text-xs text-[rgba(0,0,0,0.7)] ${locked ? '' : 'cursor-pointer hover:bg-[rgba(0,0,0,0.03)]'}`}
              >
                <input
                  type="checkbox"
                  checked={locked || !hidden.has(column.id)}
                  disabled={locked}
                  onChange={() => onToggle(column.id)}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                <span className={`flex-1 ${locked ? 'text-[rgba(0,0,0,0.5)]' : ''}`}>{column.label}</span>
                {locked && <>{' '}<span className="text-[10px] text-[rgba(0,0,0,0.35)]">Always shown</span></>}
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
}
