import React, { useEffect, useId, useRef } from 'react';
import { DialogRoot } from '../../../components/ui';
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
interface AsDialogProps {
  title: string;
  /** Short summary, wired to aria-describedby. */
  summary?: string;
  onClose: () => void;
  /** While a confirm request is in flight Esc does not close the dialog. */
  busy?: boolean;
  children: React.ReactNode;
  footer?: React.ReactNode;
}
/**
 * The one dialog for the shell (design-ux-ops.md §2.7), on the app's `DialogRoot` (react-aria modal:
 * scrim offset past the sidebar, focus contained, focus returned to the trigger on close). role=dialog,
 * aria-modal, labelled and described, initial focus on the first control, and Esc closes unless a
 * request is in flight.
 */
export const AsDialog: React.FC<AsDialogProps> = ({ title, summary, onClose, busy, children, footer }) => {
  const titleId = useId();
  const descId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const trigger = useRef<Element | null>(null);
  useEffect(() => {
    trigger.current = document.activeElement;
    const first = ref.current?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? ref.current)?.focus();
    return () => {
      if (trigger.current instanceof HTMLElement && trigger.current.isConnected) trigger.current.focus();
    };
  }, []);
  // react-aria contains focus in the modal layer; this keeps Tab cycling inside the dialog box itself.
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Tab' || !ref.current) return;
    const items = Array.from(ref.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) {
      e.preventDefault();
      return;
    }
    const firstEl = items[0];
    const lastEl = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === firstEl || !ref.current.contains(active))) {
      e.preventDefault();
      lastEl.focus();
    } else if (!e.shiftKey && (active === lastEl || !ref.current.contains(active))) {
      e.preventDefault();
      firstEl.focus();
    }
  };
  return (
    <DialogRoot isOpen onClose={onClose} maxWidth="max-w-2xl" isKeyboardDismissDisabled={busy}>
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={summary ? descId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className="flex max-h-[90vh] flex-col rounded-2xl bg-white text-[rgba(0,0,0,0.88)] shadow-2xl outline-none"
      >
        <div className="flex-shrink-0 border-b border-[rgba(0,0,0,0.06)] px-6 py-5">
          <h2 id={titleId} className="text-[16px] font-bold">
            {title}
          </h2>
          {summary && (
            <p id={descId} className="mt-1 text-[13px] text-[rgba(0,0,0,0.55)]">
              {summary}
            </p>
          )}
        </div>
        <div className="flex-1 overflow-y-auto px-6 py-5 text-[13px]">{children}</div>
        {footer && (
          <div className="flex flex-shrink-0 items-center justify-end gap-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)] px-6 py-4">
            {footer}
          </div>
        )}
      </div>
    </DialogRoot>
  );
};
export default AsDialog;
