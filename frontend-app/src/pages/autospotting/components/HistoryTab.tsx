import React from 'react';
import type { GroupDetail } from '../../../api/autospottingApi';
import { MUTED, TABLE, TD_SM, TH_SM, THEAD_ROW, TR } from './theme';

export const HistoryTab: React.FC<{ detail: GroupDetail }> = ({ detail }) => {
  if (detail.transitions.length === 0) {
    return <p className={`text-[13px] ${MUTED}`}>No state changes recorded for this group yet.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-[rgba(0,0,0,0.08)]">
    <table className={TABLE}>
      <caption className="sr-only">State history</caption>
      <thead>
        <tr className={THEAD_ROW}>
          <th scope="col" className={TH_SM}>When</th>
          <th scope="col" className={TH_SM}>From</th>
          <th scope="col" className={TH_SM}>To</th>
          <th scope="col" className={TH_SM}>Reason</th>
        </tr>
      </thead>
      <tbody>
        {detail.transitions.map((t, i) => (
          <tr key={i} className={TR}>
            <td className={`${TD_SM} whitespace-nowrap ${MUTED}`}>{t.occurredAt}</td>
            <td className={TD_SM}>{t.fromState ?? '--'}</td>
            <td className={TD_SM}>{t.toState}</td>
            <td className={TD_SM}>{t.reasonCode ?? '--'}</td>
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  );
};

export default HistoryTab;
