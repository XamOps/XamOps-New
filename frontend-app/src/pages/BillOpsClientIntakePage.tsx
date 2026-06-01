import React, { useEffect, useState } from 'react';
import { apiFetch } from '../utils/apiClient';
import { useTopbar } from '../contexts/TopbarContext';

interface ClientIntakeSubmission {
  id: number;
  name: string;
  companyName: string;
  email: string;
  phone?: string;
  address?: string;
  notes?: string;
  accountIds: string[];
  submittedAt: string;
  status: string;
}

const BillOpsClientIntakePage: React.FC = () => {
  const { setActions, clearActions } = useTopbar();
  const [submissions, setSubmissions] = useState<ClientIntakeSubmission[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchSubmissions = () => {
    setLoading(true);
    setError(null);
    apiFetch('/api/billops/admin/client-intake/all', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then((d: ClientIntakeSubmission[]) => setSubmissions(d))
      .catch(() => setError('Failed to load intake submissions.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchSubmissions();
  }, []);

  useEffect(() => {
    setActions(
      <button
        onClick={fetchSubmissions}
        className="flex items-center gap-2 px-3 py-1.5 bg-indigo-600 text-white rounded-md text-xs font-semibold hover:bg-indigo-700 transition-colors"
      >
        <i className="fas fa-sync-alt text-[10px]" />
        Refresh
      </button>
    );
    return () => clearActions();
  }, [setActions, clearActions]);

  const downloadForm = (s: ClientIntakeSubmission) => {
    const lines = [
      '═══════════════════════════════════════',
      '        CLIENT INTAKE FORM             ',
      '═══════════════════════════════════════',
      `Submitted At  : ${new Date(s.submittedAt).toLocaleString()}`,
      `Status        : ${s.status}`,
      '───────────────────────────────────────',
      `Name          : ${s.name}`,
      `Company       : ${s.companyName}`,
      `Email         : ${s.email}`,
      `Phone         : ${s.phone || '—'}`,
      `Address       : ${s.address || '—'}`,
      '───────────────────────────────────────',
      `Account IDs   : ${s.accountIds?.length ? s.accountIds.join(', ') : '—'}`,
      '───────────────────────────────────────',
      `Notes         : ${s.notes || '—'}`,
      '═══════════════════════════════════════',
    ];
    const blob = new Blob([lines.join('\n')], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `intake_${s.companyName.replace(/\s+/g, '_')}_${s.id}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="p-5 space-y-4">
      {/* Header */}
      <div>
        <h1 className="text-lg font-bold text-gray-900 dark:text-white tracking-tight">Client Intake</h1>
        <p className="text-xs text-gray-400 mt-0.5">Onboarding forms submitted by new XamOps users on first login</p>
      </div>

      {/* Card */}
      <div className="bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl shadow-sm">
        {error && (
          <div className="flex items-center gap-2 p-4 border-b border-gray-100 dark:border-gray-700">
            <i className="fas fa-exclamation-circle text-red-500 text-sm" />
            <span className="text-red-600 dark:text-red-400 text-sm">{error}</span>
          </div>
        )}

        {loading ? (
          <div className="p-6 space-y-3">
            {[1, 2, 3, 4].map(i => (
              <div key={i} className="h-14 rounded-lg bg-gray-100 dark:bg-gray-700 animate-pulse" />
            ))}
          </div>
        ) : submissions.length === 0 ? (
          <div className="py-20 flex flex-col items-center text-center text-gray-400">
            <i className="fas fa-clipboard text-4xl mb-3 text-gray-200 dark:text-gray-600" />
            <p className="text-sm font-medium text-gray-500 dark:text-gray-400">No intake forms yet</p>
            <p className="text-xs mt-1 text-gray-400">Submissions will appear here once new users complete onboarding</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/60">
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Client</th>
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Company</th>
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Contact</th>
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Account IDs</th>
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Submitted</th>
                  <th className="px-5 py-3 text-left text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50 dark:divide-gray-700/50">
                {submissions.map(s => (
                  <tr key={s.id} className="hover:bg-gray-50 dark:hover:bg-gray-700/30 transition-colors">
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2.5">
                        <div className="w-8 h-8 bg-indigo-100 dark:bg-indigo-900/40 rounded-full flex items-center justify-center flex-shrink-0">
                          <span className="text-indigo-600 dark:text-indigo-400 text-[11px] font-bold uppercase">
                            {s.name?.charAt(0) || '?'}
                          </span>
                        </div>
                        <span className="font-medium text-gray-900 dark:text-white">{s.name}</span>
                      </div>
                    </td>
                    <td className="px-5 py-4">
                      <span className="font-medium text-gray-700 dark:text-gray-300">{s.companyName}</span>
                    </td>
                    <td className="px-5 py-4">
                      <div className="space-y-0.5">
                        {s.email && (
                          <div className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
                            <i className="fas fa-envelope text-[9px] text-gray-400" />
                            {s.email}
                          </div>
                        )}
                        {s.phone && (
                          <div className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
                            <i className="fas fa-phone text-[9px] text-gray-400" />
                            {s.phone}
                          </div>
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-4">
                      {s.accountIds?.length > 0 ? (
                        <div className="flex flex-wrap gap-1">
                          {s.accountIds.slice(0, 3).map((id, i) => (
                            <span key={i} className="inline-flex items-center px-2 py-0.5 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 text-[10px] font-mono rounded-md">
                              {id}
                            </span>
                          ))}
                          {s.accountIds.length > 3 && (
                            <span className="text-[10px] text-gray-400 self-center">+{s.accountIds.length - 3} more</span>
                          )}
                        </div>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600 text-xs">—</span>
                      )}
                    </td>
                    <td className="px-5 py-4 whitespace-nowrap">
                      <div className="text-xs text-gray-500 dark:text-gray-400">
                        {new Date(s.submittedAt).toLocaleDateString('en-US', {
                          month: 'short', day: 'numeric', year: 'numeric',
                        })}
                      </div>
                      <div className="text-[10px] text-gray-400 mt-0.5">
                        {new Date(s.submittedAt).toLocaleTimeString('en-US', {
                          hour: '2-digit', minute: '2-digit',
                        })}
                      </div>
                    </td>
                    <td className="px-5 py-4">
                      <button
                        onClick={() => downloadForm(s)}
                        title="Download as text file"
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/20 border border-indigo-200 dark:border-indigo-700 rounded-md transition-colors"
                      >
                        <i className="fas fa-download text-[9px]" />
                        Download
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

export default BillOpsClientIntakePage;
