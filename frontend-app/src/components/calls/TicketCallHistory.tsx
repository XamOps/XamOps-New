/**
 * Past calls on a ticket, including the ones nobody answered.
 *
 * The live MISSED toast only reaches someone who was at their desk — which is
 * precisely not the case for a missed call. This is the durable record, so a
 * call that rang while an agent was away is still visible when they come back.
 *
 * Renders nothing when a ticket has never had a call, so it stays invisible on
 * the overwhelming majority of tickets.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { useCalls } from '../../contexts/CallContext';
import { fetchTicketCallHistory } from '../../utils/callsApi';
import type { CallSessionDto, CallState } from '../../types/calls';

interface TicketCallHistoryProps {
  ticketId: number;
}

const STATE_STYLES: Record<CallState, { icon: string; tone: string; label: string }> = {
  RINGING: { icon: 'mdi:phone-ring', tone: 'text-amber-600', label: 'Ringing' },
  ACTIVE: { icon: 'mdi:phone-in-talk', tone: 'text-emerald-600', label: 'In progress' },
  ENDED: { icon: 'mdi:phone-check', tone: 'text-[rgba(0,0,0,0.45)]', label: 'Completed' },
  MISSED: { icon: 'mdi:phone-missed', tone: 'text-red-600', label: 'Missed' },
  DECLINED: { icon: 'mdi:phone-cancel', tone: 'text-red-500', label: 'Declined' },
  CANCELLED: { icon: 'mdi:phone-cancel', tone: 'text-[rgba(0,0,0,0.4)]', label: 'Cancelled' },
};

const formatWhen = (iso?: string): string => {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const duration = (call: CallSessionDto): string => {
  if (!call.answeredAt || !call.endedAt) return '';
  const secs = Math.round(
    (new Date(call.endedAt).getTime() - new Date(call.answeredAt).getTime()) / 1000
  );
  if (!Number.isFinite(secs) || secs <= 0) return '';
  const mins = Math.floor(secs / 60);
  return mins > 0 ? `${mins}m ${secs % 60}s` : `${secs}s`;
};

const TicketCallHistory: React.FC<TicketCallHistoryProps> = ({ ticketId }) => {
  const { phase } = useCalls();
  const [calls, setCalls] = useState<CallSessionDto[]>([]);

  const load = useCallback(async () => {
    try {
      setCalls(await fetchTicketCallHistory(ticketId));
    } catch {
      // History is supplementary; a failure here must not disturb the ticket page.
    }
  }, [ticketId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Refetch when a call finishes so the record it produced appears without a
  // manual reload.
  useEffect(() => {
    if (phase === 'idle') void load();
  }, [phase, load]);

  if (calls.length === 0) return null;

  return (
    <div className="mb-4">
      <div className="text-[11px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mb-2">
        Calls
      </div>
      <div className="flex flex-col gap-1.5">
        {calls.map(call => {
          const style = STATE_STYLES[call.state] ?? STATE_STYLES.ENDED;
          const length = duration(call);
          return (
            <div
              key={call.id}
              className="flex items-center gap-2 rounded-md border border-[rgba(0,0,0,0.08)] px-2.5 py-1.5 text-[12px]"
            >
              <Icon icon={style.icon} width={14} className={`${style.tone} flex-shrink-0`} />
              <span className={`font-medium ${style.tone}`}>{style.label}</span>
              <span className="text-[rgba(0,0,0,0.45)] truncate">
                {call.direction === 'ADMIN_TO_USER' ? 'from support' : 'from customer'}
                {call.initiatorName ? ` · ${call.initiatorName}` : ''}
                {call.answeredByName ? ` → ${call.answeredByName}` : ''}
              </span>
              <span className="ml-auto flex items-center gap-2 text-[11px] text-[rgba(0,0,0,0.35)] flex-shrink-0">
                {length && <span className="tabular-nums">{length}</span>}
                {call.mediaKind === 'VIDEO' && <Icon icon="mdi:video" width={12} />}
                <span>{formatWhen(call.createdAt)}</span>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default TicketCallHistory;
