/**
 * Missed-call indicator for the topbar.
 *
 * A live MISSED toast only reaches an agent who was at their desk the moment a
 * call expired — which is exactly not the case for a missed call. This is the
 * durable, always-visible counterpart: a small bell that carries whatever calls
 * rang while you were away, on whatever page you happen to be on.
 *
 * Renders nothing when there is nothing missed, so it stays out of the way on
 * the overwhelming majority of sessions.
 */

import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useCalls } from '../../contexts/CallContext';
import { useAuth } from '../../contexts/AuthContext';
import type { MissedCallEntry } from '../../types/calls';

/** Compact "3m ago" / "2h ago" style; falls back to a date once it is old. */
const timeAgo = (iso?: string): string => {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const secs = Math.round((Date.now() - then) / 1000);
  if (secs < 60) return 'just now';
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
};

const MissedCallsBell: React.FC = () => {
  const { missedCalls, dismissMissedCall, clearMissedCalls } = useCalls();
  const { hasAnyRole } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  // Close the panel automatically once the last missed call is cleared, so an
  // empty dropdown never hangs open.
  useEffect(() => {
    if (missedCalls.length === 0) setOpen(false);
  }, [missedCalls.length]);

  if (missedCalls.length === 0) return null;

  /**
   * The detail route differs by side, and picking the wrong one 401s: the admin
   * pages read from billops, the customer page from xamops. Direction is the
   * reliable signal here — a USER_TO_ADMIN miss was rung at an admin — with the
   * role check as a fallback when a signal arrived without one.
   */
  const detailPathFor = (call: MissedCallEntry): string => {
    const admin =
      call.direction === 'USER_TO_ADMIN' ||
      (!call.direction && hasAnyRole('ROLE_BILLOPS_ADMIN'));
    return admin ? '/billops-ticket-detail' : '/ticket-detail';
  };

  const openCall = (call: MissedCallEntry) => {
    dismissMissedCall(call.participantId);
    setOpen(false);
    if (call.ticketId != null) {
      navigate(`${detailPathFor(call)}?id=${call.ticketId}`);
    }
  };

  const count = missedCalls.length;

  return (
    <div className="relative" ref={wrapRef}>
      <button
        onClick={() => setOpen(o => !o)}
        className="w-8 h-8 flex items-center justify-center rounded-md transition-colors relative text-rose-500 hover:bg-[rgba(0,0,0,0.06)] dark:hover:bg-[rgba(255,255,255,0.08)]"
        aria-label={`${count} missed call${count === 1 ? '' : 's'}`}
        aria-expanded={open}
        title={`${count} missed call${count === 1 ? '' : 's'}`}
      >
        <Icon icon="mdi:phone-missed" width={15} />
        <span className="absolute -top-0.5 -right-0.5 min-w-[15px] h-[15px] px-1 flex items-center justify-center text-[9px] font-semibold leading-none text-white bg-rose-500 rounded-full ring-2 ring-white dark:ring-[#1f1f1f]">
          {count > 9 ? '9+' : count}
        </span>
      </button>

      {open && (
        <div className="absolute top-full right-0 mt-1.5 w-72 rounded-xl border border-[rgba(0,0,0,0.06)] dark:border-slate-700 bg-white dark:bg-slate-900 shadow-lg z-50 overflow-hidden">
          <div className="flex items-center justify-between px-3 py-2.5 border-b border-[rgba(0,0,0,0.06)] dark:border-slate-700">
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] dark:text-slate-400 uppercase tracking-widest">
              Missed calls
            </span>
            <button
              onClick={clearMissedCalls}
              className="text-[11px] font-medium text-indigo-600 dark:text-indigo-400 hover:underline"
            >
              Clear all
            </button>
          </div>

          <div className="max-h-80 overflow-y-auto">
            {missedCalls.map(call => (
              <div
                key={call.participantId}
                className="group flex items-center gap-2.5 px-3 py-2.5 hover:bg-[rgba(0,0,0,0.03)] dark:hover:bg-slate-800 transition-colors"
              >
                <button
                  onClick={() => openCall(call)}
                  className="flex items-center gap-2.5 min-w-0 flex-1 text-left"
                >
                  <Icon
                    icon="mdi:phone-missed"
                    width={16}
                    className="text-rose-500 flex-shrink-0"
                  />
                  <div className="min-w-0">
                    <div className="text-[12px] font-medium text-[rgba(0,0,0,0.85)] dark:text-white truncate">
                      {call.initiatorName || 'Unknown caller'}
                    </div>
                    <div className="text-[11px] text-[rgba(0,0,0,0.45)] dark:text-slate-400 truncate">
                      {call.ticketId != null ? `Ticket #${call.ticketId}` : 'Support call'}
                      {call.at ? ` · ${timeAgo(call.at)}` : ''}
                    </div>
                  </div>
                </button>
                <button
                  onClick={() => dismissMissedCall(call.participantId)}
                  className="p-1 rounded hover:bg-[rgba(0,0,0,0.06)] dark:hover:bg-slate-700 flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
                  aria-label="Dismiss missed call"
                  title="Dismiss"
                >
                  <Icon icon="mdi:close" width={13} className="text-[rgba(0,0,0,0.4)] dark:text-slate-400" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default MissedCallsBell;
