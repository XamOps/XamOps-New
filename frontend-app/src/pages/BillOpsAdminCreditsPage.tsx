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

import React, { useCallback, useEffect, useState } from 'react';
import useBillOpsAdminCredits from '../hooks/useBillOpsAdminCredits';
import { useTopbar } from '../contexts/TopbarContext';
import type { AdminCreditRequest, AdminCreditStatus, CreditAttachment } from '../types/billopsAdminCredits';
import {
  attachmentKind,
  downloadCreditAttachment,
  formatFileSize,
  viewCreditAttachment,
  type AttachmentKind,
} from '../utils/creditAttachments';

const FILE_ICONS: Record<AttachmentKind, string> = {
  pdf: 'fas fa-file-pdf',
  image: 'fas fa-file-image',
  sheet: 'fas fa-file-excel',
  doc: 'fas fa-file-alt',
  archive: 'fas fa-file-archive',
  file: 'fas fa-file',
};

const STATUS_OPTIONS: { value: AdminCreditStatus; label: string }[] = [
  { value: 'submitted', label: 'Submitted' },
  { value: 'documentation_review', label: 'Documentation Review' },
  { value: 'technical_verification', label: 'Technical Verification' },
  { value: 'approved', label: 'Approved' },
  { value: 'rejected', label: 'Rejected' },
];

/** Requests store a free-form identifier, e.g. "GCP: my-project (011DDB-9E3A99-DE0B85)". */
const PROVIDER_PREFIX = /^(aws|gcp|google|azure)\s*[:-]\s*/i;

const PROVIDER_STYLES: Record<string, { label: string; icon: string; chip: string; tile: string }> = {
  AWS: { label: 'AWS', icon: 'fab fa-aws', chip: 'bg-orange-50 text-orange-600 border-orange-100', tile: 'bg-orange-50 text-orange-500' },
  GCP: { label: 'GCP', icon: 'fab fa-google', chip: 'bg-blue-50 text-blue-600 border-blue-100', tile: 'bg-blue-50 text-blue-500' },
  AZURE: { label: 'Azure', icon: 'fab fa-microsoft', chip: 'bg-sky-50 text-sky-600 border-sky-100', tile: 'bg-sky-50 text-sky-500' },
};

const UNKNOWN_PROVIDER = { label: '', icon: 'fas fa-cloud', chip: 'bg-gray-50 text-gray-500 border-gray-100', tile: 'bg-gray-100 text-gray-400' };

/** Number of characters beyond which the use case is clamped behind a "Show more" toggle. */
const USE_CASE_CLAMP_AT = 110;

/**
 * Splits the stored identifier into the parts the table renders: the account name (when the id
 * could be linked to a cloud account), the bare identifier, any trailing detail the user typed in
 * parentheses, and the provider styling.
 */
const describeAccount = (request: AdminCreditRequest) => {
  const raw = (request.awsAccountId || '').trim();
  const prefixMatch = raw.match(PROVIDER_PREFIX);

  let identifier = raw.replace(PROVIDER_PREFIX, '').trim();
  let detail: string | null = null;
  const parenthesis = identifier.indexOf('(');
  if (parenthesis > 0) {
    detail = identifier.slice(parenthesis).replace(/[()]/g, '').trim() || null;
    identifier = identifier.slice(0, parenthesis).trim();
  }

  let providerKey = (request.provider || prefixMatch?.[1] || '').toUpperCase();
  if (providerKey === 'GOOGLE') providerKey = 'GCP';

  return {
    name: request.accountName?.trim() || null,
    identifier: identifier || raw || '—',
    detail,
    provider: PROVIDER_STYLES[providerKey] || UNKNOWN_PROVIDER,
  };
};

/**
 * One supporting document: the name opens it in a new tab, the icon button downloads it. Both
 * mint a fresh short-lived S3 link on click.
 */
