import React, { useEffect, useRef, type RefObject } from 'react';
import ReactDOM from 'react-dom';
import { EscapeStack, useEscapeStack, useSidePanel } from './EscapeStack';

interface DrawerShellProps {
  /** The dialog's accessible name (the datastore's name). */
  label: string | undefined;
  onClose: () => void;
  /** Focused when the drawer opens: the header's close X. */
  initialFocus: RefObject<HTMLElement | null>;
  children: React.ReactNode;
}

/**
 * A root drawer (design 6.2): a dialog over the right 55% of the page, portalled into `document.body`
 * beside a dimming overlay whose click closes it.
 *
 * Escape is read on `window`, so it works wherever focus is (also after the focused control unmounted
 * or became disabled): it closes the topmost layer on the drawer's `EscapeStack`, else the drawer.
 * Controls that must win (an open popover, a visible console-only tooltip) stop the key before it gets
 * there. The stack is provided to everything inside, nested drawers included.
 */
export const DrawerShell: React.FC<DrawerShellProps> = ({ label, onClose, initialFocus, children }) => {
  const aside = useRef<HTMLElement>(null);
  const escapeStack = useEscapeStack(aside);

  useEffect(() => { initialFocus.current?.focus(); }, [initialFocus]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!escapeStack.closeTopmost()) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [escapeStack, onClose]);

  return ReactDOM.createPortal(
    <>
      <div className="fixed inset-0 z-40 bg-[rgba(0,0,0,0.25)]" onClick={onClose} aria-hidden="true" />
      <aside
        ref={aside}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        tabIndex={-1}
        className="fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-white shadow-2xl focus:outline-none lg:w-[55vw]"
      >
        <EscapeStack stack={escapeStack}>{children}</EscapeStack>
      </aside>
    </>,
    document.body,
  );
};

/**
 * Stacking classes of a nested drawer, as full literals so Tailwind generates them. The default layer
 * covers the root drawer (`z-50`); the elevated one also covers a full-screen view (`z-[70]`), for the
 * table panel opened from the full-screen Graph View (review fix R1).
 */
const LAYERS = {
  default: { overlay: 'z-[55]', aside: 'z-[60]' },
  elevated: { overlay: 'z-[75]', aside: 'z-[80]' },
} as const;

interface NestedDrawerShellProps {
  label: string | undefined;
  /** The element that opened this layer; focused again when it closes. */
  returnFocus: HTMLElement | null;
  /** Must be stable (`useCallback`), as `useSidePanel` requires. */
  onClose: () => void;
  /** The aside's width from `lg` up; full width below. */
  widthClass?: string;
  /** Above a full-screen view (`FullscreenFrame`), not only above the root drawer. */
  elevated?: boolean;
  children: (controls: { closeButton: RefObject<HTMLButtonElement | null>; close: () => void }) => React.ReactNode;
}

/**
 * A layer opened from inside a drawer (the SaaS file drawer, the Graph View's table panel): its own
 * dimming overlay over the parent drawer and its own aside, portalled into `document.body`.
 *
 * It adds no `window` listener and no provider. `useSidePanel` registers it in the parent drawer's
 * Escape stack, focuses its close X (`closeButton`, which the children attach) on open, and `close`
 * returns focus to `returnFocus`. The overlay's click calls that `close` as well (review fix R11), so it
 * closes this layer only and focus lands on the opener. Popovers inside register in the same parent
 * stack, so one Escape always closes the newest layer.
 */
export const NestedDrawerShell: React.FC<NestedDrawerShellProps> = ({
  label, returnFocus, onClose, widthClass = 'lg:w-[55vw]', elevated = false, children,
}) => {
  const { closeButton, close } = useSidePanel(returnFocus, onClose);
  const layer = elevated ? LAYERS.elevated : LAYERS.default;

  return ReactDOM.createPortal(
    <>
      <div className={`fixed inset-0 ${layer.overlay} bg-[rgba(0,0,0,0.25)]`} onClick={close} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={label}
        tabIndex={-1}
        className={`fixed inset-y-0 right-0 ${layer.aside} flex w-full flex-col bg-white shadow-2xl focus:outline-none ${widthClass}`}
      >
        {children({ closeButton, close })}
      </aside>
    </>,
    document.body,
  );
};
