/**
 * Accessible Dialog / Modal primitive built on react-aria-components.
 * Provides: focus trap, Escape to close, backdrop click to close,
 * focus restoration on close, proper ARIA roles.
 *
 * Usage:
 *   <DialogRoot isOpen={open} onClose={() => setOpen(false)}>
 *     <DialogPanel title="My Dialog">…content…</DialogPanel>
 *   </DialogRoot>
 */

import React from 'react';
import {
  Dialog,
  DialogTrigger,
  Modal,
  ModalOverlay,
  Heading,
  Button,
} from 'react-aria-components';

/* ── types ─────────────────────────────────────────────────── */

interface DialogRootProps {
  isOpen: boolean;
  onClose: () => void;
  children: React.ReactNode;
  /** Max width class e.g. "max-w-lg" (default) */
  maxWidth?: string;
}

interface DialogPanelProps {
  title?: React.ReactNode;
  children: React.ReactNode;
  /** Optional footer content (buttons, etc.) */
  footer?: React.ReactNode;
  /** Hide the default close × button */
  hideCloseButton?: boolean;
  onClose?: () => void;
}

/* ── DialogRoot ─────────────────────────────────────────────── */

export function DialogRoot({ isOpen, onClose, children, maxWidth = 'max-w-lg' }: DialogRootProps) {
  return (
    <ModalOverlay
      isOpen={isOpen}
      onOpenChange={(open) => { if (!open) onClose(); }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    >
      <Modal
        className={`w-full ${maxWidth} mx-4 outline-none`}
        onOpenChange={(open) => { if (!open) onClose(); }}
      >
        {children}
      </Modal>
    </ModalOverlay>
  );
}

/* ── DialogPanel ────────────────────────────────────────────── */

export function DialogPanel({ title, children, footer, hideCloseButton, onClose }: DialogPanelProps) {
  return (
    <Dialog className="bg-white rounded-2xl shadow-2xl overflow-hidden outline-none">
      {({ close }) => (
        <>
          {title && (
            <div className="px-6 py-5 border-b border-slate-100 bg-slate-50/50 flex items-center justify-between">
              <Heading slot="title" className="text-base font-bold text-slate-900">
                {title}
              </Heading>
              {!hideCloseButton && (
                <Button
                  onPress={onClose ?? close}
                  aria-label="Close dialog"
                  className="w-8 h-8 flex items-center justify-center rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                >
                  <i className="fas fa-times text-sm" />
                </Button>
              )}
            </div>
          )}
          <div className="px-6 py-5">{children}</div>
          {footer && (
            <div className="px-6 py-4 border-t border-slate-100 bg-slate-50/30 flex justify-end gap-3">
              {footer}
            </div>
          )}
        </>
      )}
    </Dialog>
  );
}

/* ── Re-export for convenience ──────────────────────────────── */
export { DialogTrigger, Button as DialogButton };