const AttachmentChip: React.FC<{ requestId: number; attachment: CreditAttachment }> = ({ requestId, attachment }) => {
  const [isBusy, setIsBusy] = useState(false);
  const size = formatFileSize(attachment.size);

  const run = async (action: () => Promise<void>) => {
    setIsBusy(true);
    try {
      await action();
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Could not open this document.');
    } finally {
      setIsBusy(false);
    }
  };

  return (
    <span className={`inline-flex items-center gap-1.5 pl-2 pr-1 py-1 rounded-lg border border-gray-200 bg-white ${isBusy ? 'opacity-50' : ''}`}>
      <i className={`${FILE_ICONS[attachmentKind(attachment.name, attachment.type)]} text-[10px] text-gray-400`} />
      <button
        type="button"
        disabled={isBusy}
        onClick={() => run(() => viewCreditAttachment(requestId, attachment.index))}
        className="text-[11px] font-bold text-gray-600 hover:text-indigo-600 transition-colors truncate max-w-[160px]"
        title={size ? `${attachment.name} (${size})` : attachment.name}
      >
        {attachment.name}
      </button>
      {size && <span className="text-[10px] font-semibold text-gray-300">{size}</span>}
      <button
        type="button"
        disabled={isBusy}
        onClick={() => run(() => downloadCreditAttachment(requestId, attachment.index, attachment.name))}
        className="w-5 h-5 flex items-center justify-center rounded-md text-gray-400 hover:text-indigo-600 hover:bg-gray-50 transition-colors"
        title={`Download ${attachment.name}`}
        aria-label={`Download ${attachment.name}`}
      >
        <i className="fas fa-download text-[9px]" />
      </button>
    </span>
  );
};

