/**
 * Start-a-call control for a ticket.
 *
 * Who gets rung is decided server-side from the ticket and the caller's role, so
 * this deliberately takes no audience argument — the same button means "ring the
 * support team" for a customer and "ring the people on this account" for an
 * admin.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { useCalls } from '../../contexts/CallContext';
import type { CallMediaKind } from '../../types/calls';

interface TicketCallButtonProps {
  ticketId: number;
  /** Ticket status; calling on a closed ticket is not offered. */
  disabled?: boolean;
  compact?: boolean;
  className?: string;
}

const TicketCallButton: React.FC<TicketCallButtonProps> = ({
  ticketId,
  disabled = false,
  compact = false,
  className = '',
}) => {
  const { startCall, phase, activeToken, socketConnected, callingEnabled } = useCalls();

  const onCall = phase !== 'idle' || !!activeToken;
  // Without a live socket we would place a call and never hear the answer; with
  // no LiveKit on the environment there is no room to join at all.
  const unavailable = disabled || !socketConnected || !callingEnabled;
  const isBusy = phase === 'connecting';

  const start = (mediaKind: CallMediaKind) => {
    if (onCall || unavailable) return;
    void startCall(ticketId, mediaKind);
  };

  const title = unavailable
    ? !callingEnabled
      ? 'Calling is not enabled on this environment'
      : !socketConnected
        ? 'Calling is offline — reconnecting'
        : 'Calling is unavailable for this ticket'
    : onCall
      ? 'You are already on a call'
      : undefined;

  const base =
    'inline-flex items-center gap-1 rounded-md border text-[11px] font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed';
  const size = compact ? 'px-1.5 py-1' : 'px-2 py-1.5';
  const skin =
    'border-[rgba(0,0,0,0.08)] dark:border-slate-700 hover:bg-[rgba(0,0,0,0.04)] dark:hover:bg-slate-800';

  return (
    <div className={`inline-flex items-center gap-1 ${className}`} title={title}>
      <button
        type="button"
        onClick={() => start('AUDIO')}
        disabled={onCall || unavailable || isBusy}
        className={`${base} ${size} ${skin}`}
        aria-label="Start an audio call on this ticket"
      >
        <Icon
          icon={isBusy ? 'mdi:loading' : 'mdi:phone'}
          width={13}
          className={isBusy ? 'animate-spin text-emerald-600' : 'text-emerald-600'}
        />
        {!compact && <span>Call</span>}
      </button>
      <button
        type="button"
        onClick={() => start('VIDEO')}
        disabled={onCall || unavailable || isBusy}
        className={`${base} ${compact ? 'px-1.5 py-1' : 'px-2 py-1.5'} ${skin}`}
        aria-label="Start a video call on this ticket"
      >
        <Icon icon="mdi:video" width={13} className="text-indigo-600" />
      </button>
    </div>
  );
};

export default TicketCallButton;
