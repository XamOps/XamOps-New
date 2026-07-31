/**
 * Past calls on a ticket, including the ones nobody answered.
 *
 * The live MISSED toast only reaches someone who was at their desk — which is
 * precisely not the case for a missed call. This is the durable record, so a
 * call that rang while an agent was away is still visible when they come back.
 *
 * Presentation rules (deliberate, they fight alarm fatigue):
 *  - The most recent call is the only one shown at full weight.
 *  - Older calls are metadata: smaller, muted, icon-first — never competing with
 *    real conversation content.
 *  - Consecutive same-outcome calls collapse into one summary row
 *    ("5 missed calls · 16:03–16:12") that expands on demand, so eight repeated
 *    red "Missed" rows stop drowning the thread.
 *
 * Renders nothing when a ticket has never had a call, so it stays invisible on
 * the overwhelming majority of tickets.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { useCalls } from '../../contexts/CallContext';
import { fetchTicketCallHistory } from '../../utils/callsApi';
import type { CallSessionDto, CallState } from '../../types/calls';

interface TicketCallHistoryProps {
  ticketId: number;
}

interface StateStyle {
  icon: string;
  /** Full-weight tone — only used for the most recent call. */
  tone: string;
  /** Muted tone for historical rows, so repetition stops shouting. */
  quietTone: string;
  label: string;
  /** Plural noun used in a collapsed group summary. */
  group: string;
}

const STATE_STYLES: Record<CallState, StateStyle> = {
  RINGING: { icon: 'mdi:phone-ring', tone: 'text-amber-600', quietTone: 'text-[rgba(0,0,0,0.45)]', label: 'Ringing', group: 'ringing calls' },
  ACTIVE: { icon: 'mdi:phone-in-talk', tone: 'text-emerald-600', quietTone: 'text-[rgba(0,0,0,0.45)]', label: 'In progress', group: 'active calls' },
  ENDED: { icon: 'mdi:phone-check', tone: 'text-[rgba(0,0,0,0.5)]', quietTone: 'text-[rgba(0,0,0,0.4)]', label: 'Completed', group: 'completed calls' },
  MISSED: { icon: 'mdi:phone-missed', tone: 'text-red-600', quietTone: 'text-[rgba(0,0,0,0.45)]', label: 'Missed', group: 'missed calls' },
  DECLINED: { icon: 'mdi:phone-cancel', tone: 'text-red-500', quietTone: 'text-[rgba(0,0,0,0.45)]', label: 'Declined', group: 'declined calls' },
  CANCELLED: { icon: 'mdi:phone-cancel', tone: 'text-[rgba(0,0,0,0.45)]', quietTone: 'text-[rgba(0,0,0,0.4)]', label: 'Cancelled', group: 'cancelled calls' },
};

const styleFor = (state: CallState): StateStyle => STATE_STYLES[state] ?? STATE_STYLES.ENDED;

const formatDay = (iso?: string): string => {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
};

const formatTime = (iso?: string): string => {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
};

