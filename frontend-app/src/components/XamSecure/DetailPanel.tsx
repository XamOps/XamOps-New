import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';

/**
 * Shared shell for every XamSecure detail panel.
 *
 * Matches how Upwind's side panels are actually built: a wide overlay with a VERTICAL
 * left-hand nav, optionally grouped by entity ("Finding" / "Image" / "Resource"), rather
 * than a narrow drawer with horizontal tabs. Extracted so all panels share one affordance
 * and one a11y implementation — eight bespoke tab bars would have drifted apart immediately.
 *
 * Sections declare their own availability. A section we have no data source for is rendered
 * disabled with a reason on hover rather than omitted, so the panel doesn't silently imply
 * Upwind offers less than it does.
 */

export interface PanelSection {
  id: string;
  label: string;
  icon?: string;
  /** Optional group heading; consecutive sections sharing one are rendered under it. */
  group?: string;
  /** Small trailing count, e.g. number of rows behind the section. */
  count?: number;
  /** When false the item is shown disabled; `unavailableReason` explains why. */
  available?: boolean;
  unavailableReason?: string;
  render: () => React.ReactNode;
}

interface Props {
  title: React.ReactNode;
  /** Chips/labels under the title — severity, status, timestamps. */
  subtitle?: React.ReactNode;
  /** Right-aligned header actions. */
  actions?: React.ReactNode;
  sections: PanelSection[];
  onClose: () => void;
  isLoading?: boolean;
  /** Shown instead of the nav + body, e.g. when the entity could not be resolved. */
  emptyState?: React.ReactNode;
  /** `narrow` suits single-section panels, which don't need room for a nav plus tables. */
  size?: 'wide' | 'narrow';
}

