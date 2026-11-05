import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { AnimatePresence, motion } from 'framer-motion';
import clsx from 'clsx';
import { useAuth } from '../../contexts/AuthContext';
import { useTheme } from '../../contexts/ThemeContext';
import { type ClientBanner, fetchMyBanners } from './bannerApi';

const dismissKey = (userKey: string) => `xamops_dismissed_banners_${userKey}`;

const readDismissed = (userKey: string): number[] => {
  try {
    const parsed = JSON.parse(localStorage.getItem(dismissKey(userKey)) || '[]');
    return Array.isArray(parsed) ? parsed.filter((n): n is number => typeof n === 'number') : [];
  } catch {
    return [];
  }
};

/**
 * Announcement banners a BillOps admin sent to this client, shown above the dashboard.
 * Collapsed, each shows its heading; clicking it expands to show the body. Dismissing hides a
 * banner for this user on this browser.
 */
const DashboardAnnouncementBanner: React.FC = () => {
  const { userProfile, authReady } = useAuth();
  const { theme } = useTheme();
  const dark = theme === 'dark';
  const userKey = String(userProfile?.id ?? userProfile?.email ?? 'anon');

  const [banners, setBanners] = useState<ClientBanner[]>([]);
  const [dismissed, setDismissed] = useState<number[]>(() => readDismissed(userKey));
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      setBanners(await fetchMyBanners());
    } catch {
      // A banner is never worth an error on the dashboard.
      setBanners([]);
    }
  }, []);

  useEffect(() => {
    if (!authReady) return;
    load();
    // Refetch when an admin switches the impersonated user or tenant.
    window.addEventListener('account-changed', load);
    return () => window.removeEventListener('account-changed', load);
  }, [authReady, load]);

  useEffect(() => { setDismissed(readDismissed(userKey)); }, [userKey]);

  const dismiss = (id: number) => {
    const next = Array.from(new Set([...dismissed, id]));
    setDismissed(next);
    localStorage.setItem(dismissKey(userKey), JSON.stringify(next));
    if (expandedId === id) setExpandedId(null);
  };

  const visible = banners.filter(b => !dismissed.includes(b.id));
  if (visible.length === 0) return null;

  return (
    <div className="px-6 pt-4 space-y-2">
      <AnimatePresence initial={false}>
        {visible.map(b => {
          const open = expandedId === b.id;
          return (
            <motion.div
              key={b.id}
              layout
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0, scale: open ? 1.005 : 1 }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
              className={clsx(
                'rounded-xl border overflow-hidden transition-shadow',
                open ? 'shadow-md' : 'shadow-sm',
                dark ? 'bg-slate-900 border-blue-900/60' : 'bg-blue-50/70 border-blue-200'
              )}
            >
              <div className="flex items-center gap-2 pr-2">
                <button
                  type="button"
                  onClick={() => setExpandedId(open ? null : b.id)}
                  aria-expanded={open}
                  aria-controls={`banner-body-${b.id}`}
                  className="flex-1 flex items-center gap-3 px-4 py-2.5 text-left min-w-0"
                >
                  <span className={clsx(
                    'w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0',
                    dark ? 'bg-blue-500/15' : 'bg-blue-100'
                  )}>
                    <Icon icon="mdi:bullhorn-outline" width={16} className={dark ? 'text-blue-300' : 'text-blue-600'} />
                  </span>
                  <span className={clsx(
                    'flex-1 text-[13px] font-semibold truncate',
                    dark ? 'text-slate-100' : 'text-blue-900'
                  )}>
                    {b.heading}
                  </span>
                  <span className={clsx('text-[11px] font-medium flex-shrink-0', dark ? 'text-blue-300' : 'text-blue-700')}>
                    {open ? 'Hide' : 'View'}
                  </span>
                  <Icon
                    icon="mdi:chevron-down"
                    width={18}
                    className={clsx('flex-shrink-0 transition-transform', open && 'rotate-180', dark ? 'text-blue-300' : 'text-blue-700')}
                  />
                </button>
                <button
                  type="button"
                  onClick={() => dismiss(b.id)}
                  aria-label={`Dismiss ${b.heading}`}
                  title="Dismiss"
                  className={clsx(
                    'p-1.5 rounded-md flex-shrink-0 transition-colors',
                    dark ? 'text-slate-400 hover:text-slate-100 hover:bg-slate-800' : 'text-blue-700/60 hover:text-blue-900 hover:bg-blue-100'
                  )}
                >
                  <Icon icon="mdi:close" width={15} />
                </button>
              </div>

              <AnimatePresence initial={false}>
                {open && (
                  <motion.div
                    id={`banner-body-${b.id}`}
                    key="body"
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.2, ease: 'easeOut' }}
                  >
                    <div className={clsx(
                      'px-4 pb-4 pl-14 text-[13px] leading-relaxed whitespace-pre-wrap break-words',
                      dark ? 'text-slate-300' : 'text-[rgba(0,0,0,0.72)]'
                    )}>
                      {b.body}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
};

export default DashboardAnnouncementBanner;
