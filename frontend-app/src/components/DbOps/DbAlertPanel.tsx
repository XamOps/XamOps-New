import { useState, useRef, useEffect } from 'react';
import { DbHealthAlertDto } from '../../hooks/useDbOpsData';

function timeAgo(iso: string) {
  const ms = Date.now() - new Date(iso).getTime();
  const m  = Math.floor(ms / 60000);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

const typeLabels: Record<string, string> = {
  DB_HEALTH_SCORE: 'Health Score',
  DB_CONNECTION_SATURATION: 'Connections',
  DB_SLOW_QUERIES: 'Slow Queries',
  DB_REPLICA_LAG: 'Replica Lag',
  DB_BLOAT: 'Table Bloat',
  DB_SECURITY: 'Security',
  DB_PRIVILEGE_ERROR: 'Agent Error',
};

export function DbAlertPanel({ alerts }: { alerts: DbHealthAlertDto[] }) {
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const total    = alerts.filter(a => a.active).length;
  const critical = alerts.filter(a => a.active && a.severity === 'CRITICAL').length;

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  return (
    <div className="relative" ref={panelRef}>
      <button
        onClick={() => setOpen(o => !o)}
        className="relative flex items-center justify-center w-8 h-8 rounded-md bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.25)] transition-colors"
        title="DB Health Alerts"
      >
        <i className="fas fa-bell text-xs" />
        {total > 0 && (
          <span className={`absolute -top-1 -right-1 min-w-[16px] h-4 flex items-center justify-center rounded-full text-[9px] font-bold text-white px-1 ${critical > 0 ? 'bg-red-500' : 'bg-amber-500'}`}>
            {total}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-2 w-96 max-h-[480px] overflow-y-auto inventory-hide-scrollbar z-50 bg-white rounded-xl shadow-lg border border-[rgba(0,0,0,0.1)]">
          <div className="px-4 py-3 border-b border-gray-100 dark:border-gray-700 flex items-center justify-between">
            <p className="text-sm font-semibold text-gray-800 dark:text-gray-100">DB Health Alerts</p>
            <span className="text-[11px] text-gray-400">{total} active</span>
          </div>
          {alerts.length === 0 ? (
            <div className="px-4 py-8 text-center">
              <i className="fas fa-circle-check text-emerald-400 text-2xl mb-2 block" />
              <p className="text-sm text-gray-400">All databases healthy</p>
            </div>
          ) : alerts.map(a => (
            <div key={a.id} className={`px-4 py-3 border-b border-gray-50 dark:border-gray-700/50 ${!a.active ? 'opacity-50' : ''}`}>
              <div className="flex items-start gap-3">
                <span className={`mt-1 shrink-0 w-2 h-2 rounded-full ${!a.active ? 'bg-gray-400' : a.severity === 'CRITICAL' ? 'bg-red-500' : 'bg-amber-500'}`} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[11px] font-semibold text-gray-700 dark:text-gray-300">
                      {typeLabels[a.alertType] ?? a.alertType}
                    </span>
                    <span className="text-[10px] text-gray-400 shrink-0">{timeAgo(a.triggeredAt)}</span>
                  </div>
                  <p className="text-[11px] text-gray-500 dark:text-gray-400 mt-0.5 break-words">{a.message}</p>
                  <p className="text-[10px] font-mono text-gray-400 mt-0.5">{a.instanceId}</p>
                  {!a.active && a.resolvedAt && (
                    <p className="text-[10px] text-emerald-500 mt-0.5">
                      <i className="fas fa-check mr-1" />Resolved {timeAgo(a.resolvedAt)}
                    </p>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