const DetailPanel: React.FC<Props> = ({
  title, subtitle, actions, sections, onClose, isLoading, emptyState, size = 'wide',
}) => {
  const usable = useMemo(() => sections.filter((s) => s.available !== false), [sections]);
  const [activeId, setActiveId] = useState<string>(usable[0]?.id ?? sections[0]?.id ?? '');
  const panelRef = useRef<HTMLDivElement>(null);

  // If the section set changes (data arrived), keep the selection valid.
  useEffect(() => {
    if (!sections.some((s) => s.id === activeId && s.available !== false)) {
      setActiveId(usable[0]?.id ?? '');
    }
  }, [sections, usable, activeId]);

  // Escape closes, and focus moves into the panel so keyboard users aren't stranded behind
  // the overlay.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    panelRef.current?.focus();
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose]);

  /*
   * Which section the body shows, derived rather than read straight off state.
   *
   * These panels mount while their payload is still in flight, so the first render has no
   * sections and `activeId` settles on ''. The effect above repairs it once sections arrive,
   * but that is a render later — and in between, the body fell through to "Nothing to show."
   * over data it already had. Deriving the selection means the first render with sections
   * already shows one, and the effect only keeps the stored id in step.
   */
  const active = sections.find((s) => s.id === activeId && s.available !== false) ?? usable[0];
  const activeKey = active?.id ?? '';

  // A nav listing a single destination is noise. Panels for small, cohesive entities
  // (a framework, a workflow) declare one section and get the shell's chrome, keyboard
  // handling and scroll lock without paying a click to see their own content.
  const singlePane = sections.length === 1;

  // Group consecutive sections under their heading, preserving declared order.
  const groups = useMemo(() => {
    const out: { group?: string; items: PanelSection[] }[] = [];
    for (const s of sections) {
      const last = out[out.length - 1];
      if (last && last.group === s.group) last.items.push(s);
      else out.push({ group: s.group, items: [s] });
    }
    return out;
  }, [sections]);

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/40" />
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        className={`relative h-full w-full bg-white shadow-2xl flex flex-col outline-none ${
          size === 'narrow' ? 'max-w-2xl' : 'max-w-5xl'}`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between gap-4 px-6 py-4 border-b border-gray-200">
          <div className="min-w-0">
            <h2 className="text-sm font-bold text-gray-900 leading-snug break-words">{title}</h2>
            {subtitle && <div className="mt-2 flex flex-wrap items-center gap-2">{subtitle}</div>}
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {actions}
            <button type="button" onClick={onClose} aria-label="Close"
                    className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-700
                               focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
              <Icon icon="mdi:close" width={18} />
            </button>
          </div>
        </div>

        {emptyState ? (
          <div className="flex-1 flex items-center justify-center p-6">{emptyState}</div>
        ) : (
          <div className="flex-1 flex min-h-0">
            {/* Vertical nav, as Upwind does it */}
            {!singlePane && (
            <nav aria-label="Panel sections"
                 className="w-56 flex-shrink-0 border-r border-gray-200 overflow-y-auto py-3">
              {groups.map((g, gi) => (
                <div key={g.group ?? `g${gi}`} className={gi > 0 ? 'mt-3' : ''}>
                  {g.group && (
                    <p className="px-4 pb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
                      {g.group}
                    </p>
                  )}
                  {g.items.map((s) => {
                    const disabled = s.available === false;
                    const isActive = s.id === activeKey;
                    return (
                      <button
                        key={s.id}
                        type="button"
                        disabled={disabled}
                        title={disabled ? s.unavailableReason : undefined}
                        aria-current={isActive ? 'true' : undefined}
                        onClick={() => setActiveId(s.id)}
                        className={`w-full flex items-center gap-2 px-4 py-2 text-left text-xs transition-colors
                                    focus:outline-none focus-visible:ring-2 focus-visible:ring-inset
                                    focus-visible:ring-gray-900 ${
                          disabled
                            ? 'text-gray-300 cursor-not-allowed'
                            : isActive
                              ? 'bg-gray-100 font-semibold text-gray-900'
                              : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`}
                      >
                        {s.icon && <Icon icon={s.icon} width={13} className="flex-shrink-0" />}
                        <span className="truncate">{s.label}</span>
                        {s.count != null && (
                          <span className="ml-auto text-[10px] font-normal text-gray-400">{s.count}</span>
                        )}
                        {disabled && (
                          <Icon icon="mdi:information-outline" width={11}
                                className="ml-auto flex-shrink-0 text-gray-300" />
                        )}
                      </button>
                    );
                  })}
                </div>
              ))}
            </nav>
            )}

            {/* Body */}
            <div className="flex-1 min-w-0 overflow-y-auto px-6 py-5">
              {isLoading ? (
                <p className="text-xs text-gray-400">Loading...</p>
              ) : active ? (
                <>
                  {!singlePane && (
                    <h3 className="text-base font-bold text-gray-900 mb-4">{active.label}</h3>
                  )}
                  {active.render()}
                </>
              ) : (
                <p className="text-xs text-gray-400">Nothing to show.</p>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  ), document.body);
};

/** Label/value row, the workhorse of these panels. */
export const PanelRow: React.FC<{
  label: string; value?: React.ReactNode; mono?: boolean;
}> = ({ label, value, mono }) => (
  <div className="flex items-start gap-3 py-2 border-b border-gray-50 last:border-0">
    <span className="w-40 flex-shrink-0 text-[11px] text-gray-500 pt-0.5">{label}</span>
    <span className={`min-w-0 flex-1 text-xs text-gray-800 break-words ${mono ? 'font-mono text-[11px]' : ''}`}>
      {value == null || value === '' ? <span className="text-gray-300">—</span> : value}
    </span>
  </div>
);

/** Titled block within a section body. */
export const PanelBlock: React.FC<{
  title?: string; children: React.ReactNode; action?: React.ReactNode;
}> = ({ title, children, action }) => (
  <div className="mb-5 last:mb-0">
    {(title || action) && (
      <div className="flex items-center justify-between gap-3 mb-2">
        {title && (
          <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400">{title}</h4>
        )}
        {action}
      </div>
    )}
    {children}
  </div>
);

export default DetailPanel;
