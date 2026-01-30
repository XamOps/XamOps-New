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

import React, { useCallback } from 'react';
import useBillOpsAdminCredits from '../hooks/useBillOpsAdminCredits';
import type { AdminCreditRequest, AdminCreditStatus } from '../types/billopsAdminCredits';
import '../styles/billopsAdminCredits.css';

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

  /**
   * Format currency for display
   */
  const formatCurrency = useCallback((value: number | null | undefined): string => {
    if (value === null || value === undefined) {
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
      }).format(0);
    }
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  }, []);

  /**
   * Format date for display
   */
  const formatDate = useCallback((dateString: string): string => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  }, []);

  /**
   * Get status color class
   */
  const getStatusColor = useCallback((status: AdminCreditStatus): string => {
    const colorMap: Record<AdminCreditStatus, string> = {
      submitted: 'admin-credits-status-submitted',
      documentation_review: 'admin-credits-status-documentation',
      technical_verification: 'admin-credits-status-verification',
      approved: 'admin-credits-status-approved',
      rejected: 'admin-credits-status-rejected',
    };
    return colorMap[status] || 'admin-credits-status-default';
  }, []);

  /**
   * Get priority badge class
   */
  const getPriorityBadge = useCallback((priority: string): string => {
    const priorityMap: Record<string, string> = {
      high: 'admin-credits-priority-high',
      medium: 'admin-credits-priority-medium',
      low: 'admin-credits-priority-low',
    };
    return priorityMap[priority] || 'admin-credits-priority-medium';
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
    const totalCredits = requests.reduce(
      (sum, r) => sum + (r.expectedCredits || 0),
      0
    );
    return { total, pending, approved, totalCredits };
  }, [requests]);

  return (
    <div className="admin-credits-page flex-1 flex flex-col h-screen overflow-hidden">
      {/* Header */}
      <header className="admin-credits-header px-8 py-6 border-b border-gray-200 bg-white sticky top-0 z-10">
        <div className="flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4">
          <div className="flex items-center gap-4">
            <div className="admin-credits-icon-wrapper">
              <i className="fas fa-coins text-white text-xl" />
            </div>
            <div>
              <h1 className="text-2xl lg:text-3xl font-bold text-gray-800 flex items-center gap-3">
                Credit Request Administration
                {isUpdating && !loading && (
                  <span
                    className="admin-credits-updating-spinner"
                    title="Updating data..."
                  />
                )}
              </h1>
              <p className="text-gray-500 text-sm mt-1">
                Manage and process credit applications
              </p>
            </div>
          </div>

          {/* Stats & Actions */}
          <div className="flex items-center gap-6 flex-wrap">
            {/* Stats Cards */}
            <div className="flex items-center gap-4">
              <div className="admin-credits-stat-card">
                <div className="admin-credits-stat-value text-indigo-600">
                  {stats.total}
                </div>
                <div className="admin-credits-stat-label">Total Requests</div>
              </div>
              <div className="admin-credits-stat-card">
                <div className="admin-credits-stat-value text-orange-500">
                  {stats.pending}
                </div>
                <div className="admin-credits-stat-label">Pending</div>
              </div>
              <div className="admin-credits-stat-card">
                <div className="admin-credits-stat-value text-green-600">
                  {stats.approved}
                </div>
                <div className="admin-credits-stat-label">Approved</div>
              </div>
              <div className="admin-credits-stat-card">
                <div className="admin-credits-stat-value text-cyan-600">
                  {formatCurrency(stats.totalCredits)}
                </div>
                <div className="admin-credits-stat-label">Total Value</div>
              </div>
            </div>

            {/* Refresh Button */}
            <button
              onClick={() => fetchData(true)}
              disabled={loading || isUpdating}
              className={`admin-credits-refresh-btn ${isUpdating ? 'spinning' : ''}`}
              title="Refresh List"
            >
              <i className="fas fa-sync-alt" />
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 bg-gray-50 admin-credits-scrollbar">
        {/* Error Alert */}
        {error && !loading && (
          <div className="admin-credits-alert admin-credits-alert-error mb-6">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500 text-xl" />
              <div>
                <strong className="font-bold">Error: </strong>
                <span>{error}</span>
              </div>
            </div>
          </div>
        )}

        {/* Update Warning Alert */}
        {updateError && !loading && (
          <div className="admin-credits-alert admin-credits-alert-warning mb-6">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-triangle text-yellow-500 text-xl" />
              <div>
                <strong className="font-bold">Update Warning: </strong>
                <span>{updateError} (showing cached data)</span>
              </div>
            </div>
          </div>
        )}

        {/* Loading State */}
        {loading && (
          <div className="admin-credits-loading">
            <i className="fas fa-spinner fa-spin text-4xl text-indigo-500" />
            <p className="mt-4 text-gray-600">Loading credit requests...</p>
          </div>
        )}

        {/* Credit Requests Table */}
        {!loading && requests.length > 0 && (
          <div className="admin-credits-table-container">
            <table className="admin-credits-table">
              <thead>
                <tr>
                  <th className="text-left">Account ID</th>
                  <th className="text-left">Requested Credits</th>
                  <th className="text-left">Use Case</th>
                  <th className="text-left">Priority</th>
                  <th className="text-left">Submitted</th>
                  <th className="text-center">Status</th>
                </tr>
              </thead>
              <tbody>
                {requests.map((request: AdminCreditRequest) => (
                  <tr key={request.id} className="admin-credits-table-row">
                    {/* Account ID */}
                    <td>
                      <div className="admin-credits-account-id">
                        {request.awsAccountId}
                      </div>
                    </td>

                    {/* Requested Credits */}
                    <td>
                      <div className="admin-credits-amount">
                        {formatCurrency(request.expectedCredits)}
                      </div>
                    </td>

                    {/* Use Case */}
                    <td>
                      <div
                        className="admin-credits-usecase"
                        title={request.useCase}
                      >
                        {request.useCase}
                      </div>
                    </td>

                    {/* Priority */}
                    <td>
                      <span
                        className={`admin-credits-priority-badge ${getPriorityBadge(
                          request.priority
                        )}`}
                      >
                        {request.priority?.toUpperCase() || 'MEDIUM'}
                      </span>
                    </td>

                    {/* Submitted Date */}
                    <td>
                      <div className="admin-credits-date">
                        <i className="far fa-calendar-alt mr-2 text-gray-400" />
                        {formatDate(request.submittedDate)}
                      </div>
                    </td>

                    {/* Status */}
                    <td className="text-center">
                      <select
                        value={request.status}
                        onChange={(e) =>
                          handleStatusChange(
                            request.id,
                            e.target.value as AdminCreditStatus
                          )
                        }
                        className={`admin-credits-status-select ${getStatusColor(
                          request.status
                        )}`}
                      >
                        {STATUS_OPTIONS.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Empty State */}
        {!loading && requests.length === 0 && !error && (
          <div className="admin-credits-empty-state">
            <div className="admin-credits-empty-icon">
              <i className="fas fa-inbox text-4xl text-gray-300" />
            </div>
            <p className="mt-4 text-gray-600 text-lg font-medium">
              No credit requests found
            </p>
            <p className="text-gray-400 text-sm mt-2">
              Credit requests from users will appear here
            </p>
          </div>
        )}
      </main>
    </div>
  );
};

export default BillOpsAdminCreditsPage;
