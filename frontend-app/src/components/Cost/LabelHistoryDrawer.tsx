import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { ResourceLabel } from '../../types/resourceLabels';
import { lockPageScroll } from '../../utils/scrollLock';
import LabelHistoryList from './LabelHistoryList';

/**
 * The account's label history, over the Charges by service card: every label and share change on
 * the account, and the client's label creates, edits and deletes, newest first.
 *
 * <p>The per-item view of the same trail lives in the label popover on each service and resource
 * row. This one answers the question that starts without an item — "why did prod's spend change?"
 * — which needs every change in one place.
 */
const LabelHistoryDrawer: React.FC<{
  open: boolean;
  accountId: string;
  allLabels: ResourceLabel[];
  onClose: () => void;
}> = ({ open, accountId, allLabels, onClose }) => {
  useEffect(() => (open ? lockPageScroll() : undefined), [open]);

  // Capture phase, so Escape closes this panel rather than anything beneath it.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopImmediatePropagation();
      onClose();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [open, onClose]);

  return createPortal(
    <AnimatePresence>
      {open && (
        <React.Fragment key="label-history-drawer">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            className="fixed inset-0 bg-black/30 z-[70]"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-label="Label history"
            className="fixed right-0 top-0 h-full w-full sm:w-[560px] bg-white shadow-2xl z-[70] flex flex-col"
          >
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-start justify-between gap-4">
              <div className="min-w-0 flex items-start gap-3">
                <span className="w-9 h-9 flex-shrink-0 flex items-center justify-center rounded-lg bg-[rgba(66,133,244,0.08)]">
                  <Icon icon="mdi:history" width={18} className="text-[#4285F4]" />
                </span>
                <div className="min-w-0">
                  <p className="text-[15px] font-bold text-black">Label history</p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    Who changed which labels and shares on account {accountId}, newest first
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                className="w-8 h-8 flex-shrink-0 inline-flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:text-black hover:border-[rgba(0,0,0,0.3)]"
              >
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-2">
              <LabelHistoryList accountId={accountId} target={null} allLabels={allLabels} />
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

export default LabelHistoryDrawer;
