import { Icon } from '@iconify/react';
import { motion } from 'framer-motion';
import type { MediaOpsNotification as NotificationItem } from '../../hooks/useMediaOpsNotifications';

// ─── Types ────────────────────────────────────────────────────────────────────

interface NotificationToastProps {
  notification: NotificationItem;
  onDismiss: () => void;
  onNavigateToJob: (jobId: number) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function NotificationToast({ notification, onDismiss, onNavigateToJob }: NotificationToastProps) {
  const isSuccess = notification.type === 'JOB_COMPLETED';

  const handleClick = () => {
    if (notification.jobId) {
      onNavigateToJob(notification.jobId);
    }
    onDismiss();
  };

  return (
    <motion.div
      initial={{ opacity: 0, x: 80, scale: 0.9 }}
      animate={{ opacity: 1, x: 0, scale: 1 }}
      exit={{ opacity: 0, x: 80, scale: 0.9 }}
      transition={{ type: 'spring', stiffness: 400, damping: 30 }}
      className="fixed top-4 right-4 z-[9999] w-80"
    >
      <div
        className={`flex items-start gap-3 p-4 rounded-2xl shadow-lg border backdrop-blur-sm cursor-pointer transition-transform hover:scale-[1.01] ${
          isSuccess
            ? 'bg-white border-emerald-200'
            : 'bg-white border-red-200'
        }`}
        onClick={handleClick}
        role="alert"
        aria-live="polite"
      >
        {/* Icon */}
        <div className={`w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 ${
          isSuccess ? 'bg-emerald-50' : 'bg-red-50'
        }`}>
          <Icon
            icon={isSuccess ? 'mdi:check-circle' : 'mdi:alert-circle'}
            className={`text-lg ${isSuccess ? 'text-emerald-500' : 'text-red-500'}`}
          />
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0">
          <p className="text-[11px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-0.5">
            {isSuccess ? 'Job Completed' : 'Job Failed'}
          </p>
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.8)] leading-snug line-clamp-2">
            {notification.message}
          </p>
        </div>

        {/* Dismiss */}
        <button
          onClick={e => { e.stopPropagation(); onDismiss(); }}
          className="p-1 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors flex-shrink-0"
          aria-label="Dismiss notification"
        >
          <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.3)]" />
        </button>
      </div>
    </motion.div>
  );
}

export default NotificationToast;
