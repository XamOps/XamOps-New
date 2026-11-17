import { useContext, useEffect, type RefObject } from 'react';
import { EscapeStackContext } from './escapeStackContext';

/**
 * Registers an open popover with the enclosing drawer's Escape stack, so Escape closes the popover first
 * even when focus has left it (a click on a non-focusable part of the popover moves focus to the drawer).
 *
 * Does nothing outside a drawer, where the context is null; there the popover's own `onKeyDown` handles
 * Escape. `close` should be stable, or the entry is re-registered on every render.
 */
export function usePopoverEscape(open: boolean, close: () => void, trigger: RefObject<HTMLElement | null>) {
  const stack = useContext(EscapeStackContext);   // null on the list page, outside any drawer
  useEffect(() => {
    if (!open || !stack) return;
    return stack.register({ close, returnFocus: trigger.current });   // returns the unregister function
  }, [open, stack, close, trigger]);
}
