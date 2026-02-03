/**
 * BillOps Admin Credits Page
 *
 * Converted from: billops/admin_credits.html
 *
 * Admin interface for managing all credit requests:
 * - View all credit requests across all accounts
 * - Update request status (submitted, documentation_review, technical_verification, approved, rejected)
 * - Real-time status updates
 * - Background refresh with visual indicator
 *
 * For: ROLE_BILLOPS_ADMIN
 */

import React, { useCallback, useEffect } from 'react';
import useBillOpsAdminCredits from '../hooks/useBillOpsAdminCredits';
import { useTopbar } from '../contexts/TopbarContext';
import type { AdminCreditRequest, AdminCreditStatus } from '../types/billopsAdminCredits';

const STATUS_OPTIONS: { value: AdminCreditStatus; label: string }[] = [
  { value: 'submitted', label: 'Submitted' },
  { value: 'documentation_review', label: 'Documentation Review' },
  { value: 'technical_verification', label: 'Technical Verification' },
  { value: 'approved', label: 'Approved' },
  { value: 'rejected', label: 'Rejected' },
];

const BillOpsAdminCreditsPage: React.FC = () => {
  const {
    requests,
    loading,
    isUpdating,
    error,
    updateError,
    fetchData,
    updateStatus,
  } = useBillOpsAdminCredits();

  const { setActions, clearActions } = useTopbar();

  /**
   * Format currency for display
   */
  const formatCurrency = useCallback((value: number | null | undefined): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(value || 0);
  }, []);

  /**
   * Format date for display
   */
  const formatDate = useCallback((dateString: string): string => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }, []);

  /**
   * Get status color class
   */
  const getStatusColor = useCallback((status: AdminCreditStatus): string => {
    const colorMap: Record<AdminCreditStatus, string> = {
      submitted: 'bg-blue-100 text-blue-700',
      documentation_review: 'bg-cyan-100 text-cyan-700',
      technical_verification: 'bg-amber-100 text-amber-700',
      approved: 'bg-green-100 text-green-700',
      rejected: 'bg-red-100 text-red-700',
    };
    return colorMap[status] || 'bg-gray-100 text-gray-700';
  }, []);

  /**
   * Get priority badge class
   */
  const getPriorityBadge = useCallback((priority: string): string => {
    const priorityMap: Record<string, string> = {
      high: 'bg-red-50 text-red-600 border-red-100',
      medium: 'bg-amber-50 text-amber-600 border-amber-100',
      low: 'bg-green-50 text-green-600 border-green-100',
    };
    return priorityMap[priority] || 'bg-gray-50 text-gray-600 border-gray-100';
  }, []);

  /**
   * Handle status change
   */
  const handleStatusChange = useCallback(
    (id: number, newStatus: AdminCreditStatus) => {
      updateStatus(id, newStatus);
    },
    [updateStatus]
  );

  /**
   * Calculate statistics
   */
  const stats = React.useMemo(() => {
    const total = requests.length;
    const pending = requests.filter(
      (r) => r.status !== 'approved' && r.status !== 'rejected'
    ).length;
    const approved = requests.filter((r) => r.status === 'approved').length;
    const totalCreditsValue = requests.reduce(
      (sum, r) => sum + (r.expectedCredits || 0),
      0
    );
    return { total, pending, approved, totalCreditsValue };
  }, [requests]);

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-4 bg-white/50 p-1.5 rounded-2xl border border-gray-200/50 shadow-sm hidden xl:flex">
          <div className="px-3 py-1 text-center">
            <div className="text-sm font-bold text-indigo-600">{stats.total}</div>
            <div className="text-[9px] text-gray-400 font-bold uppercase tracking-widest">Total Requests</div>
          </div>
          <div className="px-3 py-1 text-center border-l border-gray-200/50">
            <div className="text-sm font-bold text-amber-500">{stats.pending}</div>
            <div className="text-[9px] text-gray-400 font-bold uppercase tracking-widest">In Review</div>
          </div>
          <div className="px-3 py-1 text-center border-l border-gray-200/50">
            <div className="text-sm font-bold text-green-600">{stats.approved}</div>
            <div className="text-[9px] text-gray-400 font-bold uppercase tracking-widest">Approved</div>
          </div>
          <div className="px-4 py-1 text-center border-l border-gray-200/50">
            <div className="text-sm font-bold text-gray-900">{formatCurrency(stats.totalCreditsValue)}</div>
            <div className="text-[9px] text-gray-400 font-bold uppercase tracking-widest">Estimated Value</div>
          </div>
        </div>
        <button
          onClick={() => fetchData(true)}
          disabled={loading || isUpdating}
          className="w-10 h-10 flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:bg-white rounded-xl border border-transparent hover:border-gray-200 transition-all disabled:opacity-50 shadow-sm hover:shadow-md"
          title="Refresh List"
        >
          <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`} />
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, stats, loading, isUpdating, fetchData, formatCurrency]);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Main Content */}
      <main className="flex-1 overflow-x-hidden overflow-y-auto p-8 custom-scrollbar bg-gray-50/30">

        {/* Alerts Section */}
        <div className="space-y-4 mb-8">
          {error && !loading && (
            <div className="bg-red-50 border-l-4 border-red-500 p-4 rounded-xl shadow-sm animate-fade-in">
              <div className="flex items-center gap-3">
                <i className="fas fa-exclamation-circle text-red-500 text-xl" />
                <div>
                  <h4 className="text-sm font-bold text-red-800">Connection Error</h4>
                  <p className="text-xs text-red-700 mt-0.5">{error}</p>
                </div>
              </div>
            </div>
          )}

          {updateError && !loading && (
            <div className="bg-amber-50 border-l-4 border-amber-500 p-4 rounded-xl shadow-sm animate-fade-in">
              <div className="flex items-center gap-3">
                <i className="fas fa-exclamation-triangle text-amber-500 text-xl" />
                <div>
                  <h4 className="text-sm font-bold text-amber-800">Sync Warning</h4>
                  <p className="text-xs text-amber-700 mt-0.5">{updateError} (Showing cached data)</p>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Credit Requests Content */}
        {loading ? (
          <div className="bg-white rounded-3xl border border-gray-200 shadow-sm overflow-hidden">
            <div className="p-12 text-center">
              <div className="w-12 h-12 border-4 border-indigo-600 border-t-transparent rounded-full animate-spin mx-auto mb-4" />
              <p className="text-gray-500 font-medium">Retrieving credit requests...</p>
            </div>
          </div>
        ) : requests.length > 0 ? (
          <div className="bg-white rounded-3xl border border-gray-200 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-100">
                <thead>
                  <tr className="bg-gray-50/50">
                    <th className="px-6 py-4 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Cloud Account</th>
                    <th className="px-6 py-4 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Expected Credits</th>
                    <th className="px-6 py-4 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Use Case</th>
                    <th className="px-6 py-4 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Priority</th>
                    <th className="px-6 py-4 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Submission Date</th>
                    <th className="px-6 py-4 text-center text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Current Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {requests.map((request: AdminCreditRequest) => (
                    <tr key={request.id} className="hover:bg-gray-50/50 transition-colors group">
                      {/* Account ID */}
                      <td className="px-6 py-5 whitespace-nowrap">
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded-lg bg-gray-100 flex items-center justify-center text-xs text-gray-500 font-bold group-hover:bg-indigo-100 group-hover:text-indigo-600 transition-colors">
                            <i className="fas fa-cloud" />
                          </div>
                          <span className="font-mono text-sm font-bold text-gray-700 tracking-tight">{request.awsAccountId}</span>
                        </div>
                      </td>

                      {/* Requested Credits */}
                      <td className="px-6 py-5 whitespace-nowrap">
                        <span className="text-base font-black text-gray-900">{formatCurrency(request.expectedCredits)}</span>
                      </td>

                      {/* Use Case */}
                      <td className="px-6 py-5">
                        <div className="max-w-[240px] truncate text-xs font-semibold text-gray-500" title={request.useCase}>
                          {request.useCase}
                        </div>
                      </td>

                      {/* Priority */}
                      <td className="px-6 py-5 whitespace-nowrap">
                        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider border ${getPriorityBadge(request.priority)}`}>
                          {request.priority}
                        </span>
                      </td>

                      {/* Submitted Date */}
                      <td className="px-6 py-5 whitespace-nowrap text-xs font-bold text-gray-500">
                        {formatDate(request.submittedDate)}
                      </td>

                      {/* Status */}
                      <td className="px-6 py-5 whitespace-nowrap text-center">
                        <div className="relative inline-block text-left">
                          {(() => {
                            const statusBgColor = getStatusColor(request.status);
                            const dropdownIcon = `url('data:image/svg+xml;charset=UTF-8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 12l-6-6h12l-6 6z" clip-rule="evenodd" /></svg>')`;
                            return (
                              <select
                                value={request.status}
                                onChange={(e) => handleStatusChange(request.id, e.target.value as AdminCreditStatus)}
                                className={`pl-4 pr-10 py-2 rounded-xl text-xs font-bold border-none cursor-pointer outline-none transition-all shadow-sm ring-1 ring-gray-100 focus:ring-2 focus:ring-indigo-500 appearance-none bg-no-repeat bg-[right_12px_center] bg-[length:12px_12px] ${statusBgColor}`}
                                style={{ backgroundImage: dropdownIcon }}
                              >
                                {STATUS_OPTIONS.map((option) => (
                                  <option key={option.value} value={option.value} className="bg-white text-gray-900 font-semibold">
                                    {option.label}
                                  </option>
                                ))}
                              </select>
                            );
                          })()}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : (
          <div className="bg-white rounded-3xl border border-gray-200 shadow-sm p-16 text-center animate-fade-in">
            <div className="w-20 h-20 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-6">
              <i className="fas fa-inbox text-gray-300 text-4xl" />
            </div>
            <h3 className="text-xl font-bold text-gray-900 mb-2">Queue is Empty</h3>
            <p className="text-gray-400 max-w-xs mx-auto text-sm font-medium">New credit requests from users will populate here for processing and documentation review.</p>
          </div>
        )}
      </main>
    </div>
  );
};

export default BillOpsAdminCreditsPage;
