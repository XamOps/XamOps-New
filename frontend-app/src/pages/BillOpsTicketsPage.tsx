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
 * - Improved UI with modern card design and better spacing
 * - Enhanced status and severity badges with icons
 * - Better loading states and animations
 * - More professional modal design
 * - Improved responsive design
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same caching strategy (stale-while-revalidate)
 * - Same filtering logic
 * - All business logic preserved exactly
 */

import React, { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBillOpsTickets } from '../hooks/useBillOpsTickets';
import {
  CreateTicketFormData,
  SERVICE_OPTIONS,
  TicketSeverity,
  BillOpsTicket
} from '../types/billopsTickets';

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
   * Get status badge classes
   */
  const getStatusClasses = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-100 text-green-800';
      case 'CLOSED':
        return 'bg-gray-100 text-gray-800';
      case 'IN_PROGRESS':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  /**
   * Get status dot classes
   */
  const getStatusDotClasses = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-400';
      case 'CLOSED':
        return 'bg-gray-400';
      case 'IN_PROGRESS':
        return 'bg-yellow-400';
      default:
        return 'bg-gray-400';
    }
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
      alert('There was an error submitting your case. Please try again.');
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
   * Render loading skeleton
   */
  const renderLoadingSkeleton = () => (
    <div className="space-y-4">
      {[1, 2, 3].map(i => (
        <div key={i} className="bg-white/80 backdrop-blur-sm shadow-sm rounded-xl p-6 border border-gray-100">
          <div className="flex justify-between items-start">
            <div className="w-full">
              <div className="flex gap-3 mb-4">
                <div className="h-5 bg-gray-200 rounded w-16 animate-pulse"></div>
                <div className="h-5 bg-gray-200 rounded w-20 animate-pulse"></div>
                <div className="h-5 bg-gray-200 rounded w-16 animate-pulse"></div>
              </div>
              <div className="h-6 bg-gray-200 rounded w-1/3 mb-3 animate-pulse"></div>
              <div className="h-4 bg-gray-200 rounded w-2/3 animate-pulse"></div>
            </div>
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
      className="group relative bg-white rounded-xl p-6 shadow-sm border border-gray-100 hover:shadow-md hover:border-indigo-100 transition-all duration-200 cursor-pointer"
      onClick={() => viewTicket(ticket.id)}
    >
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-4">
        <div className="flex-1 min-w-0">
          {/* Badges Row */}
          <div className="flex items-center gap-2 mb-3 flex-wrap">
            <span className="text-xs font-mono text-gray-400 bg-gray-50 px-2 py-0.5 rounded border border-gray-100">
              #{ticket.id}
            </span>

            {/* Status Badge */}
            <span
              className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ring-1 ring-inset ${ticket.status === 'OPEN' ? 'bg-green-50 text-green-700 ring-green-600/20' :
                  ticket.status === 'IN_PROGRESS' ? 'bg-yellow-50 text-yellow-800 ring-yellow-600/20' :
                    'bg-gray-50 text-gray-600 ring-gray-500/10'
                }`}
            >
              <span
                className={`w-1.5 h-1.5 rounded-full mr-1.5 ${getStatusDotClasses(ticket.status)}`}
              ></span>
              {ticket.status}
            </span>

            {/* Severity Badge */}
            <span
              className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${getSeverityClasses(
                ticket.severity
              ).replace('bg-', 'bg-opacity-50 bg-')}`} // Adjust opacity for subtlety
            >
              {(ticket.severity === 'High' || ticket.severity === 'Critical') && (
                <i className="fas fa-exclamation-circle mr-1.5"></i>
              )}
              {ticket.severity}
            </span>

            {/* Account Badge */}
            {ticket.accountId && (
              <span className="flex items-center text-xs text-gray-500 bg-gray-50 px-2 py-0.5 rounded border border-gray-100">
                <i className="fas fa-cloud text-gray-400 mr-1.5"></i>
                {ticket.accountId}
              </span>
            )}
          </div>

          {/* Subject */}
          <h3 className="text-lg font-bold text-gray-900 mb-2 group-hover:text-indigo-600 transition-colors pr-8">
            {ticket.subject}
          </h3>

          {/* Description */}
          <p className="text-sm text-gray-600 line-clamp-2 mb-4 leading-relaxed">
            {ticket.description}
          </p>

          {/* Metadata Row */}
          <div className="flex items-center gap-6 text-xs text-gray-500">
            <div className="flex items-center">
              <i className="far fa-clock mr-2 text-gray-400"></i>
              <span>{formatDate(ticket.createdAt)}</span>
            </div>
            <div className="flex items-center">
              <i className="fas fa-cube mr-2 text-gray-400"></i>
              <span>{ticket.service}</span>
            </div>
            {ticket.region && (
              <div className="flex items-center">
                <i className="fas fa-globe-americas mr-2 text-gray-400"></i>
                <span className="font-mono">{ticket.region}</span>
              </div>
            )}
          </div>
        </div>

        {/* Chevron Icon */}
        <div className="hidden sm:flex items-center justify-center w-8 h-8 rounded-full bg-gray-50 text-gray-400 group-hover:bg-indigo-50 group-hover:text-indigo-600 transition-colors absolute right-6 top-6">
          <i className="fas fa-chevron-right text-sm"></i>
        </div>
      </div>
    </div>
  );

  return (
    <div className="billops-tickets-page flex-1 flex flex-col h-screen overflow-hidden bg-gray-50/50">
      {/* Main Content Scroll Area */}
      <main className="flex-1 overflow-y-auto custom-scrollbar p-8">
        <div className="w-full space-y-8">

          {/* Header Section */}
          <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
            <div>
              <h1 className="text-2xl font-bold text-gray-900 flex items-center">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white mr-3 shadow-sm">
                  <i className="fas fa-ticket-alt text-lg"></i>
                </div>
                Support Tickets
                {isUpdating && (
                  <span className="ml-3 inline-block w-4 h-4 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin"></span>
                )}
              </h1>
              <div className="flex items-center gap-3 mt-1 ml-13">
                <p className="text-gray-500">Manage your billing and account support requests</p>
                {selectedAccountId && (
                  <span className="text-xs font-medium px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-100">
                    Account: {selectedAccountId}
                  </span>
                )}
              </div>
            </div>

            <div className="flex items-center gap-3">
              <div className="bg-white p-1 rounded-lg border border-gray-200 shadow-sm flex items-center">
                {lastUpdated && (
                  <span className="text-xs text-gray-400 px-3 py-1.5 border-r border-gray-100">
                    Synced {lastUpdatedDisplay}
                  </span>
                )}
                <button
                  onClick={() => fetchTickets(true)}
                  disabled={loading || isUpdating}
                  className="p-2 text-gray-500 hover:text-indigo-600 hover:bg-gray-50 rounded-md transition-all duration-200"
                  title="Refresh Tickets"
                >
                  <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
                </button>
              </div>

              <button
                onClick={openCreateModal}
                className="inline-flex items-center px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium rounded-xl shadow-sm hover:shadow-md transition-all duration-200"
              >
                <i className="fas fa-plus mr-2"></i> Create New Case
              </button>
            </div>
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
            <div className="grid gap-4">
              {tickets.map(ticket => renderTicketCard(ticket))}
            </div>
          )}
        </div>
      </main>

      {/* Create Ticket Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-gray-900/50 backdrop-blur-sm transition-opacity"
            onClick={() => setShowModal(false)}
          ></div>

          {/* Modal */}
          <div className="flex min-h-full items-center justify-center p-4">
            <div
              className="relative bg-white rounded-2xl shadow-2xl max-w-lg w-full overflow-hidden transform transition-all border border-gray-100"
              onClick={e => e.stopPropagation()}
            >
              <form onSubmit={handleSubmit}>
                {/* Modal Header */}
                <div className="bg-gradient-to-r from-indigo-600 to-purple-600 px-8 py-6">
                  <h3 className="text-xl font-bold text-white flex items-center">
                    <i className="fas fa-plus-circle mr-3 text-indigo-200"></i>
                    Create Support Case
                  </h3>
                  <p className="text-indigo-100 text-sm mt-1">Submit a new request for billing or account support</p>
                </div>

                {/* Modal Body */}
                <div className="px-8 py-6 space-y-5">
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

                  {/* Severity and Region */}
                  <div className="grid grid-cols-2 gap-5">
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
                    <div>
                      <label className="block text-sm font-semibold text-gray-700 mb-2">
                        Region <span className="text-gray-400 font-normal">(Optional)</span>
                      </label>
                      <input
                        type="text"
                        value={form.region}
                        onChange={e =>
                          setForm({ ...form, region: e.target.value })
                        }
                        placeholder="e.g., us-east-1"
                        className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400"
                      />
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

                {/* Modal Footer */}
                <div className="bg-gray-50 px-8 py-5 flex justify-end gap-3 border-t border-gray-100">
                  <button
                    type="button"
                    onClick={() => setShowModal(false)}
                    className="px-5 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-gray-900 transition-colors shadow-sm"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={isSubmitting}
                    className="px-5 py-2.5 text-sm font-medium text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 disabled:opacity-70 disabled:cursor-not-allowed transition-all shadow-sm hover:shadow hover:translate-y-px flex items-center"
                  >
                    {isSubmitting && (
                      <i className="fas fa-circle-notch fa-spin mr-2"></i>
                    )}
                    {isSubmitting ? 'Submitting...' : 'Submit Case'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default BillOpsTicketsPage;
