import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { lockPageScroll } from '../../utils/scrollLock';
import { DEFAULT_AWS_ICON, resolveAwsServiceIcon } from '../../utils/awsServiceIcons';

/**
 * Right-side panel listing one service's resources on the Cloudlist page.
 *
 * <p>The panel is only a frame: the resource table, row details, labels and connected resources
 * are passed in as children, so the page keeps one implementation of them.
 *
 * <p>Rendered through a portal because the service cards sit inside animated list items, and a
 * transformed ancestor would make a `fixed` panel position itself against the card instead of the
 * viewport.
 */
const CloudListServiceDrawer: React.FC<{
  open: boolean;
  serviceType: string;
  resourceCount: number;
  onClose: () => void;
  children: React.ReactNode;
}> = ({ open, serviceType, resourceCount, onClose, children }) => {
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
        <React.Fragment key="cloudlist-service-drawer">
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
            aria-modal="true"
            aria-label={`${serviceType} resources`}
            className="fixed right-0 top-0 h-full w-full sm:w-[820px] max-w-full bg-white shadow-2xl z-[70] flex flex-col"
          >
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-center justify-between gap-4">
              <div className="min-w-0 flex items-center gap-3">
                <span className="w-10 h-10 flex-shrink-0 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.03)]">
                  <img
                    src={resolveAwsServiceIcon(serviceType)}
                    alt=""
                    aria-hidden="true"
                    className="w-6 h-6 object-contain"
                    onError={e => {
                      const img = e.currentTarget;
                      if (img.src.endsWith(DEFAULT_AWS_ICON)) return;
                      img.src = DEFAULT_AWS_ICON;
                    }}
                  />
                </span>
                <div className="min-w-0">
                  <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] truncate">{serviceType}</p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    {resourceCount} resource{resourceCount !== 1 ? 's' : ''} · AWS Service
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
            <div className="flex-1 overflow-y-auto">
              {children}
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

export default CloudListServiceDrawer;
