/**
 * Calls ringing at you while you are already on one.
 *
 * These used to be pushed into the "joinable" list, which is for calls somebody else
 * has already answered. The result was a grey pill reading "Call in progress" with a
 * disabled Join button, no caller name and no sound — while a real customer sat
 * ringing and then went to voicemail. An agent had no way to tell the difference
 * between "a colleague is on a call" and "someone is calling you right now".
 *
 * So this is deliberately styled as an active, urgent thing: amber, counting down,
 * with the caller named and both actions available. It is visually distinct from both
 * the full incoming sheet (which takes over a corner and rings) and the joinable pill
 * (which is passive and green).
 */

import React, { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { CallSignal } from '../../types/calls';

interface WaitingCallsStackProps {
  calls: CallSignal[];
  /** True while we are still on another call — changes what Answer means. */
  onAnotherCall: boolean;
  busy: boolean;
  onAnswer: (callId: number) => void;
  onDecline: (callId: number) => void;
}

/**
 * Counts down locally, purely so the agent can see this is time-boxed. The server
 * owns the real deadline; hitting zero here decides nothing.
 */
const useCountdown = (seconds?: number) => {
  const [left, setLeft] = useState(Math.max(0, Math.round(seconds ?? 40)));

  useEffect(() => {
    setLeft(Math.max(0, Math.round(seconds ?? 40)));
  }, [seconds]);

  useEffect(() => {
    if (left <= 0) return;
    const id = setTimeout(() => setLeft(s => Math.max(0, s - 1)), 1000);
    return () => clearTimeout(id);
  }, [left]);

  return left;
};

const severityTone = (severity?: string): string | null => {
  const raw = severity?.toLowerCase();
  if (!raw) return null;
  if (raw.includes('critical') || raw.includes('urgent') || raw.includes('1')) {
    return 'bg-rose-500/20 text-rose-700 dark:text-rose-300';
  }
  if (raw.includes('high') || raw.includes('2')) {
    return 'bg-amber-500/20 text-amber-700 dark:text-amber-300';
  }
  return 'bg-slate-500/15 text-slate-600 dark:text-slate-300';
};

const WaitingCallCard: React.FC<{
  call: CallSignal;
  onAnotherCall: boolean;
  busy: boolean;
  onAnswer: () => void;
  onDecline: () => void;
}> = ({ call, onAnotherCall, busy, onAnswer, onDecline }) => {
  const secondsLeft = useCountdown(call.ringExpiresInSeconds);
  const caller = call.initiatorName || 'Support';
  const tone = severityTone(call.ticketSeverity);

  return (
    <motion.div
      initial={{ opacity: 0, x: -16, scale: 0.97 }}
      animate={{ opacity: 1, x: 0, scale: 1 }}
      exit={{ opacity: 0, x: -12, scale: 0.97 }}
      transition={{ type: 'spring', stiffness: 380, damping: 30 }}
      className="w-[min(92vw,320px)] rounded-xl border border-amber-500/40 bg-white/90 dark:bg-slate-900/90 backdrop-blur-xl shadow-[0_16px_40px_-8px_rgba(245,158,11,0.35)] overflow-hidden"
    >
      <div className="h-0.5 w-full bg-gradient-to-r from-amber-500 to-orange-500" />

      <div className="px-3 pt-2.5 pb-2 flex items-start gap-2.5">
        <div className="relative flex-shrink-0 mt-0.5">
          <span className="flex h-7 w-7 items-center justify-center rounded-full bg-amber-500/15">
            <Icon icon="mdi:phone-alert" width={14} className="text-amber-600 dark:text-amber-400" />
          </span>
          {/* Slow pulse, not the ring sheet's urgent one: this is a nudge, not a seizure. */}
          <span className="absolute -inset-0.5 rounded-full border border-amber-400/40 animate-pulse" />
        </div>

        <div className="min-w-0 flex-1">
          <div className="text-[10px] font-semibold uppercase tracking-wide text-amber-600 dark:text-amber-400">
            Call waiting
          </div>
          <div className="text-[12px] font-semibold text-slate-900 dark:text-white truncate">
            {caller}
          </div>
          {call.ticketId != null && (
            <div className="text-[10px] text-slate-500 dark:text-slate-400 truncate">
              Ticket #{call.ticketId}
              {call.ticketSubject ? ` — ${call.ticketSubject}` : ''}
            </div>
          )}
          {(tone || call.ticketCategory) && (
            <div className="flex items-center gap-1 mt-1">
              {tone && (
                <span className={`px-1 py-0.5 rounded text-[9px] font-semibold uppercase ${tone}`}>
                  {call.ticketSeverity}
                </span>
              )}
              {call.ticketCategory && (
                <span className="px-1 py-0.5 rounded text-[9px] font-medium bg-slate-500/10 text-slate-600 dark:text-slate-300 truncate">
                  {call.ticketCategory}
                </span>
              )}
            </div>
          )}
        </div>

        <span
          className="text-[11px] font-semibold tabular-nums text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5"
          title="Becomes a missed call when this reaches zero"
        >
          {secondsLeft}s
        </span>
      </div>

      <div className="flex gap-1.5 px-3 pb-2.5">
        <button
          onClick={onDecline}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1 rounded-lg border border-slate-300 dark:border-slate-600 text-slate-700 dark:text-slate-200 text-[11px] font-medium py-1.5 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:phone-hangup" width={13} />
          Decline
        </button>
        <button
          onClick={onAnswer}
          disabled={busy}
          className="flex-1 flex items-center justify-center gap-1 rounded-lg bg-gradient-to-r from-amber-500 to-orange-500 text-white text-[11px] font-semibold py-1.5 shadow shadow-amber-500/25 hover:brightness-110 transition-all disabled:opacity-50"
          // Spelled out rather than left to be discovered: pressing this while on a
          // call ends that call, and an agent should know that before clicking.
          title={
            onAnotherCall
              ? 'Ends your current call, then answers this one'
              : 'Answer this call'
          }
        >
          <Icon icon={busy ? 'mdi:loading' : 'mdi:phone'} width={13} className={busy ? 'animate-spin' : ''} />
          {onAnotherCall ? 'End & answer' : 'Answer'}
        </button>
      </div>
    </motion.div>
  );
};

const WaitingCallsStack: React.FC<WaitingCallsStackProps> = ({
  calls,
  onAnotherCall,
  busy,
  onAnswer,
  onDecline,
}) => {
  if (calls.length === 0) return null;

  return (
    <div className="fixed top-4 right-4 z-[78] flex flex-col gap-2">
      {calls.map(call => (
        <WaitingCallCard
          key={call.callId}
          call={call}
          onAnotherCall={onAnotherCall}
          busy={busy}
          onAnswer={() => onAnswer(call.callId)}
          onDecline={() => onDecline(call.callId)}
        />
      ))}
    </div>
  );
};

export default WaitingCallsStack;
