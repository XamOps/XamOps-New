import React, { useCallback, useMemo, useRef, type RefObject } from 'react';
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
