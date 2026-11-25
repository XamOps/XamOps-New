/** Pieces the plan, revert and batch dialogs share, in the app's table-card and notice styles. */
import React from 'react';
import type { ConfirmOutcome } from './reasons';
import { diffFieldLabel, diffValue } from './diffText';
import { BTN_SECONDARY, MUTED, Notice, TABLE, TD_SM, TH_SM, THEAD_ROW, TR, type Tone } from './theme';

export const BuildingPlan: React.FC<{ text?: string }> = ({ text = 'Building the plan' }) => (
  <p role="status" className={`mb-4 flex items-center gap-2 text-[13px] ${MUTED}`}>
    <span aria-hidden="true" className="h-2 w-2 animate-pulse rounded-full bg-indigo-500" />
    {text}
  </p>
);

export const DiffTable: React.FC<{
  caption: string;
  targetHeader: string;
  diff: Array<{ field: string; from: string | null; to: string | null }>;
  className?: string;
}> = ({ caption, targetHeader, diff, className = '' }) => (
  <div className={`overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)] ${className}`}>
    <table className={TABLE}>
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr className={THEAD_ROW}>
          <th scope="col" className={TH_SM}>
            Field
          </th>
          <th scope="col" className={TH_SM}>
            Current
          </th>
          <th scope="col" className={TH_SM}>
            {targetHeader}
          </th>
        </tr>
      </thead>
      <tbody>
        {diff.map(d => (
          <tr key={d.field} className={TR}>
            <td className={`${TD_SM} font-medium`}>{diffFieldLabel(d.field)}</td>
            <td className={`${TD_SM} ${MUTED}`}>{diffValue(d.field, d.from, 'from')}</td>
            <td className={`${TD_SM} font-medium text-indigo-700`}>{diffValue(d.field, d.to, 'to')}</td>
          </tr>
        ))}
      </tbody>
    </table>
  </div>
);

const OUTCOME_TONE: Record<ConfirmOutcome['kind'], Tone> = {
  stale: 'warning',
  ack: 'warning',
  busy: 'info',
  forbidden: 'danger',
  message: 'danger',
};

/** A confirm that did not start (design-ux-ops.md §3.3). */
export const Outcome: React.FC<{ outcome: ConfirmOutcome; onViewProgress?: () => void }> = ({ outcome, onViewProgress }) => (
  <Notice tone={OUTCOME_TONE[outcome.kind]} role="alert" className="mb-4">
    {outcome.kind === 'stale' && <p className="font-bold">{outcome.title}</p>}
    <p>{outcome.message}</p>
    {outcome.kind === 'busy' && onViewProgress && (
      <button type="button" onClick={onViewProgress} className={`mt-2 ${BTN_SECONDARY}`}>
        Open Progress
      </button>
    )}
  </Notice>
);
