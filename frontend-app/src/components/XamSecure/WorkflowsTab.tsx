import React, { useMemo } from 'react';
import type { Workflow } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';

interface Props {
  data: Workflow[] | null;
  isLoading: boolean;
}

/** Response automation workflows — mirrors Upwind's automation rules. */
const WorkflowsTab: React.FC<Props> = ({ data, isLoading }) => {
  const all = data ?? [];
  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(all, 50);

  const totals = useMemo(() => ({
    workflows: all.length,
    enabled: all.filter((w) => (w.status ?? '').toUpperCase() === 'ENABLED').length,
    scheduled: all.filter((w) => (w.type ?? '').toUpperCase() === 'SCHEDULED').length,
  }), [all]);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading workflows...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Stat value={totals.workflows} label="Total workflows" />
        <Stat value={totals.enabled} label="Enabled" accent="text-emerald-600" />
        <Stat value={totals.scheduled} label="Scheduled" />
      </div>

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Workflow</th>
                <th className="px-5 py-2.5">Trigger</th>
                <th className="px-5 py-2.5">Schedule</th>
                <th className="px-5 py-2.5">Actions</th>
                <th className="px-5 py-2.5">Last run</th>
                <th className="px-5 py-2.5">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((w) => (
                <tr key={w.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{w.name ?? '—'}</td>
                  <td className="px-5 py-3">
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-indigo-100 text-indigo-700">
                      {w.type ?? '—'}
                    </span>
                  </td>
                  <td className="px-5 py-3 font-mono text-gray-500">{w.cron || '—'}</td>
                  <td className="px-5 py-3 text-gray-600">{w.actions.length ? w.actions.join(', ') : '—'}</td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                    {w.lastExecutionTime ? new Date(w.lastExecutionTime).toLocaleString() : '—'}
                  </td>
                  <td className="px-5 py-3">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                      (w.status ?? '').toUpperCase() === 'ENABLED' ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
                      {w.status ?? '—'}
                    </span>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  No workflows for the selected account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="workflows" onPage={setPage} />
      </div>
    </div>
  );
};

const Stat: React.FC<{ value: number; label: string; accent?: string }> = ({ value, label, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className={`text-2xl font-bold ${accent ?? 'text-gray-900'}`}>{value.toLocaleString()}</p>
    <p className="text-xs text-gray-500 mt-1">{label}</p>
  </div>
);

export default WorkflowsTab;
