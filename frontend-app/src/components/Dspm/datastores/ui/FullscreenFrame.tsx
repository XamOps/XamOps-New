import React, { useContext, useEffect, useRef, type RefObject } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import { EscapeStackContext } from './escapeStackContext';

interface FullscreenFrameProps {
  open: boolean;
  /** The full-screen dialog's accessible name. */
  title: string;
  /** Must be stable (`useCallback`): it is the Escape stack entry while open. */
  onExit: () => void;
  /** The toggle that opened full screen; focused again after the exit. */
  toggleRef: RefObject<HTMLElement | null>;
  /**
   * `page` (the default) fills the screen; `modal` centres a dialog up to 1280 px wide on a dimmed
   * backdrop (the Data Lineage page). Focus, the Escape-stack entry and `onExit` are the same; a backdrop
   * click does nothing.
   */
  variant?: 'page' | 'modal';
  children: React.ReactNode;
}

/**
 * Shows its children in place, or, while `open`, in a full-screen dialog portalled into `document.body`
 * above the drawer (design 6.2), with an "Exit full screen" button that takes focus.
 *
 * While open it registers `{ close: onExit, returnFocus: null }` in the enclosing drawer's Escape stack,
 * so Escape leaves full screen before anything older closes. Moving the subtree remounts it, so a toggle
 * inside the content is a new element after the exit: focus therefore returns in an effect that runs
 * when `open` turns false, to `toggleRef.current` as it is then. Callers keep the view state that must
 * survive (search, expanded cards) above the frame.
 */
export const FullscreenFrame: React.FC<FullscreenFrameProps> = ({
  open, title, onExit, toggleRef, variant = 'page', children,
}) => {
  const stack = useContext(EscapeStackContext);
  const exitButton = useRef<HTMLButtonElement>(null);
  const wasOpen = useRef(open);

  useEffect(() => {
    if (!open || !stack) return;
    return stack.register({ close: onExit, returnFocus: null });   // returns the unregister function
  }, [open, stack, onExit]);

  useEffect(() => {
    if (open) exitButton.current?.focus();
    else if (wasOpen.current) toggleRef.current?.focus();
    wasOpen.current = open;
  }, [open, toggleRef]);

  if (!open) return <>{children}</>;

  const content = (
    <>
      <div className="flex justify-end px-5 pt-4">
        <button
          ref={exitButton}
          type="button"
          onClick={onExit}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
        >
          <Icon icon="mdi:fullscreen-exit" width={14} />
          Exit full screen
        </button>
      </div>
      <div className="px-5 pb-5">{children}</div>
    </>
  );

  if (variant === 'modal') {
    return ReactDOM.createPortal(
      <div className="fixed inset-0 z-[70] flex items-start justify-center overflow-auto bg-black/40 p-6">
        <div role="dialog" aria-modal="true" aria-label={title} className="w-full max-w-[1280px] rounded-xl bg-white shadow-xl">
          {content}
        </div>
      </div>,
      document.body,
    );
  }

  return ReactDOM.createPortal(
    <div role="dialog" aria-modal="true" aria-label={title} className="fixed inset-0 z-[70] overflow-auto bg-white">
      {content}
    </div>,
    document.body,
  );
};
