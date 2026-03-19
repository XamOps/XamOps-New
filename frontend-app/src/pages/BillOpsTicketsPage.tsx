/**
 * BillOps Tickets Page Component
 *
 * Displays and manages support tickets for Account and Billing category
 * Features: List tickets, create tickets, filter by account, real-time updates
 *
 * Converted from: billops/tickets.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Topbar integration for actions and status
 * - Soft Glass Minimal design system
 * - Premium animations and transitions
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same caching strategy (stale-while-revalidate)
 * - Same filtering logic
 * - All business logic preserved exactly
 */

import React, { useState, useMemo, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBillOpsTickets } from '../hooks/useBillOpsTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  CreateTicketFormData,
  SERVICE_OPTIONS,
  TicketSeverity,
  BillOpsTicket
} from '../types/billopsTickets';
import { DialogRoot, DialogPanel } from '../components/ui';

const BillOpsTicketsPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    tickets,
    loading,
    isUpdating,
    error,
    lastUpdated,
    hasData,
    selectedAccountId,
    fetchTickets,
    submitTicket
  } = useBillOpsTickets();

  const { setActions, clearActions } = useTopbarActions();

  const [showModal, setShowModal] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [form, setForm] = useState<CreateTicketFormData>({
    category: 'Account and Billing',
    service: 'Billing Inquiry',
    severity: '',
    subject: '',
    description: '',
    accountId: '',
    region: ''
  });

  /**
   * Format last updated timestamp
   */
  const lastUpdatedDisplay = useMemo(() => {
    if (!lastUpdated) return '';
    const date = new Date(lastUpdated);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }, [lastUpdated]);

  /**
   * Format date for ticket display
   */
  const formatDate = (dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };


  /**
   * Get severity badge classes
   */
  const getSeverityClasses = (severity: string): string => {
    switch (severity) {
      case 'High':
      case 'Critical':
        return 'bg-red-50 text-red-700 border-red-200';
      case 'Medium':
        return 'bg-orange-50 text-orange-700 border-orange-200';
      case 'Low':
        return 'bg-blue-50 text-blue-700 border-blue-200';
      default:
        return 'bg-gray-50 text-gray-700 border-gray-200';
    }
  };

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') {
      return 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10';
    }
    if (status === 'IN_PROGRESS') {
      return 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10';
    }
    return 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10';
  };

  const getSeverityBadgeClasses = (severity: string): string => {
    const classes = getSeverityClasses(severity);
    if (classes.includes('red')) return 'bg-rose-500 shadow-[0_0_8px_rgba(244,63,94,0.4)]';
    if (classes.includes('orange')) return 'bg-amber-500 shadow-[0_0_8px_rgba(245,158,11,0.4)]';
    return 'bg-blue-500 shadow-[0_0_8px_rgba(59,130,246,0.4)]';
  };

  const getSeverityTextClasses = (severity: string): string => {
    const classes = getSeverityClasses(severity);
    if (classes.includes('red')) return 'text-rose-600';
    if (classes.includes('orange')) return 'text-amber-600';
    return 'text-blue-600';
  };

  /**
   * Open create ticket modal
   */
  const openCreateModal = () => {
    setForm({
      category: 'Account and Billing',
      service: 'Billing Inquiry',
      severity: '',
      subject: '',
      description: '',
      accountId: selectedAccountId || '',
      region: ''
    });
    setShowModal(true);
  };

  /**
   * Handle form submission
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);

    try {
      await submitTicket(form);
      setShowModal(false);
    } catch (err) {
      console.error('Error submitting ticket:', err);
      globalThis.alert('There was an error submitting your case. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * View ticket detail
   */
  const viewTicket = (id: number) => {
    navigate(`/billops-ticket-detail?id=${id}`);
  };

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    const openTicketsCount = tickets.filter(t => t.status === 'OPEN').length;
    const inProgressCount = tickets.filter(t => t.status === 'IN_PROGRESS').length;

    setActions(
      <div className="flex items-center gap-6">
        {/* Page Title & Icon */}
        <div className="hidden lg:flex items-center gap-3 pr-6 border-r border-slate-200/50">
          <div className="w-9 h-9 flex items-center justify-center rounded-xl bg-purple-50 text-purple-600 shadow-sm border border-purple-100/50">
            <i className="fas fa-file-invoice-dollar"></i>
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none">Billing Tickets</h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">Account & Billing Support</p>
          </div>
        </div>

        {/* Live Stats */}
        {!loading && tickets.length > 0 && (
          <div className="hidden xl:flex items-center gap-4">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Open</span>
              <span className="text-xs font-bold text-slate-700">{openTicketsCount}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-500"></span>
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">In Progress</span>
              <span className="text-xs font-bold text-slate-700">{inProgressCount}</span>
            </div>
          </div>
        )}

        <div className="flex items-center gap-3">
          <button
            onClick={() => fetchTickets(true)}
            disabled={loading || isUpdating}
            className="w-10 h-10 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-xl transition-all border border-transparent hover:border-slate-200/50"
            title="Refresh Tickets"
          >
            <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
          </button>
          <button
            onClick={openCreateModal}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all hover:-translate-y-0.5 active:scale-95"
          >
            <i className="fas fa-plus mr-1" />
            Create New Case
          </button>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, isUpdating, fetchTickets, tickets]);

  /**
   * Render loading skeleton
   */
  const renderLoadingSkeleton = () => (
    <div className="grid gap-6 md:grid-cols-2">
      {[1, 2, 3, 4].map(i => (
        <div key={i} className="bg-white/80 backdrop-blur-xl border border-white/40 rounded-[2rem] p-6 shadow-sm">
          <div className="flex justify-between items-start mb-6">
            <div className="space-y-3 w-2/3">
              <div className="h-5 bg-slate-200 rounded-full w-24 animate-pulse"></div>
              <div className="h-6 bg-slate-200 rounded-xl w-full animate-pulse"></div>
            </div>
            <div className="h-8 bg-slate-200 rounded-full w-20 animate-pulse"></div>
          </div>
          <div className="space-y-2 mb-6">
            <div className="h-4 bg-slate-100 rounded-lg w-full animate-pulse"></div>
            <div className="h-4 bg-slate-100 rounded-lg w-[85%] animate-pulse"></div>
          </div>
          <div className="flex items-center gap-4 pt-4 border-t border-slate-50">
            <div className="h-4 bg-slate-50 rounded w-24 animate-pulse"></div>
            <div className="h-4 bg-slate-50 rounded w-20 animate-pulse"></div>
          </div>
        </div>
      ))}
    </div>
  );

  /**
   * Render empty state
   */
  const renderEmptyState = () => (
    <div className="text-center py-20 bg-white/60 backdrop-blur-sm rounded-2xl border border-gray-200 shadow-sm">
      <div className="w-20 h-20 bg-indigo-50 rounded-full flex items-center justify-center mx-auto mb-6">
        <i className="fas fa-ticket-alt text-indigo-300 text-4xl"></i>
      </div>
      <h3 className="text-xl font-bold text-gray-900 mb-2">No tickets found</h3>
      <p className="text-gray-500 max-w-sm mx-auto mb-8">
        {selectedAccountId
          ? 'No tickets found for this account. Create a new case to get help.'
          : 'There are no support tickets in the Account and Billing category.'}
      </p>
      <button
        onClick={openCreateModal}
        className="inline-flex items-center px-6 py-3 border border-transparent text-base font-medium rounded-xl shadow-sm text-white bg-indigo-600 hover:bg-indigo-700 transition-all duration-200 hover:shadow-lg hover:-translate-y-0.5"
      >
        <i className="fas fa-plus mr-2"></i> Create New Case
      </button>
    </div>
  );

  /**
   * Render ticket card
   */
  const renderTicketCard = (ticket: BillOpsTicket) => (
    <div
      key={ticket.id}
      className={`group relative bg-white/70 backdrop-blur-xl border p-7 rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] hover:shadow-[0_24px_48px_rgba(0,0,0,0.08)] hover:bg-white transition-all duration-500 cursor-pointer overflow-hidden ${
        ticket.hasUnreadAdminReply
          ? 'border-indigo-300 hover:border-indigo-400 ring-1 ring-indigo-200/50'
          : 'border-white hover:border-purple-100/50'
      }`}
      onClick={() => viewTicket(ticket.id)}
    >
      {/* Background Decor */}
      <div className="absolute top-0 right-0 w-40 h-40 bg-gradient-to-br from-indigo-500/5 to-purple-500/5 rounded-full blur-3xl -mr-20 -mt-20 group-hover:scale-150 transition-transform duration-700"></div>

      <div className="relative flex flex-col h-full">
        {/* Header: ID & Status */}
        <div className="flex justify-between items-start mb-5">
          <div className="flex flex-col gap-1.5">
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Billing Case #{ticket.id}</span>
              {ticket.hasUnreadAdminReply && (
                <span className="flex items-center gap-1 px-2 py-0.5 bg-indigo-600 text-white text-[9px] font-black rounded-full uppercase tracking-widest shadow-sm animate-pulse">
                  <span className="w-1.5 h-1.5 rounded-full bg-white"></span>
                  New Reply
                </span>
              )}
            </div>
            <h3 className="text-lg font-bold text-slate-900 group-hover:text-indigo-600 transition-colors line-clamp-1 leading-tight tracking-tight">
              {ticket.subject}
            </h3>
          </div>
          <span
            className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm transition-all ${getStatusBadgeClasses(ticket.status)}`}
          >
            <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
            {ticket.status}
          </span>
        </div>

        {/* Metadata Badges */}
        <div className="flex flex-wrap gap-2.5 mb-6">
          <span className="flex items-center gap-2 px-3 py-1.5 bg-purple-50/80 text-purple-600 rounded-xl text-[10px] font-bold tracking-tight border border-purple-100/50">
            <i className="fas fa-file-invoice text-[8px] text-purple-400"></i>
            {ticket.service}
          </span>
          {ticket.region && (
            <span className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 text-slate-500 rounded-xl text-[10px] font-bold tracking-tight border border-slate-100">
              <i className="fas fa-globe-americas text-[8px] text-slate-300"></i>
              {ticket.region}
            </span>
          )}
        </div>

        {/* Description */}
        <p className="text-sm text-slate-500 line-clamp-2 mb-8 leading-relaxed flex-1">
          {ticket.description}
        </p>

        <div className="flex items-center justify-between pt-5 border-t border-slate-100/60">
          <div className="flex items-center gap-2">
            <div className={`w-2 h-2 rounded-full ${getSeverityBadgeClasses(ticket.severity)}`}></div>
            <span className={`text-[10px] font-bold uppercase tracking-widest ${getSeverityTextClasses(ticket.severity)}`}>
              {ticket.severity} SEVERITY
            </span>
          </div>
          <div className="flex items-center gap-2 text-[10px] font-bold text-slate-400 uppercase tracking-tighter">
            <i className="far fa-clock text-[12px] opacity-70"></i>
            {formatDate(ticket.createdAt)}
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Main Content Scroll Area */}
      <main className="flex-1 overflow-y-auto custom-scrollbar p-6 lg:p-8">
        <div className="max-w-7xl mx-auto space-y-8">

          {/* Subheader: Account Info & Sync Status */}
          <div className="flex flex-wrap items-center justify-between gap-4 px-2">
            <div className="flex items-center gap-3">
              {selectedAccountId ? (
                <div className="flex items-center gap-2 px-3 py-1 bg-indigo-50 text-indigo-700 rounded-full border border-indigo-100 text-[10px] font-bold uppercase tracking-wider shadow-sm">
                  <i className="fas fa-at opacity-70"></i>
                  <span>Active Account: {selectedAccountId}</span>
                </div>
              ) : (
                <div className="flex items-center gap-2 px-3 py-1 bg-slate-100 text-slate-500 rounded-full border border-slate-200 text-[10px] font-bold uppercase tracking-wider">
                  <i className="fas fa-info-circle opacity-70"></i>
                  <span>Viewing All Accounts</span>
                </div>
              )}
              {isUpdating && (
                <div className="flex items-center gap-2 text-[10px] font-bold text-indigo-400 uppercase tracking-widest animate-pulse">
                  <span className="w-1.5 h-1.5 rounded-full bg-indigo-500"></span>
                  Updating...
                </div>
              )}
            </div>

            {lastUpdated && (
              <div className="flex items-center gap-2 text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                <i className="fas fa-history opacity-70"></i>
                Last synced {lastUpdatedDisplay}
              </div>
            )}
          </div>

          {/* Loading State */}
          {loading && renderLoadingSkeleton()}

          {/* Error State */}
          {error && !hasData && (
            <div className="bg-red-50 border border-red-200 rounded-xl p-6 text-center">
              <i className="fas fa-exclamation-triangle text-red-500 text-3xl mb-3"></i>
              <h3 className="text-lg font-medium text-red-900 mb-1">Failed to load tickets</h3>
              <p className="text-sm text-red-700 mb-4">{error}</p>
              <button
                onClick={() => fetchTickets(true)}
                className="text-sm font-medium text-red-700 bg-white border border-red-200 px-4 py-2 rounded-lg hover:bg-red-50 shadow-sm"
              >
                Try Again
              </button>
            </div>
          )}

          {/* Empty State */}
          {!loading && !error && tickets.length === 0 && renderEmptyState()}

          {/* Tickets Grid */}
          {!loading && tickets.length > 0 && (
            <div className="grid gap-6 md:grid-cols-2">
              {tickets.map(ticket => renderTicketCard(ticket))}
            </div>
          )}
        </div>
      </main>

      {/* Create Ticket Modal */}
      <DialogRoot isOpen={showModal} onClose={() => setShowModal(false)} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <i className="fas fa-plus-circle text-indigo-600"></i>
              <div>
                <div className="text-xl font-bold text-gray-900">Create Support Case</div>
                <p className="text-gray-500 text-sm mt-0.5">Submit a new request for billing or account support</p>
              </div>
            </div>
          }
          onClose={() => setShowModal(false)}
          footer={
            <>
              <button
                type="button"
                onClick={() => setShowModal(false)}
                className="px-5 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-gray-900 transition-colors shadow-sm"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="billops-ticket-form"
                disabled={isSubmitting}
                className="px-5 py-2.5 text-sm font-medium text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 disabled:opacity-70 disabled:cursor-not-allowed transition-all shadow-sm hover:shadow hover:translate-y-px flex items-center"
              >
                {isSubmitting && <i className="fas fa-circle-notch fa-spin mr-2"></i>}
                {isSubmitting ? 'Submitting...' : 'Submit Case'}
              </button>
            </>
          }
        >
          <form id="billops-ticket-form" onSubmit={handleSubmit}>
            <div className="space-y-5">
                  {/* Category */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Category
                    </label>
                    <div className="relative">
                      <select
                        value={form.category}
                        disabled
                        className="w-full rounded-xl border-gray-200 bg-gray-50 text-gray-500 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none cursor-not-allowed"
                      >
                        <option>Account and Billing</option>
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-400">
                        <i className="fas fa-lock text-xs"></i>
                      </div>
                    </div>
                  </div>

                  {/* Service */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Service
                    </label>
                    <div className="relative">
                      <select
                        value={form.service}
                        onChange={e =>
                          setForm({ ...form, service: e.target.value })
                        }
                        className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                      >
                        {SERVICE_OPTIONS[form.category].map(service => (
                          <option key={service} value={service}>
                            {service}
                          </option>
                        ))}
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                        <i className="fas fa-chevron-down text-xs"></i>
                      </div>
                    </div>
                  </div>

                  {/* Severity */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Severity <span className="text-red-500">*</span>
                    </label>
                    <div className="relative">
                      <select
                        value={form.severity}
                        onChange={e =>
                          setForm({
                            ...form,
                            severity: e.target.value as TicketSeverity
                          })
                        }
                        required
                        className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                      >
                        <option value="">Select...</option>
                        <option value="Low">Low</option>
                        <option value="Medium">Medium</option>
                        <option value="High">High</option>
                        <option value="Critical">Critical</option>
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                        <i className="fas fa-chevron-down text-xs"></i>
                      </div>
                    </div>
                  </div>

                  {/* Subject */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Subject <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={form.subject}
                      onChange={e =>
                        setForm({ ...form, subject: e.target.value })
                      }
                      required
                      placeholder="Brief summary of the issue"
                      className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400"
                    />
                  </div>

                  {/* Description */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Description <span className="text-red-500">*</span>
                    </label>
                    <textarea
                      value={form.description}
                      onChange={e =>
                        setForm({ ...form, description: e.target.value })
                      }
                      rows={4}
                      required
                      placeholder="Provide detailed information about your request..."
                      className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400 resize-none"
                    ></textarea>
                  </div>
                </div>
          </form>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

export default BillOpsTicketsPage;
