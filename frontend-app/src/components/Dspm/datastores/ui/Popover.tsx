import React, { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { usePopoverEscape } from './usePopoverEscape';

/** `w-72`. */
const POPOVER_WIDTH = 288;

interface PopoverProps {
  open: boolean;
  /** Must be stable (`useCallback`), as `usePopoverEscape` requires. */
  onClose: () => void;
  /** The element the popover opens under; focused again after Escape. */
  anchor: HTMLElement | null;
  /** The unscaled `relative` element, without `overflow` clipping, that the caller renders this inside. */
  container: RefObject<HTMLElement | null>;
  /** The dialog's accessible name. */
  label: string;
  children: React.ReactNode;
}

/**
 * A small dialog under an anchor (design 6.2), rendered by the caller inside `container`, not a portal.
 * Its one user is the lineage event popover, drawn outside the zoomed lanes so it is never scaled.
 *
 * The position comes from one read of both rects on open: `top = anchor.bottom − container.top + 4`,
 * `left = min(max(anchor.left − container.left, 0), container.clientWidth − 288)`, so it sits under its
 * anchor and never runs off the container's right side.
 *
 * Closes on a mousedown outside both the popover and its anchor (the anchor's own click decides), on
 * Escape in its own `onKeyDown` (stopped there, so an enclosing drawer stays open) and, inside a drawer,
 * through `usePopoverEscape`, which also covers Escape pressed after focus left it.
 */
export const Popover: React.FC<PopoverProps> = ({ open, onClose, anchor, container, label, children }) => {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);
  const trigger = useMemo(() => ({ current: anchor }), [anchor]);
  usePopoverEscape(open, onClose, trigger);

  useLayoutEffect(() => {
    const box = container.current;
    if (!open || !anchor || !box) {
      setPosition(null);
      return;
    }
    const a = anchor.getBoundingClientRect();
    const c = box.getBoundingClientRect();
    setPosition({
      top: a.bottom - c.top + 4,
      left: Math.min(Math.max(a.left - c.left, 0), box.clientWidth - POPOVER_WIDTH),
    });
  }, [open, anchor, container]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (panel.current?.contains(target) || anchor?.contains(target)) return;
      onClose();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open, anchor, onClose]);

  if (!open || !position) return null;

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    onClose();
    anchor?.focus();
  };

  return (
    <div
      ref={panel}
      role="dialog"
      aria-label={label}
      onKeyDown={onKeyDown}
      style={{ top: position.top, left: position.left }}
      className="absolute z-30 w-72 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white p-3 text-xs shadow-lg"
    >
      {children}
    </div>
  );
};
