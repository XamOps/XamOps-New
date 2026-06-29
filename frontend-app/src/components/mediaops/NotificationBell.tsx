import { Icon } from '@iconify/react';
import { motion, AnimatePresence } from 'framer-motion';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface NotificationBellProps {
  unreadCount: number;
  onClick: () => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function NotificationBell({ unreadCount, onClick }: NotificationBellProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  return (
    <button
      onClick={onClick}
      className={`relative p-2 rounded-xl transition-colors ${dark ? 'hover:bg-[rgba(255,255,255,0.04)]' : 'hover:bg-[rgba(0,0,0,0.04)]'}`}
      aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
    >
      <Icon icon="mdi:bell-outline" className={`text-xl ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`} />

      <AnimatePresence>
        {unreadCount > 0 && (
          <motion.span
            initial={{ scale: 0 }}
            animate={{ scale: 1 }}
            exit={{ scale: 0 }}
            transition={{ type: 'spring', stiffness: 500, damping: 25 }}
            className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] flex items-center justify-center rounded-full bg-gradient-to-r from-blue-500 to-blue-600 text-white text-[10px] font-bold px-1 shadow-sm"
          >
            {unreadCount > 99 ? '99+' : unreadCount}
          </motion.span>
        )}
      </AnimatePresence>
    </button>
  );
}

export default NotificationBell;
