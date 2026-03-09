/**
 * Accessible AlertDialog primitive — replaces window.confirm() / window.alert().
 * Provides: proper alertdialog role, focus trap on destructive button,
 * Escape key cancel, ARIA-described content.
 *
 * Usage:
 *   const [open, setOpen] = useState(false);
 *   <AlertDialog
 *     isOpen={open}
 *     title="Delete user?"
 *     description="This action cannot be undone."
 *     confirmLabel="Delete"
 *     variant="danger"
 *     onConfirm={() => { doDelete(); setOpen(false); }}
 *     onCancel={() => setOpen(false)}
 *   />
 */

import React from 'react';
import {
  Dialog,
  Modal,
  ModalOverlay,
  Heading,
  Button,
} from 'react-aria-components';

type Variant = 'danger' | 'warning' | 'info';

interface AlertDialogProps {
  isOpen: boolean;
  title: string;
  description?: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: Variant;
  onConfirm: () => void;
  onCancel: () => void;
}

const variantStyles: Record<Variant, { icon: string; iconClass: string; confirmBtn: string }> = {
  danger: {
    icon: 'fas fa-exclamation-triangle',
    iconClass: 'text-rose-500',
    confirmBtn:
      'bg-rose-600 hover:bg-rose-700 text-white focus-visible:ring-rose-500',
  },
  warning: {
    icon: 'fas fa-exclamation-circle',
    iconClass: 'text-amber-500',
    confirmBtn:
      'bg-amber-500 hover:bg-amber-600 text-white focus-visible:ring-amber-400',
  },
  info: {
    icon: 'fas fa-info-circle',
    iconClass: 'text-indigo-500',
    confirmBtn:
      'bg-indigo-600 hover:bg-indigo-700 text-white focus-visible:ring-indigo-500',
  },
};

export function AlertDialog({
  isOpen,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
  onConfirm,
  onCancel,
}: AlertDialogProps) {
  const styles = variantStyles[variant];

  return (
    <ModalOverlay
      isOpen={isOpen}
      onOpenChange={(open) => { if (!open) onCancel(); }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    >
      <Modal className="w-full max-w-md mx-4 outline-none">
        <Dialog
          role="alertdialog"
          className="bg-white rounded-2xl shadow-2xl overflow-hidden outline-none"
        >
          {() => (
            <div className="p-6">
              <div className="flex items-start gap-4">
                <div className={`text-2xl mt-0.5 ${styles.iconClass}`}>
                  <i className={styles.icon} />
                </div>
                <div className="flex-1">
                  <Heading
                    slot="title"
                    className="text-base font-bold text-slate-900 mb-2"
                  >
                    {title}
                  </Heading>
                  {description && (
                    <p className="text-sm text-slate-500">{description}</p>
                  )}
                </div>
              </div>
              <div className="flex justify-end gap-3 mt-6">
                <Button
                  onPress={onCancel}
                  className="px-4 py-2 rounded-xl text-xs font-bold text-slate-600 bg-white border border-slate-200 hover:bg-slate-50 transition-all outline-none focus-visible:ring-2 focus-visible:ring-slate-400"
                >
                  {cancelLabel}
                </Button>
                <Button
                  onPress={onConfirm}
                  autoFocus
                  className={`px-4 py-2 rounded-xl text-xs font-bold transition-all outline-none focus-visible:ring-2 shadow-sm ${styles.confirmBtn}`}
                >
                  {confirmLabel}
                </Button>
              </div>
            </div>
          )}
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}