const formatWhen = (iso?: string): string => {
  const day = formatDay(iso);
  const time = formatTime(iso);
  return day && time ? `${day}, ${time}` : day || time;
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

const participantsLabel = (call: CallSessionDto): string =>
  [
    call.direction === 'ADMIN_TO_USER' ? 'from support' : 'from customer',
    call.initiatorName || null,
    call.answeredByName ? `→ ${call.answeredByName}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

/** Newest-first run of calls that share an outcome. */
interface CallGroup {
  key: string;
  state: CallState;
  calls: CallSessionDto[];
}

function groupConsecutive(calls: CallSessionDto[]): CallGroup[] {
  const groups: CallGroup[] = [];
  calls.forEach((call) => {
    const last = groups[groups.length - 1];
    if (last && last.state === call.state) {
      last.calls.push(call);
    } else {
      groups.push({ key: `${call.state}-${call.id}`, state: call.state, calls: [call] });
    }
  });
  return groups;
}

/** Timestamp span of a newest-first group, e.g. "16:03–16:12" or "28 Jul–30 Jul". */
function groupRange(group: CallGroup): string {
  const newest = group.calls[0]?.createdAt;
  const oldest = group.calls[group.calls.length - 1]?.createdAt;
  if (!newest || !oldest) return '';
  const sameDay = formatDay(newest) === formatDay(oldest);
  if (sameDay) return `${formatDay(oldest)}, ${formatTime(oldest)}–${formatTime(newest)}`;
  return `${formatWhen(oldest)} – ${formatWhen(newest)}`;
}

/** One historical call, rendered as metadata rather than content. */
const QuietRow: React.FC<{ call: CallSessionDto; indented?: boolean }> = ({ call, indented }) => {
  const style = styleFor(call.state);
  const length = duration(call);
  return (
    <div
      className={`flex items-center gap-2 py-1 text-[11px] ${indented ? 'pl-8 pr-3' : 'px-3'}`}
    >
      <Icon icon={style.icon} width={12} className={`${style.quietTone} flex-shrink-0`} />
      <span className={`font-medium ${style.quietTone}`}>{style.label}</span>
      <span className="text-[rgba(0,0,0,0.38)] truncate">{participantsLabel(call)}</span>
      <span className="ml-auto flex items-center gap-2 text-[10.5px] text-[rgba(0,0,0,0.3)] flex-shrink-0">
        {length && <span className="tabular-nums">{length}</span>}
        {call.mediaKind === 'VIDEO' && <Icon icon="mdi:video-outline" width={11} />}
        <span className="tabular-nums">{formatWhen(call.createdAt)}</span>
      </span>
    </div>
  );
};

const TicketCallHistory: React.FC<TicketCallHistoryProps> = ({ ticketId }) => {
  const { phase } = useCalls();
  const [calls, setCalls] = useState<CallSessionDto[]>([]);
  const [sectionOpen, setSectionOpen] = useState(true);
  const [showOlder, setShowOlder] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});

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

  const sorted = useMemo(
    () =>
      [...calls].sort(
        (a, b) => new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime()
      ),
    [calls]
  );

  const latest = sorted[0];
  const older = useMemo(() => sorted.slice(1), [sorted]);
  const groups = useMemo(() => groupConsecutive(older), [older]);

  if (calls.length === 0 || !latest) return null;

  const latestStyle = styleFor(latest.state);
  const latestLength = duration(latest);
  const missedCount = sorted.filter((c) => c.state === 'MISSED').length;

  return (
    <section className="rounded-xl border border-[#e5e7eb] bg-white overflow-hidden">
      {/* Section header */}
      <button
        onClick={() => setSectionOpen((v) => !v)}
        className="w-full flex items-center gap-2 px-3 py-2 bg-[#fafafa] border-b border-[#e5e7eb] text-left hover:bg-[#f5f5f5] transition-colors"
      >
        <Icon icon="mdi:phone-outline" width={13} className="text-[rgba(0,0,0,0.4)]" />
        <span className="text-[10.5px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-widest">
          Call History
        </span>
        <span className="px-1.5 py-0.5 rounded-full bg-[rgba(0,0,0,0.06)] text-[10px] font-semibold text-[rgba(0,0,0,0.5)] tabular-nums">
          {sorted.length}
        </span>
        {missedCount > 0 && (
          <span className="text-[10px] font-medium text-[rgba(0,0,0,0.4)]">
            {missedCount} missed
          </span>
        )}
        <Icon
          icon="mdi:chevron-down"
          width={14}
          className={`ml-auto text-[rgba(0,0,0,0.3)] transition-transform ${sectionOpen ? '' : '-rotate-90'}`}
        />
      </button>

      {sectionOpen && (
        <div className="divide-y divide-[#f1f2f4]">
          {/* Most recent call — the only one at full weight */}
          <div className="flex items-center gap-2.5 px-3 py-2.5">
            <span className="w-7 h-7 rounded-lg bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
              <Icon icon={latestStyle.icon} width={14} className={latestStyle.tone} />
            </span>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className={`text-[12.5px] font-semibold ${latestStyle.tone}`}>
                  {latestStyle.label}
                </span>
                <span className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[9px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">
                  Latest
                </span>
              </div>
              <div className="text-[11px] text-[rgba(0,0,0,0.45)] truncate">
                {participantsLabel(latest)}
              </div>
            </div>
            <span className="ml-auto flex items-center gap-2 text-[11px] text-[rgba(0,0,0,0.35)] flex-shrink-0">
              {latestLength && <span className="tabular-nums">{latestLength}</span>}
              {latest.mediaKind === 'VIDEO' && <Icon icon="mdi:video-outline" width={12} />}
              <span className="tabular-nums">{formatWhen(latest.createdAt)}</span>
            </span>
          </div>

          {/* Everything older is history — collapsed behind one line by default */}
          {older.length > 0 && (
            <div>
              <button
                onClick={() => setShowOlder((v) => !v)}
                className="w-full flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[#fafafa] transition-colors"
              >
                <Icon
                  icon="mdi:chevron-right"
                  width={13}
                  className={`transition-transform ${showOlder ? 'rotate-90' : ''}`}
                />
                {showOlder ? 'Hide' : 'Show'} {older.length} earlier call
                {older.length === 1 ? '' : 's'}
              </button>

              {showOlder && (
                <div className="pb-1.5">
                  {groups.map((group) => {
                    if (group.calls.length === 1) {
                      return <QuietRow key={group.key} call={group.calls[0]} />;
                    }
                    const isExpanded = !!expandedGroups[group.key];
                    const style = styleFor(group.state);
                    return (
                      <div key={group.key}>
                        <button
                          onClick={() =>
                            setExpandedGroups((prev) => ({ ...prev, [group.key]: !prev[group.key] }))
                          }
                          className="w-full flex items-center gap-2 px-3 py-1 text-[11px] hover:bg-[#fafafa] transition-colors text-left"
                        >
                          <Icon
                            icon={style.icon}
                            width={12}
                            className={`${style.quietTone} flex-shrink-0`}
                          />
                          <span className="font-medium text-[rgba(0,0,0,0.5)]">
                            {group.calls.length} {style.group}
                          </span>
                          <span className="text-[rgba(0,0,0,0.35)] tabular-nums truncate">
                            {groupRange(group)}
                          </span>
                          <Icon
                            icon="mdi:chevron-right"
                            width={12}
                            className={`ml-auto text-[rgba(0,0,0,0.25)] flex-shrink-0 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
                          />
                        </button>
                        {isExpanded && (
                          <div className="border-l border-[#eceef0] ml-4">
                            {group.calls.map((call) => (
                              <QuietRow key={call.id} call={call} indented />
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </section>
  );
};

export default TicketCallHistory;
