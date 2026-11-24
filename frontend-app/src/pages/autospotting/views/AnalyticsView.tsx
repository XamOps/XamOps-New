/**
 * Analytics view (design-ux-ops.md section 2.2, plan-phase-14 task 14.2, PD-08): Spot launches, failures
 * with their classified cause, interruptions, rebalance recommendations and success rate, per group and
 * instance type. A metric with no source for the account reads "Not tracked", never 0.
 */
import React, { useState } from 'react';
import { useAnalytics } from '../../../hooks/autospotting/useAnalytics';
import type { AnalyticsFigures } from '../../../api/autospottingApi';
import { AutoSpottingApiError } from '../../../api/autospottingApi';
import { RANGE_OPTIONS, lastDays } from './dateRange';

const NOT_TRACKED = 'Not tracked';

const METRIC_LABELS: Record<string, string> = {
  spotLaunches: 'Spot launches',
  launchFailures: 'Launch failures',
  interruptions: 'Interruptions',
  rebalanceRecommendations: 'Rebalance recommendations',
  successRate: 'Success rate',
};

function count(v: number | null | undefined): string {
  return v === null || v === undefined ? NOT_TRACKED : String(v);
}

function rate(f: AnalyticsFigures): string {
  if (f.successRate === null || f.successRate === undefined) return NOT_TRACKED;
  return `${f.successRate}%`;
}

function causes(f: AnalyticsFigures): string {
  const entries = Object.entries(f.failuresByCause ?? {});
  return entries.length === 0 ? '' : entries.map(([k, v]) => `${k} ${v}`).join(', ');
}

const Row: React.FC<{ name: string; sub?: string; f: AnalyticsFigures; indent?: boolean; rateTracked: boolean }> = ({
  name,
  sub,
  f,
  indent,
  rateTracked,
}) => (
  <tr className="border-t border-gray-200">
    <td className={`px-3 py-2 ${indent ? 'pl-8 text-gray-600' : 'font-medium text-black'}`}>
      {name}
      {sub && <span className="ml-2 text-xs font-normal text-gray-600">{sub}</span>}
    </td>
    <td className="px-3 py-2 tabular-nums">{count(f.spotLaunches)}</td>
    <td className="px-3 py-2 tabular-nums">
      {count(f.launchFailures)}
      {causes(f) && <span className="ml-2 text-xs text-gray-600">{causes(f)}</span>}
    </td>
    <td className="px-3 py-2 tabular-nums">{count(f.interruptions)}</td>
    <td className="px-3 py-2 tabular-nums">{count(f.rebalanceRecommendations)}</td>
    <td className="px-3 py-2 tabular-nums">{rateTracked ? (f.successRate === null ? 'No launches' : rate(f)) : NOT_TRACKED}</td>
  </tr>
);

const AnalyticsView: React.FC<{ accountId: number | null }> = ({ accountId }) => {
  const [days, setDays] = useState<number>(30);
  const { from, to } = lastDays(days);
  const q = useAnalytics(accountId, from, to);

  if (accountId === null) {
    return <p className="text-sm text-gray-600">Select an AWS account to see its analytics.</p>;
  }

  let body: React.ReactNode;
  if (q.isLoading) {
    body = <div role="status" aria-label="Loading analytics" className="h-24 animate-pulse rounded bg-gray-200" />;
  } else if (q.isError || !q.data) {
    const requestId = q.error instanceof AutoSpottingApiError ? q.error.requestId : null;
    body = (
      <div role="alert" className="rounded border border-gray-300 p-6 text-center text-sm text-gray-600">
        <p className="font-medium text-black">Couldn't load analytics.</p>
        {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
        <button type="button" onClick={() => void q.refetch()} className="mt-2 rounded border border-black px-3 py-1 text-black">
          Retry
        </button>
      </div>
    );
  } else {
    const data = q.data;
    const missing = data.notTracked;
    const allMissing = missing.length >= Object.keys(METRIC_LABELS).length;
    const rateTracked = !missing.includes('successRate');
    body = (
      <>
        {missing.length > 0 && (
          <p role="status" className="rounded border border-gray-400 bg-gray-100 px-3 py-2 text-sm text-black">
            {allMissing
              ? 'No Spot events are recorded for this account yet, so none of these figures is tracked.'
              : `Not tracked for this account: ${missing.map(m => METRIC_LABELS[m] ?? m).join(', ')}.`}
          </p>
        )}
        <div className="overflow-x-auto rounded border border-gray-300">
          <table className="w-full text-left text-sm text-black">
            <thead className="bg-gray-100 text-xs text-gray-600">
              <tr>
                <th className="px-3 py-2">Group</th>
                <th className="px-3 py-2">Spot launches</th>
                <th className="px-3 py-2">Launch failures</th>
                <th className="px-3 py-2">Interruptions</th>
                <th className="px-3 py-2">Rebalance</th>
                <th className="px-3 py-2">Success rate</th>
              </tr>
            </thead>
            <tbody>
              <Row name="All groups" f={data.totals} rateTracked={rateTracked} />
              {data.groups.map(g => (
                <React.Fragment key={g.groupId}>
                  <Row name={g.name} sub={g.location ?? undefined} f={g.figures} rateTracked={rateTracked} />
                  {g.byType.map(t => (
                    <Row key={`${g.groupId}-${t.instanceType}`} name={t.instanceType} f={t.figures} indent rateTracked={rateTracked} />
                  ))}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        </div>
        {!allMissing && data.groups.length === 0 && (
          <p className="text-sm text-gray-600">No Spot events in this range.</p>
        )}
      </>
    );
  }

  return (
    <section aria-label="Analytics" className="space-y-3">
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

export default AnalyticsView;
