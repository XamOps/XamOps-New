import React from 'react';
import type { ApiEndpoint } from '../../types/xamsecure';

interface Props {
  data: ApiEndpoint[] | null;
  isLoading: boolean;
}

const ApiSecurityTab: React.FC<Props> = ({ data, isLoading }) => {
  const rows = data ?? [];
  if (isLoading && rows.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading API endpoints...</div>;
  }
  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Method</th>
              <th className="px-5 py-2.5">Endpoint</th>
              <th className="px-5 py-2.5">Internet Exposed</th>
              <th className="px-5 py-2.5">First Seen</th>
              <th className="px-5 py-2.5">Last Seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id} className="border-b border-gray-50 hover:bg-gray-50">
                <td className="px-5 py-3 font-mono font-bold text-gray-700">{e.method}</td>
                <td className="px-5 py-3 font-mono text-gray-800 max-w-md truncate">{e.uri}</td>
                <td className="px-5 py-3">
                  <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                    e.internetExposed ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-500'}`}>
                    {e.internetExposed ? 'Exposed' : 'Internal'}
                  </span>
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {e.firstSeenTime ? new Date(e.firstSeenTime).toLocaleDateString() : '—'}
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {e.lastSeenTime ? new Date(e.lastSeenTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="px-5 py-12 text-center text-gray-400">
                No API endpoints discovered for the selected account.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default ApiSecurityTab;
