import React, { useEffect } from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { AuditLogEntry } from '../../types/cloudsitter';

interface Props {
  accountId: string | null;
  logs: AuditLogEntry[];
  isLoading: boolean;
  totalPages: number;
  page: number;
  onLoad: (page?: number) => void;
}

function formatTs(ts: string): string {
  try {
    return new Date(ts).toLocaleString('en-IN', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit', hour12: true,
    });
  } catch {
    return ts;
  }
}

const CloudSitterAuditLogTab: React.FC<Props> = ({ accountId, logs, isLoading, totalPages, page, onLoad }) => {
  useEffect(() => {
    if (accountId) onLoad(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId]);

  return (
    <div>
      {/* Toolbar */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-4 py-3 mb-5 flex items-center gap-3">
        <Icon icon="mdi:history" width={14} color="rgba(0,0,0,0.4)" />
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.55)]">Stop / Start Events</span>
        <div className="ml-auto flex items-center gap-2">
          {totalPages > 1 && (
            <div className="flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.45)]">
              <button
                disabled={page === 0}
                onClick={() => onLoad(page - 1)}
                className="px-2 py-1 rounded border border-[rgba(0,0,0,0.1)] disabled:opacity-30 hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              >
                <Icon icon="mdi:chevron-left" width={13} />
              </button>
              <span className="px-1">Page {page + 1} / {totalPages}</span>
              <button
                disabled={page >= totalPages - 1}
                onClick={() => onLoad(page + 1)}
                className="px-2 py-1 rounded border border-[rgba(0,0,0,0.1)] disabled:opacity-30 hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              >
                <Icon icon="mdi:chevron-right" width={13} />
              </button>
            </div>
          )}
          <button
            onClick={() => onLoad(page)}
            disabled={isLoading}
            className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40 border border-[rgba(0,0,0,0.1)] rounded-lg px-3 py-1.5"
          >
            <svg className={`w-3 h-3 ${isLoading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            Refresh
          </button>
        </div>
      </div>

      {/* Table */}
      {isLoading ? (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
              <div className="w-7 h-7 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="h-2.5 w-48 bg-[rgba(0,0,0,0.06)] rounded" />
                <div className="h-2 w-32 bg-[rgba(0,0,0,0.04)] rounded" />
              </div>
              <div className="h-5 w-20 bg-[rgba(0,0,0,0.04)] rounded-full" />
            </div>
          ))}
        </div>
      ) : !accountId || logs.length === 0 ? (
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.22, ease: 'easeOut' }}
          className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center"
        >
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:history" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Events Yet</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            {accountId
              ? 'CloudSitter has not triggered any stop or start actions for this account.'
              : 'Select an account to view audit events.'}
          </p>
        </motion.div>
      ) : (
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
          className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden"
        >
          {/* Header row */}
          <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
            <div className="col-span-1">Action</div>
            <div className="col-span-3">Instance</div>
            <div className="col-span-2">Region</div>
            <div className="col-span-2">Policy</div>
            <div className="col-span-1">Prev State</div>
            <div className="col-span-1">Outcome</div>
            <div className="col-span-2">Time</div>
          </div>

          {logs.map((log, idx) => {
            const isStart = log.action === 'STARTED';
            const isSuccess = log.outcome === 'SUCCESS';

            return (
              <div
                key={log.id}
                className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
                style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.03, 0.45)}s both` }}
              >
                {/* Action badge */}
                <div className="col-span-1">
                  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                    isStart
                      ? 'bg-emerald-50 text-emerald-700 border-emerald-100'
                      : 'bg-slate-50 text-slate-600 border-slate-200'
                  }`}>
                    <Icon icon={isStart ? 'mdi:play' : 'mdi:stop'} width={10} />
                    {log.action}
                  </span>
                </div>

                {/* Instance */}
                <div className="col-span-3 min-w-0">
                  <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate">{log.instanceName || log.instanceId}</p>
                  {log.instanceName && (
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)] font-mono truncate">{log.instanceId}</p>
                  )}
                </div>

                {/* Region */}
                <div className="col-span-2 text-[rgba(0,0,0,0.55)] text-[12px]">{log.region || '—'}</div>

                {/* Policy */}
                <div className="col-span-2">
                  {log.policyName ? (
                    <span className="inline-flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.55)]">
                      <Icon icon="mdi:clock-outline" width={11} color="rgba(0,0,0,0.35)" />
                      {log.policyName}
                    </span>
                  ) : <span className="text-[rgba(0,0,0,0.25)]">—</span>}
                </div>

                {/* Previous State */}
                <div className="col-span-1">
                  <span className="capitalize text-[11px] text-[rgba(0,0,0,0.45)]">{log.previousState || '—'}</span>
                </div>

                {/* Outcome */}
                <div className="col-span-1">
                  <span className={`inline-flex items-center gap-1 text-[11px] font-semibold ${
                    isSuccess ? 'text-emerald-600' : 'text-red-500'
                  }`}>
                    <Icon icon={isSuccess ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'} width={12} />
                    {log.outcome}
                  </span>
                  {!isSuccess && log.errorMessage && (
                    <p className="text-[10px] text-red-400 truncate mt-0.5" title={log.errorMessage}>
                      {log.errorMessage.slice(0, 40)}{log.errorMessage.length > 40 ? '…' : ''}
                    </p>
                  )}
                </div>

                {/* Timestamp */}
                <div className="col-span-2 text-[11px] text-[rgba(0,0,0,0.45)]">
                  {formatTs(log.timestamp)}
                </div>
              </div>
            );
          })}
        </motion.div>
      )}
    </div>
  );
};

export default CloudSitterAuditLogTab;
