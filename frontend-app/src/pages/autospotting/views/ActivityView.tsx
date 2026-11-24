/**
 * Activity view (design-ux-ops.md section 2.2, plan-phase-14 task 14.2, PD-08): the account's audit rows,
 * newest first, with the outcome of each (a dry run is `WOULD_DO`). Reads `autospotting_audit` through the
 * v2 audit endpoint, at most 200 rows.
 */
import React, { useState } from 'react';
import { useAudit } from '../../../hooks/autospotting/useAudit';
import { AutoSpottingApiError } from '../../../api/autospottingApi';
import { RANGE_OPTIONS, lastDays } from './dateRange';

const LIMIT = 100;

const when = (iso: string): string => iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC');

const ActivityView: React.FC<{ accountId: number | null; groupId?: string }> = ({ accountId, groupId }) => {
  const [days, setDays] = useState<number>(30);
  const { from, to } = lastDays(days);
  const q = useAudit(accountId, { groupId, from, to, limit: LIMIT });

  if (accountId === null) {
    return <p className="text-sm text-gray-600">Select an AWS account to see its activity.</p>;
  }

  let body: React.ReactNode;
  if (q.isLoading) {
    body = <div role="status" aria-label="Loading activity" className="h-24 animate-pulse rounded bg-gray-200" />;
  } else if (q.isError || !q.data) {
    const requestId = q.error instanceof AutoSpottingApiError ? q.error.requestId : null;
    body = (
      <div role="alert" className="rounded border border-gray-300 p-6 text-center text-sm text-gray-600">
        <p className="font-medium text-black">Couldn't load activity.</p>
        {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
        <button type="button" onClick={() => void q.refetch()} className="mt-2 rounded border border-black px-3 py-1 text-black">
          Retry
        </button>
      </div>
    );
  } else if (q.data.length === 0) {
    body = <p className="text-sm text-gray-600">No activity in this range.</p>;
  } else {
    body = (
      <>
        {q.data.length >= LIMIT && (
          <p role="status" className="rounded border border-gray-400 bg-gray-100 px-3 py-2 text-sm text-black">
            {`Showing the latest ${LIMIT} entries. Choose a shorter range to see earlier ones.`}
          </p>
        )}
        <div className="overflow-x-auto rounded border border-gray-300">
          <table className="w-full text-left text-sm text-black">
            <thead className="bg-gray-100 text-xs text-gray-600">
              <tr>
                <th className="px-3 py-2">Time</th>
                <th className="px-3 py-2">Action</th>
                <th className="px-3 py-2">Outcome</th>
                <th className="px-3 py-2">Group</th>
                <th className="px-3 py-2">By</th>
              </tr>
            </thead>
            <tbody>
              {q.data.map(e => (
                <tr key={e.id} className="border-t border-gray-200">
                  <td className="whitespace-nowrap px-3 py-2 text-gray-600">{when(e.occurredAt)}</td>
                  <td className="px-3 py-2">{e.action}</td>
                  <td className="px-3 py-2">
                    <span className="rounded border border-gray-400 px-1.5 py-0.5 text-xs">{e.outcome}</span>
                    {e.errorCode && <span className="ml-2 text-xs text-gray-600">{e.errorCode}</span>}
                  </td>
                  <td className="px-3 py-2">{e.groupKey ?? ''}</td>
                  <td className="px-3 py-2 text-gray-600">{e.actorType}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }

  return (
    <section aria-label="Activity" className="space-y-3">
      <div className="flex items-center gap-2 text-sm">
        <span className="text-gray-600">Range</span>
        {RANGE_OPTIONS.map(o => (
          <button
            key={o.days}
            type="button"
            aria-pressed={days === o.days}
            onClick={() => setDays(o.days)}
            className={`rounded border px-3 py-1 ${days === o.days ? 'border-black bg-black text-white' : 'border-gray-400 text-black'}`}
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
