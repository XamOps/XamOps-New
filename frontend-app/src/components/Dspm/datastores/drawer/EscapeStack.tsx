import React, { useCallback, useContext, useEffect, useMemo, useRef, type RefObject } from 'react';
import { EscapeStackContext, type EscapeEntry, type EscapeStackApi } from '../ui/escapeStackContext';

/** A drawer's Escape stack: the API its layers register with, and the drawer's own close-topmost. */
export interface EscapeStackController {
  api: EscapeStackApi;
  /**
   * Closes the topmost layer and returns true, or returns false when nothing is open (the drawer then
   * closes itself). Focus goes back to the element that opened the layer while it is still in the
   * document, else to `fallbackFocus` (the drawer's `aside`).
   */
  closeTopmost: () => boolean;
}

/**
 * The stack behind a datastore drawer's Escape key (design 6.7). Side panels and open popovers register
 * `{ close, returnFocus }` while they are open; the drawer's `window` keydown listener calls
 * `closeTopmost` on Escape, whatever has focus, and closes the drawer only when the stack is empty.
 *
 * The order Escape works through is: an open popover, a visible console-only tooltip (both stop the key
 * themselves before it reaches `window`), then the newest registered layer, then the drawer.
 */
export function useEscapeStack(fallbackFocus: RefObject<HTMLElement | null>): EscapeStackController {
  const entries = useRef<EscapeEntry[]>([]);

  const api = useMemo<EscapeStackApi>(() => ({
    register(entry) {
      entries.current = [...entries.current, entry];
      // Also called after `closeTopmost` already removed the entry, when the layer's effect cleans up.
      return () => { entries.current = entries.current.filter(e => e !== entry); };
    },
  }), []);

  const closeTopmost = useCallback(() => {
    const top = entries.current[entries.current.length - 1];
    if (!top) return false;
    entries.current = entries.current.slice(0, -1);
    top.close();
    const target = top.returnFocus?.isConnected ? top.returnFocus : fallbackFocus.current;
    target?.focus();
    return true;
  }, [fallbackFocus]);

  return useMemo(() => ({ api, closeTopmost }), [api, closeTopmost]);
}

/** Provides a drawer's Escape stack to the popovers and panels inside it (`usePopoverEscape`). */
export const EscapeStack: React.FC<{ stack: EscapeStackController; children: React.ReactNode }> = ({ stack, children }) => (
  <EscapeStackContext.Provider value={stack.api}>{children}</EscapeStackContext.Provider>
);

/**
 * A side panel's part in the drawer's Escape stack (design 6.7), for the Risk detail panel and the
 * Permissions panel. While the panel is mounted it is registered with `returnFocus`, the row or box that
 * opened it, and on open its close X takes focus.
 *
 * Escape closes the panel through the stack. The X uses the returned `close`, which returns focus the
 * same way, because unregistering never moves focus: to the opener while it is still in the document,
 * else to the drawer. `onClose` should be stable, or the entry is re-registered on every render.
 */
export function useSidePanel(returnFocus: HTMLElement | null, onClose: () => void) {
  const stack = useContext(EscapeStackContext);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => stack?.register({ close: onClose, returnFocus }), [stack, onClose, returnFocus]);

  const close = useCallback(() => {
    const target = returnFocus?.isConnected
      ? returnFocus
      : closeButton.current?.closest<HTMLElement>('[role="dialog"]') ?? null;
    onClose();
    target?.focus();
  }, [returnFocus, onClose]);

  return { closeButton, close };
}
