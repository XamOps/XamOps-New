import React, { useEffect } from 'react';
import { useAgreements } from '../hooks/useAgreements';
import { useTopbar } from '../contexts/TopbarContext';

const AgreementsPage: React.FC = () => {
  const {
    agreements,
    loading,
    error,
    accountId,
    accountName,
  } = useAgreements();

  const { setActions, clearActions } = useTopbar();

  /**
   * Set Topbar Actions and Stats
   */
  useEffect(() => {
    if (accountId) {
      setActions(
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-4">
            <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden sm:block transition-all hover:shadow-md hover:border-indigo-200">
              <div className="text-sm font-black text-indigo-600 tracking-tight">
                {agreements.length}
              </div>
              <div className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">Total Agreements</div>
            </div>
            {accountName && (
              <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden md:block transition-all hover:shadow-md hover:border-indigo-200">
                <div className="text-sm font-black text-gray-900 tracking-tight truncate max-w-[200px]">
                  {accountName}
                </div>
                <div className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">Selected Account</div>
              </div>
            )}
          </div>
        </div>
      );
    } else {
      setActions(null);
    }
    return () => clearActions();
  }, [setActions, clearActions, agreements.length, accountName, accountId]);

  /**
   * Format date to readable string
   */
  const formatDate = (dateString: string | null): string => {
    if (!dateString) return 'Pending Signature';
    try {
      return new Date(dateString).toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return 'N/A';
    }
  };

  /**
   * Get file icon based on file name
   */
  const getFileIcon = (fileName: string) => {
    const ext = fileName.split('.').pop()?.toLowerCase();
    if (ext === 'pdf') return 'fa-file-pdf text-red-500';
    if (ext === 'doc' || ext === 'docx') return 'fa-file-word text-blue-500';
    return 'fa-file-contract text-indigo-500';
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 custom-scrollbar relative z-0">

        {/* Account Selection Notice */}
        {!accountId && !loading && (
          <div className="max-w-3xl mx-auto mt-20">
            <div className="bg-white border border-indigo-100 rounded-3xl p-12 text-center shadow-xl shadow-indigo-500/5 animate-fade-in">
              <div className="w-20 h-20 bg-indigo-50 rounded-2xl flex items-center justify-center mx-auto mb-6">
                <i className="fas fa-building text-3xl text-indigo-600" />
              </div>
              <h2 className="text-2xl font-black text-gray-900 mb-4">Select an Account</h2>
              <p className="text-gray-500 font-medium leading-relaxed max-w-sm mx-auto">
                Please select a cloud account from the sidebar sidebar to view and manage your billing agreements and contracts.
              </p>
            </div>
          </div>
        )}

        {/* Loading State */}
        {loading && (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-12 h-12 border-4 border-indigo-100 border-t-indigo-600 rounded-full animate-spin mb-4" />
            <p className="text-gray-500 font-bold uppercase tracking-widest text-xs">Loading Agreements...</p>
          </div>
        )}

        {/* Error State */}
        {error && !loading && (
          <div className="bg-red-50 border-l-4 border-red-500 p-6 rounded-2xl mb-8 shadow-sm animate-shake">
            <div className="flex items-center gap-4">
              <div className="w-10 h-10 bg-red-100 rounded-xl flex items-center justify-center text-red-600">
                <i className="fas fa-exclamation-triangle" />
              </div>
              <div>
                <h4 className="font-bold text-red-900">System Error</h4>
                <p className="text-red-700 text-sm font-medium">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Agreements Content */}
        {!loading && accountId && !error && (
          <div className="max-w-[1600px] mx-auto space-y-8 animate-fade-in">
            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
              <div className="px-8 py-6 border-b border-gray-100 bg-gray-50/50 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <h3 className="text-lg font-bold text-gray-900 tracking-tight">Active Agreements</h3>
                  <p className="text-xs text-gray-500 font-medium uppercase tracking-wider mt-0.5">Finalized contracts and billing documents</p>
                </div>
              </div>

              {agreements.length > 0 ? (
                <div className="overflow-x-auto overflow-y-hidden custom-scrollbar">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-gray-50/30 border-b border-gray-100">
                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">Document Name</th>
                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">Date Finalized</th>
                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {agreements.map((agreement) => (
                        <tr key={agreement.id} className="group hover:bg-gray-50/80 transition-all duration-200">
                          <td className="px-8 py-5">
                            <div className="flex items-center gap-4">
                              <div className="w-10 h-10 rounded-xl bg-gray-50 border border-gray-100 flex items-center justify-center text-lg transition-transform group-hover:scale-110">
                                <i className={`fas ${getFileIcon(agreement.fileName)}`} />
                              </div>
                              <div>
                                <div className="text-sm font-bold text-gray-900 group-hover:text-indigo-600 transition-colors line-clamp-1">{agreement.fileName}</div>
                                <div className="text-[10px] text-gray-400 font-bold uppercase tracking-wider mt-0.5">ID: #{agreement.id}</div>
                              </div>
                            </div>
                          </td>
                          <td className="px-8 py-5">
                            <div className="flex items-center gap-2 text-sm text-gray-600 font-medium">
                              <i className="far fa-calendar-check text-green-500" />
                              <span>{formatDate(agreement.finalizedAt)}</span>
                            </div>
                          </td>
                          <td className="px-8 py-5 text-right">
                            <a
                              href={agreement.downloadUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-50 text-indigo-600 rounded-xl text-xs font-bold hover:bg-indigo-600 hover:text-white transition-all active:scale-95 group/btn border border-indigo-100"
                            >
                              <i className="fas fa-download transition-transform group-hover/btn:-translate-y-0.5" />
                              <span>Download PDF</span>
                            </a>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="py-20 text-center">
                  <div className="w-16 h-16 bg-gray-50 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-gray-100">
                    <i className="fas fa-file-signature text-2xl text-gray-300" />
                  </div>
                  <h4 className="text-lg font-bold text-gray-900">No active contracts found</h4>
                  <p className="text-sm text-gray-500 font-medium max-w-sm mx-auto mt-2 leading-relaxed">
                    There are no finalized billing agreements available for this account at this time.
                  </p>
                </div>
              )}
            </div>
          </div>
        )}
      </main>
    </div>
  );
};

export default AgreementsPage;
