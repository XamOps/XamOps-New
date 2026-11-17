import React, { useEffect, useId, useRef } from 'react';

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
 * The one dialog for the shell (design-ux-ops.md §2.7): role=dialog, aria-modal, labelled and
 * described, a focus trap with initial focus on the first control and focus returned to the trigger
 * on close, and Esc closes unless a request is in flight.
 */
export const AsDialog: React.FC<AsDialogProps> = ({ title, summary, onClose, busy, children, footer }) => {
  const titleId = useId();
  const descId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const trigger = useRef<Element | null>(null);
  const onCloseRef = useRef(onClose);
  const busyRef = useRef(busy);
  onCloseRef.current = onClose;
  busyRef.current = busy;

  useEffect(() => {
    trigger.current = document.activeElement;
    const first = ref.current?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? ref.current)?.focus();
    return () => {
      if (trigger.current instanceof HTMLElement) trigger.current.focus();
    };
  }, []);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      if (!busyRef.current) {
        e.stopPropagation();
        onCloseRef.current();
      }
      return;
    }
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={summary ? descId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className="flex max-h-[90vh] w-full max-w-2xl flex-col rounded border border-gray-300 bg-white text-black shadow-lg"
      >
        <div className="border-b border-gray-200 px-5 py-4">
          <h2 id={titleId} className="text-lg font-semibold">
            {title}
          </h2>
          {summary && (
            <p id={descId} className="mt-1 text-sm text-gray-600">
              {summary}
            </p>
          )}
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex items-center justify-end gap-2 border-t border-gray-200 px-5 py-3">{footer}</div>}
      </div>
    </div>
  );
};

export default AsDialog;
