import React from 'react';
import type { GroupDetail } from '../../../api/autospottingApi';

export const HistoryTab: React.FC<{ detail: GroupDetail }> = ({ detail }) => {
  if (detail.transitions.length === 0) {
    return <p className="text-sm text-gray-600">No state changes recorded for this group yet.</p>;
  }
  return (
    <table className="w-full text-left text-sm text-black">
      <caption className="sr-only">State history</caption>
      <thead>
        <tr className="border-b border-gray-300 text-gray-600">
          <th scope="col" className="py-1 pr-2 font-medium">When</th>
          <th scope="col" className="py-1 pr-2 font-medium">From</th>
          <th scope="col" className="py-1 pr-2 font-medium">To</th>
          <th scope="col" className="py-1 font-medium">Reason</th>
        </tr>
      </thead>
      <tbody>
        {detail.transitions.map((t, i) => (
          <tr key={i} className="border-b border-gray-100">
            <td className="py-1 pr-2">{t.occurredAt}</td>
            <td className="py-1 pr-2">{t.fromState ?? '--'}</td>
            <td className="py-1 pr-2">{t.toState}</td>
            <td className="py-1">{t.reasonCode ?? '--'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
};

export default HistoryTab;
