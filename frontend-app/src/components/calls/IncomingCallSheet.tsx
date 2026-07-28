/**
 * The ring. Deliberately loud: a call is time-boxed, so unlike a toast this
 * takes over the corner of the screen and counts down to the point where it
 * becomes a missed call.
 */

import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { CallSignal } from '../../types/calls';

interface IncomingCallSheetProps {
  call: CallSignal;
  busy: boolean;
  onAccept: () => void;
  onDecline: () => void;
}

const IncomingCallSheet: React.FC<IncomingCallSheetProps> = ({
  call,
  busy,
  onAccept,
  onDecline,
}) => {
  // Counts down locally for feedback only. The server owns the real deadline, so
  // hitting zero here does not decide anything — the MISSED signal does.
  const [secondsLeft, setSecondsLeft] = useState(
    Math.max(0, Math.round(call.ringExpiresInSeconds ?? 40))
  );

  useEffect(() => {
    setSecondsLeft(Math.max(0, Math.round(call.ringExpiresInSeconds ?? 40)));
  }, [call.callId, call.ringExpiresInSeconds]);

  useEffect(() => {
    if (secondsLeft <= 0) return;
    const id = setTimeout(() => setSecondsLeft(s => Math.max(0, s - 1)), 1000);
    return () => clearTimeout(id);
  }, [secondsLeft]);

  const caller = call.initiatorName || 'Support';
  const initial = caller.charAt(0).toUpperCase();
  const isVideo = call.mediaKind === 'VIDEO';

  return (
    <div className="fixed bottom-4 right-4 z-[80] w-[min(94vw,340px)] rounded-xl bg-white dark:bg-slate-900 shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
      <div className="px-4 pt-4 pb-3 flex items-start gap-3">
        <div className="relative flex-shrink-0">
          <div className="w-12 h-12 rounded-full bg-indigo-500 text-white flex items-center justify-center text-lg font-semibold">
            {initial}
          </div>
          <span className="absolute -inset-1 rounded-full border-2 border-indigo-400/60 animate-ping" />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-[11px] font-medium text-indigo-600 dark:text-indigo-400 uppercase tracking-wide">
            <Icon icon={isVideo ? 'mdi:video' : 'mdi:phone-in-talk'} width={13} />
            Incoming {isVideo ? 'video' : 'audio'} call
          </div>
          <div className="text-sm font-semibold text-slate-900 dark:text-white truncate mt-0.5">
            {caller}
          </div>
          {call.ticketId != null && (
            <div className="text-[11px] text-slate-500 dark:text-slate-400 truncate">
              Ticket #{call.ticketId}
              {call.ticketSubject ? ` — ${call.ticketSubject}` : ''}
            </div>
          )}
        </div>

        <div className="text-[11px] tabular-nums text-slate-400 flex-shrink-0 pt-0.5">
          {secondsLeft}s
        </div>
      </div>

      <div className="flex gap-2 px-4 pb-4">
        <button
          onClick={onDecline}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1.5 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-200 text-xs font-medium py-2.5 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:phone-hangup" width={15} className="text-red-500" />
          Decline
        </button>
        <button
          onClick={onAccept}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1.5 rounded-lg bg-emerald-600 text-white text-xs font-medium py-2.5 hover:bg-emerald-700 transition-colors disabled:opacity-50"
        >
          <Icon
            icon={busy ? 'mdi:loading' : 'mdi:phone'}
            width={15}
            className={busy ? 'animate-spin' : ''}
          />
          {busy ? 'Joining…' : 'Answer'}
        </button>
      </div>
    </div>
  );
};

export default IncomingCallSheet;
