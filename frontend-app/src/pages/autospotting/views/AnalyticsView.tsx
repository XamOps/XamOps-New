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
import { BTN_PRIMARY, CARD, CARD_TITLE, EmptyState, MUTED, Notice, TABLE, TD, TH, THEAD_ROW, TR, rangeButtonClass } from '../components/theme';

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
  <tr className={TR}>
    <td className={`${TD} ${indent ? `pl-10 ${MUTED}` : 'font-semibold'}`}>
      {name}
      {sub && <span className={`ml-2 text-[11px] font-normal ${MUTED}`}>{sub}</span>}
    </td>
    <td className={`${TD} tabular-nums`}>{count(f.spotLaunches)}</td>
    <td className={`${TD} tabular-nums`}>
      {count(f.launchFailures)}
      {causes(f) && <span className={`ml-2 text-[11px] ${MUTED}`}>{causes(f)}</span>}
    </td>
    <td className={`${TD} tabular-nums`}>{count(f.interruptions)}</td>
    <td className={`${TD} tabular-nums`}>{count(f.rebalanceRecommendations)}</td>
    <td className={`${TD} tabular-nums`}>{rateTracked ? (f.successRate === null ? 'No launches' : rate(f)) : NOT_TRACKED}</td>
  </tr>
);

const AnalyticsView: React.FC<{ accountId: number | null }> = ({ accountId }) => {
  const [days, setDays] = useState<number>(30);
  const { from, to } = lastDays(days);
  const q = useAnalytics(accountId, from, to);

  if (accountId === null) {
    return <EmptyState icon="mdi:account-search-outline" title="Select an AWS account to see its analytics." />;
  }

  let body: React.ReactNode;
  if (q.isLoading) {
    body = (
      <div role="status" aria-label="Loading analytics" className={`${CARD} space-y-3 p-5`}>
        {[0, 1, 2].map(i => (
          <div key={i} className="h-4 animate-pulse rounded bg-[rgba(0,0,0,0.06)]" />
        ))}
      </div>
    );
  } else if (q.isError || !q.data) {
    const requestId = q.error instanceof AutoSpottingApiError ? q.error.requestId : null;
    body = (
      <Notice tone="danger" role="alert" className="py-4">
        <p className="font-semibold">Couldn't load analytics.</p>
        {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
        <button type="button" onClick={() => void q.refetch()} className={`mt-3 ${BTN_PRIMARY}`}>
          Retry
        </button>
      </Notice>
    );
  } else {
    const data = q.data;
    const missing = data.notTracked;
    const allMissing = missing.length >= Object.keys(METRIC_LABELS).length;
    const rateTracked = !missing.includes('successRate');
    body = (
      <>
        {missing.length > 0 && (
          <Notice tone="warning" role="status">
            {allMissing
              ? 'No Spot events are recorded for this account yet, so none of these figures is tracked.'
              : `Not tracked for this account: ${missing.map(m => METRIC_LABELS[m] ?? m).join(', ')}.`}
          </Notice>
        )}
        <div className={`${CARD} overflow-x-auto`}>
          <table className={TABLE}>
            <thead>
              <tr className={THEAD_ROW}>
                <th scope="col" className={TH}>Group</th>
                <th scope="col" className={TH}>Spot launches</th>
                <th scope="col" className={TH}>Launch failures</th>
                <th scope="col" className={TH}>Interruptions</th>
                <th scope="col" className={TH}>Rebalance</th>
                <th scope="col" className={TH}>Success rate</th>
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
          <EmptyState icon="mdi:chart-line" title="No Spot events in this range." />
        )}
      </>
    );
  }

  return (
    <section aria-label="Analytics" className="space-y-4">
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

export default AnalyticsView;
