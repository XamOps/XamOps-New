import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { XpLogRow } from '../../../types/xampulse';
import { formatClock } from '../../../utils/xampulse/format';
import { SeverityBadge } from '../ui/ui';

/** Which rows to render for a scroll position. Pure so it can be tested with 100k rows. */
export function computeWindow(scrollTop: number, viewport: number, rowH: number, total: number, overscan = 8): { start: number; end: number } {
  const start = Math.max(0, Math.floor(scrollTop / rowH) - overscan);
  const end = Math.min(total, Math.ceil((scrollTop + viewport) / rowH) + overscan);
  return { start, end };
}

interface Props {
  rows: XpLogRow[];
  selectedId?: string | null;
  onSelect: (row: XpLogRow) => void;
  wrap?: boolean;
  height?: number;
  /** When true the newest row is at the top and a "Jump to latest" button appears after scrolling away. */
  followTop?: boolean;
  onNearEnd?: () => void;
  emptyText?: string;
  label: string;
}

/** Virtualised log list with a roving selection (j/k, arrows, Enter) and grid semantics. */
export const VirtualLogList: React.FC<Props> = ({ rows, selectedId, onSelect, wrap = false, height = 560, followTop = false, onNearEnd, emptyText = 'No log lines match.', label }) => {
  const rowH = wrap ? 56 : 30;
  const boxRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [active, setActive] = useState(0);
  const { start, end } = computeWindow(scrollTop, height, rowH, rows.length);

  useEffect(() => { if (active >= rows.length) setActive(Math.max(0, rows.length - 1)); }, [rows.length, active]);

  const scrollToIndex = useCallback((i: number) => {
    const box = boxRef.current;
    if (!box) return;
    const top = i * rowH;
    if (top < box.scrollTop) box.scrollTop = top;
    else if (top + rowH > box.scrollTop + height) box.scrollTop = top + rowH - height;
  }, [rowH, height]);

  const move = (delta: number) => {
    const next = Math.min(rows.length - 1, Math.max(0, active + delta));
    setActive(next);
    scrollToIndex(next);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'j' || e.key === 'ArrowDown') { e.preventDefault(); move(1); }
    else if (e.key === 'k' || e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    else if (e.key === 'PageDown') { e.preventDefault(); move(Math.floor(height / rowH)); }
    else if (e.key === 'PageUp') { e.preventDefault(); move(-Math.floor(height / rowH)); }
    else if (e.key === 'Home') { e.preventDefault(); move(-rows.length); }
    else if (e.key === 'End') { e.preventDefault(); move(rows.length); }
    else if ((e.key === 'Enter' || e.key === ' ') && rows[active]) { e.preventDefault(); onSelect(rows[active]); }
  };

  if (rows.length === 0) {
    return <div className="xp-logbox" style={{ height: 'auto', minHeight: 120 }}><p className="xp-muted" style={{ padding: 16, margin: 0 }}>{emptyText}</p></div>;
  }

  const visible = rows.slice(start, end);
  return (
    <div style={{ position: 'relative' }}>
      <div ref={boxRef} className="xp-logbox" style={{ height }} role="grid" aria-label={label} aria-rowcount={rows.length} tabIndex={0}
        aria-activedescendant={rows[active] ? `xp-lr-${rows[active].id}` : undefined}
        onKeyDown={onKeyDown}
        onScroll={(e) => {
          const el = e.currentTarget;
          setScrollTop(el.scrollTop);
          if (onNearEnd && el.scrollTop + height > el.scrollHeight - rowH * 10) onNearEnd();
        }}>
        <div style={{ height: rows.length * rowH, position: 'relative' }} role="rowgroup">
          {visible.map((r, k) => {
            const i = start + k;
            return (
              <div key={r.id} id={`xp-lr-${r.id}`} role="row" aria-rowindex={i + 1} aria-selected={selectedId === r.id || (i === active && selectedId == null)}
                className="xp-logrow" data-sev={r.severity} data-wrap={wrap} style={{ top: i * rowH, height: rowH, outline: i === active ? '1px solid var(--xp-accent)' : undefined, outlineOffset: -1 }}
                onClick={() => { setActive(i); onSelect(r); }}>
                <span role="gridcell" className="ts">{formatClock(r.ts, true)}</span>
                <span role="gridcell"><SeverityBadge severity={r.severity} /></span>
                <span role="gridcell" className="svc" title={r.instanceId}>{r.service}</span>
                <span role="gridcell" className="body">{wrap ? r.body : r.body.split('\n')[0]}</span>
              </div>
            );
          })}
        </div>
      </div>
      {followTop && scrollTop > rowH * 3 && (
        <button type="button" className="xp-btn xp-btn--primary" style={{ position: 'absolute', right: 16, top: 12 }} onClick={() => { if (boxRef.current) boxRef.current.scrollTop = 0; setActive(0); }}>Jump to latest</button>
      )}
    </div>
  );
};
