import { Icon } from '@iconify/react';
import { motion } from 'framer-motion';
import type { MediaOpsNotification as NotificationItem } from '../../hooks/useMediaOpsNotifications';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface NotificationListProps {
  notifications: NotificationItem[];
  onMarkAsRead: (id: number) => void;
  onNavigateToJob: (jobId: number) => void;
  onClose: () => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  if (!iso) return '';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function getNotificationIcon(type: NotificationItem['type']): { icon: string; color: string; bg: string } {
  switch (type) {
    case 'JOB_COMPLETED':
      return { icon: 'mdi:check-circle', color: 'text-emerald-500', bg: 'bg-emerald-50' };
    case 'JOB_FAILED':
      return { icon: 'mdi:alert-circle', color: 'text-red-500', bg: 'bg-red-50' };
    default:
      return { icon: 'mdi:information', color: 'text-blue-500', bg: 'bg-blue-50' };
  }
}

// ─── Component ────────────────────────────────────────────────────────────────

export function NotificationList({ notifications, onMarkAsRead, onNavigateToJob, onClose }: NotificationListProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const handleClick = (notification: NotificationItem) => {
    if (!notification.read) onMarkAsRead(notification.id);
    if (notification.jobId) onNavigateToJob(notification.jobId);
    onClose();
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: -8, scale: 0.95 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: -8, scale: 0.95 }}
      transition={{ duration: 0.15 }}
      className={`absolute right-0 top-full mt-2 w-80 border rounded-2xl shadow-xl z-50 overflow-hidden ${
        dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.10)]' : 'bg-white border-[rgba(0,0,0,0.10)]'
      }`}
    >
      {/* Header */}
      <div className={`flex items-center justify-between px-4 py-3 border-b ${dark ? 'border-[rgba(255,255,255,0.06)]' : 'border-[rgba(0,0,0,0.06)]'}`}>
        <div className="flex items-center gap-2">
          <Icon icon="mdi:bell" className="text-pink-500 text-sm" />
          <span className={`text-[13px] font-bold ${dark ? 'text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.88)]'}`}>Notifications</span>
        </div>
        <button onClick={onClose}
          className={`p-1 rounded-lg transition-colors ${dark ? 'hover:bg-[rgba(255,255,255,0.04)]' : 'hover:bg-[rgba(0,0,0,0.04)]'}`}
          aria-label="Close notifications">
          <Icon icon="mdi:close" className={`text-sm ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`} />
        </button>
      </div>

      {/* Notification list */}
      <div className="max-h-80 overflow-y-auto">
        {notifications.length === 0 ? (
          <div className="py-10 text-center">
            <Icon icon="mdi:bell-off-outline" className={`text-3xl mx-auto mb-2 ${dark ? 'text-[rgba(255,255,255,0.12)]' : 'text-[rgba(0,0,0,0.12)]'}`} />
            <p className={`text-[12px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>No notifications yet</p>
          </div>
        ) : (
          <div className={`divide-y ${dark ? 'divide-[rgba(255,255,255,0.04)]' : 'divide-[rgba(0,0,0,0.04)]'}`}>
            {notifications.map(notification => {
              const { icon, color, bg } = getNotificationIcon(notification.type);
              return (
                <button key={notification.id} onClick={() => handleClick(notification)}
                  className={`w-full flex items-start gap-3 px-4 py-3 text-left transition-colors ${
                    dark ? 'hover:bg-[rgba(255,255,255,0.02)]' : 'hover:bg-[rgba(0,0,0,0.02)]'
                  } ${!notification.read ? (dark ? 'bg-pink-900/10' : 'bg-pink-50/30') : ''}`}>
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5 ${bg}`}>
                    <Icon icon={icon} className={`text-sm ${color}`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className={`text-[12px] leading-snug ${
                      !notification.read
                        ? (dark ? 'font-semibold text-[rgba(255,255,255,0.88)]' : 'font-semibold text-[rgba(0,0,0,0.88)]')
                        : (dark ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.65)]')
                    }`}>{notification.message}</p>
                    <p className={`text-[10px] mt-0.5 ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>
                      {timeAgo(notification.createdAt)}
                    </p>
                  </div>
                  {!notification.read && <span className="w-2 h-2 rounded-full bg-pink-500 flex-shrink-0 mt-2" />}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </motion.div>
  );
}

export default NotificationList;
