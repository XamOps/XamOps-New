/**
 * Activity view (design-ux-ops.md section 2.2, plan-phase-14 task 14.2, PD-08): the account's audit rows,
 * newest first, with the outcome of each (a dry run is `WOULD_DO`). Reads `autospotting_audit` through the
 * v2 audit endpoint, at most 200 rows.
 */
import React, { useState } from 'react';
import { useAudit } from '../../../hooks/autospotting/useAudit';
import { AutoSpottingApiError } from '../../../api/autospottingApi';
import { RANGE_OPTIONS, lastDays } from './dateRange';
import { BTN_PRIMARY, CARD, CARD_TITLE, EmptyState, MUTED, Notice, TABLE, TD, TH, THEAD_ROW, TR, rangeButtonClass } from '../components/theme';

const LIMIT = 100;

/** Outcome chip tint; the outcome text is always shown. */
function outcomeClass(outcome: string): string {
  const o = outcome.toUpperCase();
  if (o === 'SUCCESS' || o === 'SUCCEEDED' || o === 'OK') return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (o.includes('FAIL') || o.includes('ERROR') || o === 'DENIED' || o === 'REJECTED') return 'border-red-200 bg-red-50 text-red-700';
  if (o === 'WOULD_DO') return 'border-amber-200 bg-amber-50 text-amber-800';
  return 'border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.65)]';
}
const when = (iso: string): string => iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC');

const ActivityView: React.FC<{ accountId: number | null; groupId?: string }> = ({ accountId, groupId }) => {
  const [days, setDays] = useState<number>(30);
  const { from, to } = lastDays(days);
  const q = useAudit(accountId, { groupId, from, to, limit: LIMIT });

  if (accountId === null) {
    return <EmptyState icon="mdi:account-search-outline" title="Select an AWS account to see its activity." />;
  }

  let body: React.ReactNode;
  if (q.isLoading) {
    body = (
      <div role="status" aria-label="Loading activity" className={`${CARD} space-y-3 p-5`}>
        {[0, 1, 2].map(i => (
          <div key={i} className="h-4 animate-pulse rounded bg-[rgba(0,0,0,0.06)]" />
        ))}
      </div>
    );
  } else if (q.isError || !q.data) {
    const requestId = q.error instanceof AutoSpottingApiError ? q.error.requestId : null;
    body = (
      <Notice tone="danger" role="alert" className="py-4">
        <p className="font-semibold">Couldn't load activity.</p>
        {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
        <button type="button" onClick={() => void q.refetch()} className={`mt-3 ${BTN_PRIMARY}`}>
          Retry
        </button>
      </Notice>
    );
  } else if (q.data.length === 0) {
    body = <EmptyState icon="mdi:history" title="No activity in this range." />;
  } else {
    body = (
      <>
        {q.data.length >= LIMIT && (
          <Notice tone="warning" role="status">
            {`Showing the latest ${LIMIT} entries. Choose a shorter range to see earlier ones.`}
          </Notice>
        )}
        <div className={`${CARD} overflow-x-auto`}>
          <table className={TABLE}>
            <thead>
              <tr className={THEAD_ROW}>
                <th scope="col" className={TH}>Time</th>
                <th scope="col" className={TH}>Action</th>
                <th scope="col" className={TH}>Outcome</th>
                <th scope="col" className={TH}>Group</th>
                <th scope="col" className={TH}>By</th>
              </tr>
            </thead>
            <tbody>
              {q.data.map(e => (
                <tr key={e.id} className={TR}>
                  <td className={`${TD} whitespace-nowrap ${MUTED}`}>{when(e.occurredAt)}</td>
                  <td className={`${TD} font-medium`}>{e.action}</td>
                  <td className={TD}>
                    <span className={`rounded border px-1.5 py-0.5 text-[11px] font-semibold ${outcomeClass(e.outcome)}`}>{e.outcome}</span>
                    {e.errorCode && <span className={`ml-2 text-[11px] ${MUTED}`}>{e.errorCode}</span>}
                  </td>
                  <td className={`${TD} font-mono text-[12px]`}>{e.groupKey ?? ''}</td>
                  <td className={`${TD} ${MUTED}`}>{e.actorType}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }

  return (
    <section aria-label="Activity" className="space-y-4">
      <div className={`${CARD} flex items-center gap-2 px-4 py-3`}>
        <span className={`mr-1 ${CARD_TITLE}`}>Range</span>
        {RANGE_OPTIONS.map(o => (
          <button
            key={o.days}
            type="button"
            aria-pressed={days === o.days}
            onClick={() => setDays(o.days)}
            className={rangeButtonClass(days === o.days)}
          >
            {o.label}
          </button>
        ))}
      </div>
      {body}
    </section>
  );
};

export default ActivityView;
