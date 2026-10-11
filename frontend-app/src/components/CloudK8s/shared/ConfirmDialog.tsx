/**
 * CloudK8s confirmation dialog (plan P8; docs/cloudk8s/ui-revamp-design.md §14 "Dialogs").
 *
 * The shared `components/ui/AlertDialog.tsx` autofocuses the confirm button and is chromatic, which
 * breaks "the destructive or restarting choice is never the default button" and the design-rule
 * guard, so CloudK8s uses this one: the same react-aria primitives, greyscale, focus starting on
 * Cancel, Escape cancels, and focus returns to the trigger on close.
 */
import type { ReactNode } from 'react';
import { Button, Dialog, Heading, Modal, ModalOverlay } from 'react-aria-components';

export interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  children: ReactNode;
  /** Short verb, e.g. "Turn on". */
  confirmLabel: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** While the confirmed action runs: the confirm button reads "Working…" and does nothing. */
  pending?: boolean;
}

const buttonBase =
  'inline-flex items-center justify-center rounded-lg px-4 py-2 text-[13px] font-semibold outline-none '
  + 'focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

export function ConfirmDialog({
  isOpen, title, children, confirmLabel, cancelLabel = 'Cancel', onConfirm, onCancel, pending = false,
}: ConfirmDialogProps) {
  return (
    <ModalOverlay
      isOpen={isOpen}
      isDismissable
      onOpenChange={(open) => { if (!open) onCancel(); }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.32)] p-4"
    >
      <Modal className="w-full max-w-[440px] rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-[0_12px_40px_rgba(0,0,0,0.18)]">
        <Dialog role="alertdialog" className="outline-none p-5">
          <Heading slot="title" className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</Heading>
          <div className="mt-2 text-[13px] leading-[1.5] text-[rgba(0,0,0,0.6)]">{children}</div>
          <div className="mt-5 flex justify-end gap-2">
            <Button
              autoFocus
              onPress={onCancel}
              className={`${buttonBase} border border-[rgba(0,0,0,0.2)] bg-white text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)]`}
            >
              {cancelLabel}
            </Button>
            <Button
              onPress={() => { if (!pending) onConfirm(); }}
              aria-disabled={pending ? 'true' : undefined}
              className={`${buttonBase} bg-[rgba(0,0,0,0.88)] text-white hover:bg-black ${pending ? 'cursor-wait' : ''}`}
            >
              {pending ? 'Working…' : confirmLabel}
            </Button>
          </div>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}

export default ConfirmDialog;
