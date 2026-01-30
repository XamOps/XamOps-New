/**
 * BillOps Admin Invoices Page
 *
 * Converted from: billops/admin_invoices.html
 *
 * Admin interface for managing all invoices:
 * - View all invoices across all accounts
 * - Generate new draft invoices
 * - Merge compatible invoices (Standard + CloudFront)
 * - Download invoice previews
 * - Navigate to invoice detail page
 *
 * For: ROLE_BILLOPS_ADMIN
 */

import React, { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import useBillOpsAdminInvoices from '../hooks/useBillOpsAdminInvoices';
import type { AdminInvoiceItem } from '../types/billopsAdminInvoices';
import '../styles/billopsAdminInvoices.css';

const YEARS = [2026, 2025, 2024];
const MONTHS = [
  { value: 1, label: 'January' },
  { value: 2, label: 'February' },
  { value: 3, label: 'March' },
  { value: 4, label: 'April' },
  { value: 5, label: 'May' },
  { value: 6, label: 'June' },
  { value: 7, label: 'July' },
  { value: 8, label: 'August' },
  { value: 9, label: 'September' },
  { value: 10, label: 'October' },
  { value: 11, label: 'November' },
  { value: 12, label: 'December' },
];

const BillOpsAdminInvoicesPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    loading,
    isUpdating,
    error,
    updateError,
    mergeSuccess,
    lastUpdatedDisplay,
    selectedIds,
    generation,
    generating,
    filteredInvoices,
    canMergeSelection,
    mergeButtonText,
    filterIds,
    fetchData,
    generateInvoice,
    setSelectedIds,
    setGeneration,
    mergeSelectedInvoices,
    downloadPreview,
    getSelectedAccountName,
    isCloudFront,
  } = useBillOpsAdminInvoices();

  /**
   * Format currency for display
   */
  const formatCurrency = useCallback((value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 4,
    }).format(value || 0);
  }, []);

  /**
   * Get display account ID from invoice
   */
  const getDisplayAccountId = useCallback((invoice: AdminInvoiceItem): string => {
    if (invoice.awsAccountId && invoice.awsAccountId !== 'N/A') {
      return invoice.awsAccountId;
    }
    if (invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A') {
      return invoice.gcpProjectId;
    }
    return invoice.azureSubscriptionId || '';
  }, []);

  /**
   * Handle checkbox change
   */
  const handleCheckboxChange = useCallback((invoiceId: string, checked: boolean) => {
    setSelectedIds(prev => {
      if (checked) {
        return [...prev, invoiceId];
      } else {
        return prev.filter(id => id !== invoiceId);
      }
    });
  }, [setSelectedIds]);

  /**
   * Navigate to invoice detail page
   */
  const navigateToDetail = useCallback((invoiceId: number) => {
    navigate(`/billops-admin-invoice-detail?id=${invoiceId}`);
  }, [navigate]);

  return (
    <div className="admin-invoices-page flex-1 flex flex-col h-screen overflow-hidden">
      {/* Header */}
      <header className="admin-invoices-header h-20 flex items-center justify-between px-8 sticky top-0 z-20">
        <div className="flex items-center">
          <h2 className="text-2xl font-bold text-gray-800">Invoice Administration</h2>
          {isUpdating && (
            <div
              className="invoice-updating-spinner"
              title="Refreshing list in background..."
            />
          )}
        </div>

        <div className="flex items-center space-x-4">
          {lastUpdatedDisplay && (
            <div className="invoice-last-updated">
              <i className="far fa-clock" />
              <span>Last Updated: {lastUpdatedDisplay}</span>
            </div>
          )}

          <button
            onClick={() => fetchData(true)}
            disabled={loading || isUpdating}
            className={`invoice-refresh-btn ${isUpdating ? 'spinning' : ''}`}
            title="Refresh List"
          >
            <i className="fas fa-sync-alt" />
          </button>

          <div className="invoice-total-counter">
            Total: {filteredInvoices.length}
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 space-y-6 admin-invoices-scrollbar">
        {/* Generate New Draft Invoice Form */}
        <div className="invoice-generate-card p-6">
          <h3 className="card-title">
            <i className="fas fa-plus-circle" />
            Generate New Draft Invoice
          </h3>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
            {/* Target Account (Read-only) */}
            <div>
              <label className="invoice-form-label">Target Account</label>
              <div
                className="invoice-form-input invoice-readonly-input truncate"
                title="Selected from sidebar"
              >
                {getSelectedAccountName()}
              </div>
            </div>

            {/* Year */}
            <div>
              <label className="invoice-form-label">Year</label>
              <select
                value={generation.year}
                onChange={(e) => setGeneration(prev => ({ ...prev, year: Number(e.target.value) }))}
                className="invoice-form-input"
              >
                {YEARS.map(y => (
                  <option key={y} value={y}>{y}</option>
                ))}
              </select>
            </div>

            {/* Month */}
            <div>
              <label className="invoice-form-label">Month</label>
              <select
                value={generation.month}
                onChange={(e) => setGeneration(prev => ({ ...prev, month: Number(e.target.value) }))}
                className="invoice-form-input"
              >
                {MONTHS.map(m => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
            </div>

            {/* Generate Button */}
            <button
              onClick={generateInvoice}
              disabled={!generation.accountId || generating}
              className="invoice-generate-btn"
            >
              {generating ? (
                <>
                  <i className="fas fa-spinner fa-spin" />
                  Processing...
                </>
              ) : (
                'Generate Standard Draft'
              )}
            </button>
          </div>

          {!generation.accountId && (
            <div className="mt-3 text-xs text-red-500">
              * Please select an account from the sidebar to generate an invoice.
            </div>
          )}
        </div>

        {/* Update Error Alert */}
        {updateError && !loading && (
          <div className="invoice-alert invoice-alert-warning">
            <div className="invoice-alert-icon">
              <i className="fas fa-exclamation-triangle" />
            </div>
            <div>
              <p className="text-sm text-yellow-700">
                <span className="font-bold">Update Failed:</span> {updateError}. Showing cached data.
              </p>
            </div>
          </div>
        )}

        {/* Critical Error Alert */}
        {error && !loading && filteredInvoices.length === 0 && (
          <div className="invoice-alert invoice-alert-error">
            <div className="invoice-alert-icon">
              <i className="fas fa-times-circle" />
            </div>
            <div>
              <p className="text-sm text-red-700 font-bold">Error loading invoices</p>
              <p className="text-sm text-red-600">{error}</p>
              <button
                onClick={() => fetchData(true)}
                className="mt-2 text-sm font-medium text-red-800 hover:underline"
              >
                Try Again
              </button>
            </div>
          </div>
        )}

        {/* Merge Success Alert */}
        {mergeSuccess && (
          <div className="invoice-alert invoice-alert-success invoice-fade-in">
            <div className="invoice-alert-icon">
              <i className="fas fa-check-circle" />
            </div>
            <p className="text-sm text-green-700 font-bold">Invoices Merged Successfully!</p>
          </div>
        )}

        {/* Selection & Merge Panel */}
        {selectedIds.length > 0 && (
          <div className="invoice-selection-panel invoice-fade-in">
            <div className="flex items-center gap-3">
              <span className="invoice-selection-badge">
                {selectedIds.length} Selected
              </span>
              <span className={`text-sm ${!canMergeSelection ? 'text-red-600' : 'text-gray-600'}`}>
                {selectedIds.length !== 2 && 'Select exactly 2 drafts to enable merging.'}
                {selectedIds.length === 2 && !canMergeSelection && 'Incompatible selection (Check Account/Period/Status).'}
              </span>
            </div>

            <div>
              <button
                onClick={mergeSelectedInvoices}
                disabled={!canMergeSelection}
                className={`invoice-merge-btn ${canMergeSelection ? 'invoice-merge-btn-active' : 'invoice-merge-btn-disabled'}`}
              >
                <i className="fas fa-code-branch" />
                {mergeButtonText}
              </button>
            </div>
          </div>
        )}

        {/* Invoice Table */}
        <div className="invoice-table-container invoice-glass-card">
          {/* Loading Skeleton */}
          {loading && (
            <div className="p-6 space-y-4">
              <div className="h-4 invoice-skeleton-pulse w-full" />
              <div className="h-4 invoice-skeleton-pulse w-full" />
              <div className="h-4 invoice-skeleton-pulse w-full" />
              <div className="h-4 invoice-skeleton-pulse w-3/4" />
            </div>
          )}

          {/* Invoice Table */}
          {!loading && (
            <table className="invoice-table">
              <thead>
                <tr>
                  <th></th>
                  <th>Invoice #</th>
                  <th>Account</th>
                  <th>Period</th>
                  <th className="text-right">Total</th>
                  <th className="text-center">Status</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredInvoices.map((invoice) => {
                  const isSelected = selectedIds.includes(String(invoice.id));
                  const isDraft = invoice.status === 'DRAFT';

                  return (
                    <tr
                      key={invoice.id}
                      className={isSelected ? 'selected' : ''}
                    >
                      {/* Checkbox */}
                      <td>
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={(e) => handleCheckboxChange(String(invoice.id), e.target.checked)}
                          disabled={!isDraft}
                          className="invoice-checkbox"
                        />
                      </td>

                      {/* Invoice Number */}
                      <td>
                        <div className="flex items-center">
                          <span
                            className={`invoice-type-badge ${isCloudFront(invoice) ? 'invoice-type-cloudfront' : 'invoice-type-standard'}`}
                          >
                            {isCloudFront(invoice) ? 'CF' : 'STD'}
                          </span>
                          <button
                            onClick={() => navigateToDetail(invoice.id)}
                            className="invoice-number-link"
                          >
                            {invoice.invoiceNumber}
                          </button>
                        </div>
                      </td>

                      {/* Account */}
                      <td>
                        <div className="invoice-account-name">{invoice.accountName}</div>
                        <div className="invoice-account-id">{getDisplayAccountId(invoice)}</div>
                      </td>

                      {/* Period */}
                      <td>
                        <span className="invoice-period-badge">
                          <i className="far fa-calendar-alt" />
                          {invoice.billingPeriod}
                        </span>
                      </td>

                      {/* Total */}
                      <td className="text-right">
                        <span className="invoice-amount">{formatCurrency(invoice.amount)}</span>
                      </td>

                      {/* Status */}
                      <td className="text-center">
                        <span
                          className={`invoice-status-badge ${
                            invoice.status === 'FINALIZED'
                              ? 'invoice-status-finalized'
                              : invoice.status === 'DRAFT'
                              ? 'invoice-status-draft'
                              : 'invoice-status-void'
                          }`}
                        >
                          <i
                            className={`fas ${
                              invoice.status === 'FINALIZED' ? 'fa-check' : 'fa-pencil-alt'
                            }`}
                          />
                          {invoice.status}
                        </span>
                      </td>

                      {/* Actions */}
                      <td>
                        <div className="invoice-actions">
                          <button
                            onClick={() => downloadPreview(invoice)}
                            className="invoice-action-btn"
                            title="Download Preview PDF"
                          >
                            <i className="fas fa-file-pdf fa-lg" />
                          </button>

                          <button
                            onClick={() => navigateToDetail(invoice.id)}
                            className="invoice-manage-btn"
                          >
                            Manage
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}

                {/* Empty State */}
                {filteredInvoices.length === 0 && (
                  <tr>
                    <td colSpan={7}>
                      <div className="invoice-empty-state">
                        <i className="fas fa-folder-open" />
                        <p>
                          {filterIds.length > 0
                            ? 'No invoices found for the selected account.'
                            : 'No invoices found.'}
                        </p>
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      </main>
    </div>
  );
};

export default BillOpsAdminInvoicesPage;
