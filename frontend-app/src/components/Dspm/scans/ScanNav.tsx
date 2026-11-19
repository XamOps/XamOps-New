import React, { useRef } from 'react';

export type ScanTabId = 'configurations' | 'scopes' | 'schedules' | 'history';

/** The console's left nav: the primary pair, a divider, then the secondary pair. */
export const SCAN_TABS: ReadonlyArray<{ id: ScanTabId; label: string }> = [
  { id: 'configurations', label: 'Scan Configurations' },
  { id: 'scopes', label: 'Scan Scopes' },
  { id: 'schedules', label: 'Schedules' },
  { id: 'history', label: 'Scan History' },
];

/** The divider sits after this tab. */
const DIVIDER_AFTER: ScanTabId = 'scopes';

interface Props {
  active: ScanTabId;
  onSelect: (id: ScanTabId) => void;
  /** From `useId()`. Tab ids are `${idPrefix}-tab-${id}`, panel ids `${idPrefix}-panel-${id}`. */
  idPrefix: string;
}

/**
 * Scan Management's vertical tab list (design 6.8). Only the selected tab is in the Tab order; ArrowUp
 * and ArrowDown move focus and the selection to the previous or next tab (wrapping), Home and End to the
 * first and last.
 */
const ScanNav: React.FC<Props> = ({ active, onSelect, idPrefix }) => {
  const buttons = useRef<Partial<Record<ScanTabId, HTMLButtonElement | null>>>({});

  const move = (to: number) => {
    const next = SCAN_TABS[(to + SCAN_TABS.length) % SCAN_TABS.length].id;
    onSelect(next);
    buttons.current[next]?.focus();
  };

  const onKeyDown = (e: React.KeyboardEvent, index: number) => {
    let to: number | null = null;
    if (e.key === 'ArrowDown') to = index + 1;
    else if (e.key === 'ArrowUp') to = index - 1;
    else if (e.key === 'Home') to = 0;
    else if (e.key === 'End') to = SCAN_TABS.length - 1;
    if (to === null) return;
    e.preventDefault();
    move(to);
  };

  return (
    <div
      role="tablist"
      aria-orientation="vertical"
      aria-label="Scan Management"
      className="flex w-48 flex-shrink-0 flex-col gap-0.5 border-r border-[rgba(0,0,0,0.06)] p-3"
    >
      {SCAN_TABS.map((tab, index) => (
        <React.Fragment key={tab.id}>
          <button
            ref={el => { buttons.current[tab.id] = el; }}
            id={`${idPrefix}-tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={tab.id === active}
            aria-controls={`${idPrefix}-panel-${tab.id}`}
            tabIndex={tab.id === active ? 0 : -1}
            onClick={() => onSelect(tab.id)}
            onKeyDown={e => onKeyDown(e, index)}
            className={`rounded-lg px-3 py-2 text-left text-xs font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${
              tab.id === active
                ? 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.88)]'
                : 'text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)] hover:text-[rgba(0,0,0,0.8)]'
            }`}
          >
            {tab.label}
          </button>
          {tab.id === DIVIDER_AFTER && <div role="separator" className="my-2 h-px bg-[rgba(0,0,0,0.08)]" />}
        </React.Fragment>
      ))}
    </div>
  );
};

export default ScanNav;