const parseServices = (services: string | null | undefined): string[] =>
  (services || '')
    .split(',')
    .map((service) => service.trim())
    .filter(Boolean);

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

  /** Ids of rows whose full use case is expanded */
  const [expandedRows, setExpandedRows] = useState<Set<number>>(() => new Set());

  const toggleRow = useCallback((id: number) => {
    setExpandedRows((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

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
   * Format the submission time, shown under the date
   */
  const formatTime = useCallback((dateString: string): string => {
    // Date-only values (e.g. "2026-03-15") carry no meaningful time of day
    if (!String(dateString ?? '').includes(':')) return '';
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleTimeString('en-US', {
      hour: 'numeric',
      minute: '2-digit',
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
        <div className="hidden xl:flex items-center gap-4 bg-white/50 p-1.5 rounded-2xl border border-gray-200/50 shadow-sm">
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
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
              <h2 className="text-sm font-bold text-gray-900">Credit Requests</h2>
              <span className="text-[11px] font-bold text-gray-400 uppercase tracking-widest">
                {requests.length} {requests.length === 1 ? 'Request' : 'Requests'}
              </span>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-100">
                <thead>
                  <tr className="bg-gray-50/50">
                    <th className="px-6 py-3.5 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Account</th>
                    <th className="px-6 py-3.5 text-right text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Expected Credits</th>
                    <th className="px-6 py-3.5 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest w-[38%]">Use Case</th>
                    <th className="px-6 py-3.5 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Priority</th>
                    <th className="px-6 py-3.5 text-left text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Submitted</th>
                    <th className="px-6 py-3.5 text-center text-[10px] font-black text-gray-400 uppercase tracking-widest whitespace-nowrap">Current Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {requests.map((request: AdminCreditRequest) => {
                    const account = describeAccount(request);
                    const useCase = (request.useCase || '').trim();
                    const services = parseServices(request.services);
                    const attachments = request.attachments ?? [];
                    const isExpanded = expandedRows.has(request.id);
                    const isClamped = useCase.length > USE_CASE_CLAMP_AT;
                    const visibleServices = isExpanded ? services : services.slice(0, 3);

                    return (
                      <tr key={request.id} className="hover:bg-gray-50/50 transition-colors align-top">
                        {/* Account name + provider identifier */}
                        <td className="px-6 py-5">
                          <div className="flex items-start gap-3">
                            <div className={`w-9 h-9 shrink-0 rounded-xl flex items-center justify-center text-sm transition-colors ${account.provider.tile}`}>
                              <i className={account.provider.icon} />
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-2">
                                <span
                                  className={`text-sm font-bold text-gray-900 truncate max-w-[220px] ${account.name ? '' : 'font-mono tracking-tight'}`}
                                  title={account.name || account.identifier}
                                >
                                  {account.name || account.identifier}
                                </span>
                                {account.provider.label && (
                                  <span className={`inline-flex items-center px-1.5 py-0.5 rounded-md text-[9px] font-black uppercase tracking-wider border ${account.provider.chip}`}>
                                    {account.provider.label}
                                  </span>
                                )}
                              </div>
                              <div className="mt-0.5 font-mono text-[11px] text-gray-500 truncate max-w-[260px]" title={request.awsAccountId}>
                                {account.name ? account.identifier : 'Not linked to an account'}
                              </div>
                              {account.detail && (
                                <div className="mt-0.5 font-mono text-[10px] text-gray-400 truncate max-w-[260px]" title={account.detail}>
                                  {account.detail}
                                </div>
                              )}
                            </div>
                          </div>
                        </td>

                        {/* Requested Credits */}
                        <td className="px-6 py-5 whitespace-nowrap text-right">
                          <span className="text-base font-black text-gray-900 tabular-nums">{formatCurrency(request.expectedCredits)}</span>
                        </td>

                        {/* Use Case */}
                        <td className="px-6 py-5">
                          {useCase ? (
                            <p className={`text-xs leading-relaxed text-gray-600 whitespace-pre-line ${isClamped && !isExpanded ? 'line-clamp-2' : ''}`}>
                              {useCase}
                            </p>
                          ) : (
                            <span className="text-xs text-gray-300">No use case provided</span>
                          )}

                          {isClamped && (
                            <button
                              type="button"
                              onClick={() => toggleRow(request.id)}
                              className="mt-1.5 inline-flex items-center gap-1 text-[11px] font-bold text-indigo-600 hover:text-indigo-700 transition-colors"
                              aria-expanded={isExpanded}
                            >
                              {isExpanded ? 'Show less' : 'Show more'}
                              <i className={`fas fa-chevron-${isExpanded ? 'up' : 'down'} text-[8px]`} />
                            </button>
                          )}

                          {visibleServices.length > 0 && (
                            <div className="mt-2 flex flex-wrap items-center gap-1">
                              {visibleServices.map((service, index) => (
                                <span key={`${service}-${index}`} className="inline-flex items-center px-2 py-0.5 rounded-md bg-gray-50 border border-gray-100 text-[10px] font-bold text-gray-500">
                                  {service}
                                </span>
                              ))}
                              {!isExpanded && services.length > visibleServices.length && (
                                <span className="text-[10px] font-bold text-gray-400">+{services.length - visibleServices.length}</span>
                              )}
                            </div>
                          )}

                          {attachments.length > 0 && (
                            <div className="mt-2.5">
                              <div className="text-[9px] font-black text-gray-400 uppercase tracking-widest mb-1.5">
                                <i className="fas fa-paperclip mr-1" />
                                {attachments.length} {attachments.length === 1 ? 'Document' : 'Documents'}
                              </div>
                              <div className="flex flex-wrap gap-1.5">
                                {attachments.map((attachment) => (
                                  <AttachmentChip key={attachment.index} requestId={request.id} attachment={attachment} />
                                ))}
                              </div>
                            </div>
                          )}
                        </td>

                        {/* Priority */}
                        <td className="px-6 py-5 whitespace-nowrap">
                          {request.priority ? (
                            <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider border ${getPriorityBadge(request.priority)}`}>
                              {request.priority}
                            </span>
                          ) : (
                            <span className="text-xs font-bold text-gray-300">—</span>
                          )}
                        </td>

                        {/* Submitted Date */}
                        <td className="px-6 py-5 whitespace-nowrap">
                          <div className="text-xs font-bold text-gray-700">{formatDate(request.submittedDate)}</div>
                          {formatTime(request.submittedDate) && (
                            <div className="mt-0.5 text-[10px] font-semibold text-gray-400">{formatTime(request.submittedDate)}</div>
                          )}
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
                                  aria-label={`Status for request ${account.name || account.identifier}`}
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
                    );
                  })}
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
