import React from 'react';
import type { RemediationSummary } from '../../../types/dspmPages';
import { InfoIcon } from '../dashboard/DashboardParts';
import { cardActive, cardStatus, type RemediationCard } from './remediationModel';

/** The console's card titles and tooltips (`index-4ef0511f.js`). */
const CARD_TEXT: Record<RemediationCard, { label: string; tooltip: string }> = {
  total: { label: 'Total events', tooltip: 'All remediation events, across every status' },
  waitingApproval: { label: 'Waiting approval', tooltip: 'Events awaiting reviewer approval' },
  inProgressScheduled: { label: 'In progress / scheduled', tooltip: 'Events currently running or scheduled to run' },
  completed: { label: 'Completed', tooltip: 'Events that finished successfully' },
  failed: { label: 'Failed', tooltip: 'Events that failed to complete' },
};

/** The Total bar's segments and the console's colours (`constants-a63ffc8b.js`). Full literals, for Tailwind. */
const SEGMENTS: ReadonlyArray<{ card: Exclude<RemediationCard, 'total'>; colour: string }> = [
  { card: 'waitingApproval', colour: 'bg-[#EF6C0066]' },
  { card: 'inProgressScheduled', colour: 'bg-[#F9A82566]' },
  { card: 'completed', colour: 'bg-[#24A14866]' },
  { card: 'failed', colour: 'bg-[#DA1E2899]' },
];

interface Props {
  summary: RemediationSummary;
  statusCounts: Record<string, number>;
  /** The Status filter; '' for All. */
  status: string;
  /** Called with the status a card or segment filters by. */
  onSelect: (status: string) => void;
}

const SegmentBar: React.FC<Omit<Props, 'status'>> = ({ summary, statusCounts, onSelect }) => {
  const total = summary.total;
  return (
    <div data-testid="remediation-total-bar" className="mt-2 flex h-2 w-full overflow-hidden rounded-full bg-[rgba(0,0,0,0.06)]">
      {total > 0 && SEGMENTS.map(({ card, colour }) => {
        const value = summary[card];
        if (value <= 0) return null;
        const filter = cardStatus(card, statusCounts);
        const style = { width: `${(value / total) * 100}%` };
        const name = `${CARD_TEXT[card].label}: ${value.toLocaleString('en-US')}`;
        return filter === null
          ? <span key={card} title={name} className={`h-full ${colour}`} style={style} />
          : (
            <button
              key={card}
              type="button"
              aria-label={name}
              title={name}
              onClick={() => onSelect(filter)}
              className={`h-full ${colour} focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]`}
              style={style}
            />
          );
      })}
    </div>
  );
};

/**
 * The analytics cards above the Activity table (FR-REM-3): Total events with its segmented bar, then
 * Waiting approval, In progress / scheduled, Completed and Failed. A card, or a segment of the Total bar,
 * sets the Status filter to its status (again: clears it); Total itself filters nothing, and In progress /
 * scheduled filters nothing while both of its statuses have runs (`cardStatus`).
 */
const RemediationCards: React.FC<Props> = ({ summary, statusCounts, status, onSelect }) => {
  const cards: ReadonlyArray<Exclude<RemediationCard, 'total'>> = ['waitingApproval', 'inProgressScheduled', 'completed', 'failed'];
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3">
        <CardTitle card="total" />
        <div className="mt-1 text-lg font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{summary.total.toLocaleString('en-US')}</div>
        <SegmentBar summary={summary} statusCounts={statusCounts} onSelect={onSelect} />
      </div>
      {cards.map(card => {
        const filter = cardStatus(card, statusCounts);
        const active = cardActive(card, status);
        const body = (
          <>
            <CardTitle card={card} />
            <div className="mt-1 text-lg font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{summary[card].toLocaleString('en-US')}</div>
          </>
        );
        const frame = `rounded-xl border px-4 py-3 text-left ${
          active ? 'border-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.03)]' : 'border-[rgba(0,0,0,0.08)] bg-white'
        }`;
        return filter === null
          ? <div key={card} data-card={card} className={frame}>{body}</div>
          : (
            <button
              key={card}
              type="button"
              data-card={card}
              aria-pressed={active}
              onClick={() => onSelect(filter)}
              className={`${frame} transition-colors hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]`}
            >
              {body}
            </button>
          );
      })}
    </div>
  );
};

const CardTitle: React.FC<{ card: RemediationCard }> = ({ card }) => (
  <span className="flex items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
    {CARD_TEXT[card].label}
    <InfoIcon text={CARD_TEXT[card].tooltip} />
  </span>
);

export default RemediationCards;
