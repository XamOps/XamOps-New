/**
 * The ring. Deliberately loud: a call is time-boxed, so unlike a toast this
 * takes over the corner of the screen and counts down to the point where it
 * becomes a missed call.
 *
 * Styled to match the Add Account premium language: frosted glass panel,
 * indigo→violet accent, emerald answer / rose decline gradients, motion entrance
 * and a soft pulsing ring on the caller avatar.
 */

import React, { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
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
    <motion.div
      initial={{ opacity: 0, y: 24, scale: 0.96 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: 16, scale: 0.97 }}
      transition={{ type: 'spring', stiffness: 380, damping: 30 }}
      className="fixed bottom-4 right-4 z-[80] w-[min(94vw,344px)] rounded-2xl bg-white/85 dark:bg-slate-900/85 backdrop-blur-xl shadow-[0_24px_48px_-8px_rgba(99,102,241,0.35)] border border-indigo-400/20 overflow-hidden"
    >
      {/* Gradient top accent */}
      <div className="h-1 w-full bg-gradient-to-r from-indigo-500 via-violet-500 to-fuchsia-500" />

      <div className="px-4 pt-4 pb-3 flex items-start gap-3">
        <div className="relative flex-shrink-0">
          <div className="w-12 h-12 rounded-full bg-gradient-to-br from-indigo-500 to-violet-500 text-white flex items-center justify-center text-lg font-semibold shadow-lg shadow-indigo-500/30">
            {initial}
          </div>
          <span className="absolute -inset-1 rounded-full border-2 border-indigo-400/50 animate-ping" />
          <span className="absolute -bottom-0.5 -right-0.5 w-5 h-5 rounded-full bg-white dark:bg-slate-900 flex items-center justify-center shadow">
            <Icon
              icon={isVideo ? 'mdi:video' : 'mdi:phone'}
              width={11}
              className="text-indigo-500"
            />
          </span>
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-[11px] font-semibold text-indigo-600 dark:text-indigo-400 uppercase tracking-wide">
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

        <div className="flex flex-col items-center flex-shrink-0 pt-0.5">
          <span className="text-[13px] font-semibold tabular-nums text-slate-600 dark:text-slate-300">
            {secondsLeft}s
          </span>
        </div>
      </div>

      <div className="flex gap-2 px-4 pb-4">
        <button
          onClick={onDecline}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-rose-500 to-red-500 text-white text-xs font-semibold py-2.5 shadow-lg shadow-rose-500/25 hover:brightness-110 transition-all disabled:opacity-50"
        >
          <Icon icon="mdi:phone-hangup" width={15} />
          Decline
        </button>
        <button
          onClick={onAccept}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 text-white text-xs font-semibold py-2.5 shadow-lg shadow-emerald-500/25 hover:brightness-110 transition-all disabled:opacity-50"
        >
          <Icon
            icon={busy ? 'mdi:loading' : 'mdi:phone'}
            width={15}
            className={busy ? 'animate-spin' : ''}
          />
          {busy ? 'Joining…' : 'Answer'}
        </button>
      </div>
    </motion.div>
  );
};

export default IncomingCallSheet;
